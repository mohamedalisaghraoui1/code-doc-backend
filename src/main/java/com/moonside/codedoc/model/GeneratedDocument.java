package com.moonside.codedoc.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Un document généré, persisté dans MongoDB. Le PDF est stocké directement
 * dans le champ pdfContent (binaire) : un seul document Mongo = une ligne
 * du tableau Angular + le fichier lui-même.
 *
 * ATTENTION : MongoDB limite un document à 16 Mo. Largement suffisant pour
 * un PDF de documentation ; si ça devait un jour être dépassé, GridFS (fait
 * pour les gros fichiers binaires dans MongoDB) serait la solution.
 *
 * @param id                 généré automatiquement par MongoDB (laisser null à la création)
 * @param pdfContent         le PDF en binaire
 * @param methodsDocumented  nombre total de méthodes documentées (trivial + cache + LLM)
 * @param methodsFromTrivial parmi elles, combien reconnues comme triviales (0 appel LLM)
 * @param methodsFromCache   parmi elles, combien réutilisées depuis le cache (0 appel LLM)
 * @param methodsFailed      nombre de méthodes où la génération a échoué
 */
@Document(collection = "generated_documents")
public record GeneratedDocument(
        @Id String id,
        String title,
        String language,
        String projectName,
        byte[] pdfContent,
        int methodsDocumented,
        int methodsFromTrivial,
        int methodsFromCache,
        int methodsFailed,
        Instant generatedAt
) {
}