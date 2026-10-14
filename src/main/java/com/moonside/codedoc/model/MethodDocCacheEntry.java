package com.moonside.codedoc.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Cache les docs déjà générées, indexées par le hash du code de la méthode
 * (signature + corps). Si le même code est revu dans un upload ultérieur —
 * très courant quand on itère sur le même projet — on réutilise la doc déjà
 * générée au lieu de rappeler Ollama.
 */
@Document(collection = "method_doc_cache")
public record MethodDocCacheEntry(
        @Id String hash,
        String doc,
        Instant generatedAt
) {
}