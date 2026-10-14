package com.moonside.codedoc.repository;

import com.moonside.codedoc.model.GeneratedDocument;
import org.springframework.data.mongodb.repository.MongoRepository;

/**
 * Aucune implémentation à écrire : Spring Data MongoDB fournit déjà save(),
 * findById(), findAll(), deleteById()... à partir de cette seule interface.
 */
public interface GeneratedDocumentRepository extends MongoRepository<GeneratedDocument, String> {
}