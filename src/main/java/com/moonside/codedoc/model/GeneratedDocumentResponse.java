package com.moonside.codedoc.model;



import java.time.Instant;

/**
 * Ce que l'API renvoie pour une ligne du tableau Angular — jamais le PDF
 * lui-même (trop lourd pour une liste), juste un lien vers l'endpoint
 * de téléchargement.
 */
public record GeneratedDocumentResponse(
        String id,
        String title,
        String language,
        String projectName,
        String downloadUrl,
        int methodsDocumented,
        int methodsFromTrivial,
        int methodsFromCache,
        int methodsFailed,
        Instant generatedAt
) {
    public static GeneratedDocumentResponse from(GeneratedDocument document) {
        return new GeneratedDocumentResponse(
                document.id(),
                document.title(),
                document.language(),
                document.projectName(),
                "/api/projects/" + document.id() + "/download",
                document.methodsDocumented(),
                document.methodsFromTrivial(),
                document.methodsFromCache(),
                document.methodsFailed(),
                document.generatedAt()
        );
    }
}