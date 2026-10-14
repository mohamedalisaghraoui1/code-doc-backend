package com.moonside.codedoc.model;

import java.nio.file.Path;

/**
 * Un fichier source (tout langage connu de Language) découvert dans l'archive.
 *
 * @param absolutePath chemin réel sur le disque, utilisé par le parseur
 * @param relativePath chemin affiché à l'utilisateur, relatif à la racine du projet
 * @param sizeBytes    taille du fichier
 */
public record SourceFile(
        Path absolutePath,
        String relativePath,
        long sizeBytes
) {
}