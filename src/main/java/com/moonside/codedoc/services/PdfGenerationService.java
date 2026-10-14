package com.moonside.codedoc.services;

import com.moonside.codedoc.exceptions.GenerationException;
import com.moonside.codedoc.model.CodeEntity;
import com.moonside.codedoc.model.enums.EntityKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Construit un PDF : pour chaque type du projet (classe, interface, enum, record,
 * exception...), sa description puis ses constructeurs et méthodes avec leur
 * documentation (générée par le LLM, ou celle déjà présente dans le code).
 * Renvoie directement les octets en mémoire (pas d'écriture sur le disque
 * local) : ils sont ensuite sauvegardés dans MongoDB.
 */
@Service
public class PdfGenerationService {

    private static final float MARGIN = 50;
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float USABLE_WIDTH = PDRectangle.A4.getWidth() - 2 * MARGIN;
    private static final float DOC_INDENT = 15;

    private static final Set<EntityKind> TYPE_KINDS =
            Set.of(EntityKind.MODULE, EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM, EntityKind.RECORD);

    public byte[] generate(String projectTitle, List<CodeEntity> entities,
                           Map<String, String> generatedDocs) {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Cursor cursor = new Cursor(document);
            cursor.writeTitle(projectTitle);

            List<CodeEntity> types = entities.stream()
                    .filter(e -> TYPE_KINDS.contains(e.kind()))
                    // Un fichier sans fonction ni description (ex. LLM indisponible) n'apporte qu'un titre vide.
                    .filter(e -> e.kind() != EntityKind.MODULE
                            || docFor(e, generatedDocs) != null
                            || entities.stream().anyMatch(m -> e.id().equals(m.parentId())))
                    .sorted(Comparator.comparing(CodeEntity::id))
                    .toList();

            if (types.isEmpty()) {
                cursor.writeBody("Aucun code source reconnu dans ce projet.");
            }

            for (CodeEntity type : types) {
                cursor.writeTypeHeading(type);
                String typeDoc = docFor(type, generatedDocs);
                if (typeDoc != null) {
                    cursor.writeBody(typeDoc, DOC_INDENT);
                }

                // Constructeurs d'abord, puis méthodes par ordre alphabétique.
                List<CodeEntity> members = entities.stream()
                        .filter(e -> e.kind() == EntityKind.CONSTRUCTOR || e.kind() == EntityKind.METHOD)
                        .filter(e -> type.id().equals(e.parentId()))
                        .sorted(Comparator
                                .comparing((CodeEntity e) -> e.kind() == EntityKind.CONSTRUCTOR ? 0 : 1)
                                .thenComparing(CodeEntity::name))
                        .toList();

                for (CodeEntity member : members) {
                    cursor.writeMember(member, docFor(member, generatedDocs));
                }
            }

            cursor.close();
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new GenerationException("Impossible de générer le PDF : " + e.getMessage(), e);
        }
    }

    /**
     * Javadoc existante en priorité, sinon texte généré, sinon null : dans ce
     * dernier cas rien n'est écrit dans le PDF (pas de mention parasite).
     */
    private String docFor(CodeEntity entity, Map<String, String> generatedDocs) {
        String doc = entity.existingJavadoc() != null
                ? entity.existingJavadoc()
                : generatedDocs.get(entity.id());
        if (doc == null) {
            return null;
        }
        String cleaned = cleanDoc(doc);
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Nettoie un texte de doc avant écriture : retire les balises HTML/javadoc
     * parasites (<p>, {@code ...}) et les lignes vides en trop (début, fin,
     * doublons), qui apparaîtraient sinon comme de grands blancs dans le PDF.
     */
    private static String cleanDoc(String doc) {
        String text = doc.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("(?i)</?p\\s*/?>|<br\\s*/?>", "\n")
                .replaceAll("\\{@(?:code|link|linkplain|literal)\\s+([^}]*)}", "$1")
                .replaceAll("[ \\t\\u00A0]+\\n", "\n")
                .replaceAll("\\n{2,}", "\n\n");
        return reflow(text.strip());
    }

    /** Début d'une ligne de liste ou de tag javadoc : on garde alors le retour à la ligne. */
    private static final Pattern LIST_ITEM = Pattern.compile("^([-*\\u2022]\\s|\\d+[.)]\\s|@\\w+)");

    /**
     * Recolle les lignes d'un même paragraphe. Les javadocs sont écrites avec
     * des retours à la ligne "en dur" (vers 80 colonnes) : recopiés tels quels
     * dans le PDF, ils laissent de grands blancs à droite des lignes courtes.
     * On les remplace par des espaces, sauf avant un élément de liste ou un tag ;
     * les paragraphes (ligne vide) sont conservés.
     */
    private static String reflow(String text) {
        List<String> paragraphs = new ArrayList<>();
        for (String paragraph : text.split("\\n\\n")) {
            StringBuilder joined = new StringBuilder();
            for (String raw : paragraph.split("\\n")) {
                String line = raw.strip();
                if (line.isEmpty()) {
                    continue;
                }
                if (joined.isEmpty()) {
                    joined.append(line);
                } else if (LIST_ITEM.matcher(line).find()) {
                    joined.append('\n').append(line);
                } else {
                    joined.append(' ').append(line);
                }
            }
            if (!joined.isEmpty()) {
                paragraphs.add(joined.toString());
            }
        }
        return String.join("\n\n", paragraphs);
    }

    private static String kindLabel(EntityKind kind) {
        return switch (kind) {
            case INTERFACE -> "Interface";
            case ENUM -> "Enum";
            case RECORD -> "Record";
            case MODULE -> "Fichier";
            default -> "Classe";
        };
    }

    /**
     * Gère la position d'écriture et les sauts de page automatiques.
     * PDFBox n'a pas de "flux de texte" : chaque ligne doit être positionnée
     * à la main, et une nouvelle page ouverte quand on arrive en bas.
     * Tout texte passe par wrap() : plus aucune ligne ne peut dépasser de la page.
     */
    private static class Cursor {
        private final PDDocument document;
        private final Map<Standard14Fonts.FontName, PDFont> fonts = new EnumMap<>(Standard14Fonts.FontName.class);
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument document) throws IOException {
            this.document = document;
            newPage();
        }

        private void newPage() throws IOException {
            if (stream != null) {
                stream.close();
            }
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            stream = new PDPageContentStream(document, page);
            y = PAGE_HEIGHT - MARGIN;
        }

        private void ensureSpace(float needed) throws IOException {
            if (y - needed < MARGIN) {
                newPage();
            }
        }

        private PDFont font(Standard14Fonts.FontName fontName) {
            return fonts.computeIfAbsent(fontName, PDType1Font::new);
        }

        void writeTitle(String text) throws IOException {
            writeWrapped(text, Standard14Fonts.FontName.HELVETICA_BOLD, 18, 0);
            y -= 10;
        }

        void writeTypeHeading(CodeEntity type) throws IOException {
            // De la place pour le titre + son chemin + au moins un début de contenu,
            // pour ne pas laisser un titre orphelin en bas de page.
            ensureSpace(80);
            y -= 10;
            writeWrapped(kindLabel(type.kind()) + " " + type.name(),
                    Standard14Fonts.FontName.HELVETICA_BOLD, 13, 0);
            writeWrapped(type.filePath(), Standard14Fonts.FontName.HELVETICA_OBLIQUE, 9, 0);
            y -= 2;
        }

        void writeMember(CodeEntity member, String doc) throws IOException {
            ensureSpace(45);
            writeWrapped(member.signature(), Standard14Fonts.FontName.COURIER, 10, 0);
            if (doc != null) {
                writeWrapped(doc, Standard14Fonts.FontName.HELVETICA, 10, DOC_INDENT);
            }
            y -= 6;
        }

        void writeBody(String text) throws IOException {
            writeBody(text, 0);
        }

        void writeBody(String text, float indent) throws IOException {
            writeWrapped(text, Standard14Fonts.FontName.HELVETICA, 10, indent);
            y -= 4;
        }

        /** Découpe le texte à la largeur disponible puis écrit chaque ligne (avec saut de page si besoin). */
        private void writeWrapped(String text, Standard14Fonts.FontName fontName, float size,
                                  float indent) throws IOException {
            for (String line : wrap(text, fontName, size, USABLE_WIDTH - indent)) {
                if (line.isBlank()) {
                    // Ligne vide = simple séparation de paragraphe : un petit
                    // intervalle, pas une ligne entière de blanc.
                    y -= 3;
                    continue;
                }
                ensureSpace(size + 4);
                writeLine(line, fontName, size, MARGIN + indent);
            }
        }

        private void writeLine(String text, Standard14Fonts.FontName fontName, float size,
                               float x) throws IOException {
            stream.beginText();
            stream.setFont(font(fontName), size);
            stream.newLineAtOffset(x, y);
            stream.showText(sanitize(text));
            stream.endText();
            y -= size + 4;
        }

        /**
         * Découpe un texte en lignes qui tiennent dans maxWidth. Un mot plus long
         * qu'une ligne entière (chemin de fichier, signature sans espace...) est
         * coupé caractère par caractère plutôt que de sortir de la page.
         */
        private List<String> wrap(String text, Standard14Fonts.FontName fontName, float size,
                                  float maxWidth) throws IOException {
            PDFont font = font(fontName);
            List<String> lines = new ArrayList<>();
            for (String paragraph : sanitize(text).split("\\R", -1)) {
                StringBuilder current = new StringBuilder();
                for (String word : paragraph.split(" ", -1)) {
                    String candidate = current.isEmpty() ? word : current + " " + word;
                    if (width(font, candidate, size) <= maxWidth) {
                        current = new StringBuilder(candidate);
                        continue;
                    }
                    if (!current.isEmpty()) {
                        lines.add(current.toString());
                        current = new StringBuilder();
                    }
                    String rest = word;
                    while (width(font, rest, size) > maxWidth) {
                        int cut = largestFit(font, rest, size, maxWidth);
                        lines.add(rest.substring(0, cut));
                        rest = rest.substring(cut);
                    }
                    current = new StringBuilder(rest);
                }
                lines.add(current.toString());
            }
            return lines;
        }

        private float width(PDFont font, String text, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        /** Nombre maximal de caractères (au moins 1) qui tiennent dans maxWidth. */
        private int largestFit(PDFont font, String text, float size, float maxWidth) throws IOException {
            int count = 1;
            while (count < text.length() && width(font, text.substring(0, count + 1), size) <= maxWidth) {
                count++;
            }
            return count;
        }

        /**
         * PDFBox plante sur les caractères hors encodage standard (emojis, flèches...)
         * et sur les caractères de contrôle (tabulation, retour chariot...).
         */
        private String sanitize(String text) {
            return text
                    .replace("\t", "    ")
                    .replace("→", "->")
                    .replace("←", "<-")
                    .replace("—", "-")
                    .replace("–", "-")
                    .replace("‘", "'")
                    .replace("’", "'")
                    .replace("“", "\"")
                    .replace("”", "\"")
                    .replace("…", "...")
                    .replaceAll("[^\\x20-\\x7E\\xA0-\\xFF\\r\\n]", "?");
        }

        void close() throws IOException {
            stream.close();
        }
    }
}