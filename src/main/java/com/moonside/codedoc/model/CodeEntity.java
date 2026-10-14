package com.moonside.codedoc.model;

import com.moonside.codedoc.model.enums.EntityKind;

import java.util.Set;

/**
 * Un élément de code : un package, une classe, une méthode, un champ...
 *
 * @param id              identifiant unique, basé sur le nom qualifié.
 *                        Ex : "com.app.service.UserService#save(User)"
 * @param kind            le type d'élément (classe, méthode...)
 * @param name            le nom court. Ex : "save"
 * @param parentId        l'id de l'élément qui le contient (null pour la racine)
 * @param filePath        chemin relatif du fichier source
 * @param startLine       ligne de début dans le fichier
 * @param endLine         ligne de fin dans le fichier
 * @param signature       la déclaration. Ex : "public User save(User user)"
 * @param sourceCode      le code complet de l'élément (corps de la méthode inclus).
 *                        C'est ce que le LLM lira pour expliquer ce que ça fait.
 * @param existingJavadoc la javadoc déjà écrite, ou null si absente
 * @param modifiers       public, private, static, final...
 * @param annotations     @Service, @Override, @GetMapping...
 */
public record CodeEntity(
        String id,
        EntityKind kind,
        String name,
        String parentId,
        String filePath,
        int startLine,
        int endLine,
        String signature,
        String sourceCode,
        String existingJavadoc,
        Set<String> modifiers,
        Set<String> annotations
) {
}