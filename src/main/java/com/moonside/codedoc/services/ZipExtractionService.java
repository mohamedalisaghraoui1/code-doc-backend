package com.moonside.codedoc.services;

import com.moonside.codedoc.exceptions.IngestionException;
import com.moonside.codedoc.model.enums.Language;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Décompresse une archive zip non fiable dans un répertoire temporaire isolé.
 * <p>
 * Entrée : le zip brut envoyé par l'utilisateur.
 * Sortie : le chemin d'un dossier temporaire contenant uniquement les fichiers utiles.
 */
@Service
public class ZipExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ZipExtractionService.class);

    /** Taille totale décompressée maximale (protection zip bomb). */
    private static final long MAX_TOTAL_BYTES = 200L * 1024 * 1024;

    /** Taille maximale d'un fichier unique. */
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    /** Nombre maximal d'entrées lues, ignorées comprises. */
    private static final int MAX_ENTRIES = 20_000;

    /** Longueur maximale du chemin d'une entrée. */
    private static final int MAX_PATH_LENGTH = 300;

    /** Fichiers de configuration conservés en plus du code source. */
    private static final Set<String> CONFIG_EXTENSIONS = Set.of(
            ".xml", ".yml", ".yaml", ".properties", ".md", ".gradle", ".kts"
    );

    /** Code source (tous les langages connus de Language) + fichiers de configuration. */
    private static final Set<String> KEPT_EXTENSIONS = Stream.concat(
            Language.allExtensions().stream(), CONFIG_EXTENSIONS.stream()
    ).collect(Collectors.toUnmodifiableSet());

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "target", "build", "out", "bin", ".git", ".idea", ".gradle",
            "node_modules", ".mvn", "dist", "__macosx",
            "venv", ".venv", "__pycache__", "site-packages", "vendor", "obj", "coverage", ".next", ".nuxt", "pods"
    );

    public Path extract(MultipartFile archive) {
        validateUpload(archive);

        Path root = createWorkDirectory();
        long totalBytes = 0;
        int entryCount = 0;
        int keptFiles = 0;

        try (ZipInputStream zis = new ZipInputStream(archive.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {

                // On compte TOUTES les entrées, y compris celles qu'on ignore :
                // une archive de millions d'entrées vides est aussi une attaque.
                if (++entryCount > MAX_ENTRIES) {
                    throw new IngestionException("L'archive contient trop d'entrées.");
                }

                String name = normalize(entry.getName());

                if (entry.isDirectory() || shouldSkip(name)) {
                    continue;
                }

                Path target = resolveSafely(root, name);
                Files.createDirectories(target.getParent());

                // Budget restant : le fichier ne peut pas dépasser ni sa propre limite,
                // ni ce qu'il reste du quota global.
                long budget = Math.min(MAX_FILE_BYTES, MAX_TOTAL_BYTES - totalBytes);
                totalBytes += copyWithLimit(zis, target, budget);
                keptFiles++;
            }
        } catch (IOException e) {
            cleanup(root);
            throw new IngestionException("Archive illisible ou corrompue.", e);
        } catch (RuntimeException e) {
            cleanup(root);
            throw e;
        }

        if (keptFiles == 0) {
            cleanup(root);
            throw new IngestionException("Aucun fichier source exploitable dans l'archive.");
        }

        log.info("Archive extraite : {} fichiers, {} Ko", keptFiles, totalBytes / 1024);
        return root;
    }

    private void validateUpload(MultipartFile archive) {
        if (archive == null || archive.isEmpty()) {
            throw new IngestionException("L'archive est vide.");
        }
        String filename = archive.getOriginalFilename();
        if (filename == null || !filename.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            throw new IngestionException("Seuls les fichiers .zip sont acceptés.");
        }
    }

    private Path createWorkDirectory() {
        try {
            return Files.createTempDirectory("docgen-");
        } catch (IOException e) {
            throw new IngestionException("Impossible de créer le répertoire de travail.", e);
        }
    }

    /** Uniformise les séparateurs : certains zips Windows utilisent des antislashs. */
    private String normalize(String entryName) {
        return entryName.replace('\\', '/');
    }

    private boolean shouldSkip(String name) {
        if (name.length() > MAX_PATH_LENGTH) {
            return true;
        }
        for (String segment : name.split("/")) {
            if (SKIPPED_DIRECTORIES.contains(segment.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        return KEPT_EXTENSIONS.stream().noneMatch(lower::endsWith);
    }

    /**
     * Protection Zip Slip : le chemin final doit rester DANS la racine.
     * On normalise d'abord (résout les ".."), puis on vérifie le préfixe.
     */
    private Path resolveSafely(Path root, String name) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path target = normalizedRoot.resolve(name).normalize();
        if (!target.startsWith(normalizedRoot)) {
            throw new IngestionException("Chemin d'archive invalide : " + name);
        }
        return target;
    }

    /**
     * Copie en comptant les octets réellement lus : la taille annoncée
     * Dans l'en-tête du zip n'est pas fiable.
     */
    private long copyWithLimit(InputStream in, Path target, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > limit) {
                    throw new IngestionException("Taille maximale dépassée pendant l'extraction.");
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    /** Supprime récursivement le répertoire temporaire. */
    public void cleanup(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    log.warn("Suppression impossible : {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Nettoyage incomplet de {}", root, e);
        }
    }
}