package com.moonside.codedoc.controller;

import com.moonside.codedoc.exceptions.GenerationException;
import com.moonside.codedoc.model.*;
import com.moonside.codedoc.model.enums.EntityKind;
import com.moonside.codedoc.model.enums.Language;
import com.moonside.codedoc.repository.GeneratedDocumentRepository;
import com.moonside.codedoc.repository.MethodDocCacheRepository;
import com.moonside.codedoc.services.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Point d'entrée unique : upload du zip → extraction → parsing (tous langages)
 * → génération de la doc de chaque méthode et classe via Ollama → PDF → sauvegarde en MongoDB
 * (métadonnées + PDF dans le même document) → réponse pour le tableau Angular.
 *
 * Ce endpoint est SYNCHRONE : la requête reste ouverte jusqu'à ce que tout
 * soit fait. Adapté à un petit projet de démo ; pour un gros projet, la
 * version asynchrone avec jobId + SSE serait la bonne base à reprendre.
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectUploadController {

    private static final Logger log = LoggerFactory.getLogger(ProjectUploadController.class);

    private static final Set<EntityKind> TYPE_KINDS =
            Set.of(EntityKind.CLASS, EntityKind.INTERFACE, EntityKind.ENUM, EntityKind.RECORD);

    private final ZipExtractionService extractionService;
    private final SourceScannerService scanner;
    private final GenericSourceParser parser;
    private final DocumentationGenerationService generationService;
    private final PdfGenerationService pdfGenerationService;
    private final GeneratedDocumentRepository documentRepository;
    private final DocHasher docHasher;
    private final MethodDocCacheRepository docCacheRepository;

    public ProjectUploadController(ZipExtractionService extractionService,
                                   SourceScannerService scanner,
                                   GenericSourceParser parser,
                                   DocumentationGenerationService generationService,
                                   PdfGenerationService pdfGenerationService,
                                   GeneratedDocumentRepository documentRepository,
                                   DocHasher docHasher,
                                   MethodDocCacheRepository docCacheRepository) {
        this.extractionService = extractionService;
        this.scanner = scanner;
        this.parser = parser;
        this.generationService = generationService;
        this.pdfGenerationService = pdfGenerationService;
        this.documentRepository = documentRepository;
        this.docHasher = docHasher;
        this.docCacheRepository = docCacheRepository;
    }

    @PostMapping("/upload")
    public GeneratedDocumentResponse upload(@RequestParam("file") MultipartFile file) {
        Path root = extractionService.extract(file);
        try {
            List<SourceFile> sources = scanner.scan(root);

            // Un seul chemin pour tous les langages (Java compris) : détection des
            // classes et fonctions par règles, voir GenericSourceParser.
            List<CodeEntity> entities = parser.parse(sources);

            Map<String, List<CodeEntity>> membersByParent = entities.stream()
                    .filter(e -> e.parentId() != null)
                    .collect(Collectors.groupingBy(CodeEntity::parentId));

            Map<String, String> generatedDocs = new LinkedHashMap<>();
            Counters counters = new Counters();

            // 1) Fonctions, méthodes et constructeurs : on documente tout ce qui
            // n'a pas déjà de doc écrite à la main, quelle que soit sa visibilité.
            // Seule la doc existante évite l'appel au LLM ; sinon le cache
            // (même code déjà vu = même hash) évite de refaire l'appel.
            for (CodeEntity method : entities) {
                if ((method.kind() == EntityKind.METHOD || method.kind() == EntityKind.CONSTRUCTOR)
                        && method.existingJavadoc() == null) {
                    document(method, () -> generationService.generateMethodDoc(method), generatedDocs, counters);
                }
            }
            int methodDocCount = generatedDocs.size();

            // 2) Classes, interfaces, enums, records (exceptions comprises) sans doc.
            for (CodeEntity type : entities) {
                if (TYPE_KINDS.contains(type.kind()) && type.existingJavadoc() == null) {
                    List<CodeEntity> members = membersByParent.getOrDefault(type.id(), List.of());
                    document(type, () -> generationService.generateTypeDoc(type, members), generatedDocs, counters);
                }
            }

            // 3) Fichiers sans aucune fonction ni classe extraite (SQL, HTML, script...) :
            // le LLM décrit le fichier dans son ensemble.
            for (CodeEntity module : entities) {
                if (module.kind() == EntityKind.MODULE
                        && module.existingJavadoc() == null
                        && module.sourceCode() != null
                        && !membersByParent.containsKey(module.id())) {
                    document(module, () -> generationService.generateTypeDoc(module, List.of()), generatedDocs, counters);
                }
            }

            log.info("Génération terminée : {} depuis le cache, {} via Ollama, {} échecs",
                    counters.fromCache, counters.fromLlm, counters.failed);

            String projectTitle = stripZipExtension(file.getOriginalFilename());
            byte[] pdfBytes = pdfGenerationService.generate(projectTitle, entities, generatedDocs);

            // id = null : MongoDB génère l'identifiant automatiquement à la
            // sauvegarde, et save() renvoie une nouvelle instance qui le contient.
            // methodsFromTrivial est conservé pour ne pas changer le modèle (et le
            // tableau Angular) : il vaut toujours ZÉRO depuis la suppression des
            // raccourcis propres à Java (getters/setters).
            GeneratedDocument document = new GeneratedDocument(
                    null,
                    projectTitle,
                    detectedLanguages(sources),
                    projectTitle,
                    pdfBytes,
                    methodDocCount,
                    0,
                    counters.fromCache,
                    counters.failed,
                    Instant.now()
            );
            GeneratedDocument saved = documentRepository.save(document);

            return GeneratedDocumentResponse.from(saved);
        } finally {
            extractionService.cleanup(root);
        }
    }

    /** Compteurs de la génération (cache, LLM, échecs). */
    private static final class Counters {
        int fromCache;
        int fromLlm;
        int failed;
    }

    /**
     * Documente un élément : réutilise le cache si le même code a déjà été vu,
     * sinon appelle le LLM puis mémorise le résultat. En cas d'échec le PDF
     * n'affiche simplement rien pour cet élément (pas de texte parasite).
     */
    private void document(CodeEntity entity, Supplier<String> generator,
                          Map<String, String> generatedDocs, Counters counters) {
        String hash = docHasher.hash(entity.signature(), entity.sourceCode());
        var cached = docCacheRepository.findById(hash);
        if (cached.isPresent()) {
            generatedDocs.put(entity.id(), cached.get().doc());
            counters.fromCache++;
            return;
        }
        try {
            String doc = generator.get();
            generatedDocs.put(entity.id(), doc);
            docCacheRepository.save(new MethodDocCacheEntry(hash, doc, Instant.now()));
            counters.fromLlm++;
        } catch (Exception e) {
            counters.failed++;
        }
    }

    /** "Java", "Python, Go"... : les langages réellement trouvés dans l'archive. */
    private String detectedLanguages(List<SourceFile> sources) {
        String languages = sources.stream()
                .map(src -> Language.fromPath(src.relativePath()))
                .filter(java.util.Objects::nonNull)
                .map(Language::displayName)
                .distinct()
                .collect(Collectors.joining(", "));
        return languages.isEmpty() ? "Inconnu" : languages;
    }

    /** Alimente le tableau Angular : jamais le PDF lui-même, juste les métadonnées + un lien. */
    @GetMapping
    public List<GeneratedDocumentResponse> listDocuments() {
        return documentRepository.findAll().stream()
                .map(GeneratedDocumentResponse::from)
                .toList();
    }

    /** Sert le PDF depuis MongoDB — c'est ici, et seulement ici, que les octets sortent de la base. */
    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable String id) {
        GeneratedDocument document = documentRepository.findById(id)
                .orElseThrow(() -> new GenerationException("Document introuvable : " + id));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + document.title() + ".pdf\"")
                .body(document.pdfContent());
    }

    private String stripZipExtension(String filename) {
        if (filename == null) {
            return "projet";
        }
        return filename.toLowerCase().endsWith(".zip")
                ? filename.substring(0, filename.length() - 4)
                : filename;
    }
}