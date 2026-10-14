package com.moonside.codedoc.services;

import com.moonside.codedoc.model.CodeEntity;
import com.moonside.codedoc.model.enums.EntityKind;
import com.moonside.codedoc.model.enums.Language;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Transforme une CodeEntity (le fait) en texte (le prompt) à envoyer au LLM.
 * C'est la seule classe qui "parle" au LLM en langage naturel — le reste du
 * pipeline ne manipule que des faits structurés. Elle ne dépend d'aucun
 * langage : le nom du langage est simplement déduit de l'extension du fichier.
 */
@Component
public class PromptBuilder {

    /** Au-delà de cette taille, on n'envoie pas le code complet d'un type (limite de contexte du modèle). */
    private static final int MAX_TYPE_SOURCE_CHARS = 2500;

    /** Prompt pour une méthode, une fonction ou un constructeur. */
    public String buildMethodPrompt(CodeEntity method) {
        String what = method.kind() == EntityKind.CONSTRUCTOR ? "le constructeur" : "la fonction ou la méthode";

        StringBuilder prompt = new StringBuilder();
        prompt.append("Tu es un générateur de documentation technique pour un projet ")
                .append(languageLabel(method)).append(".\n")
                .append("Explique ce que fait ").append(what).append(" suivant(e), en 2 à 4 phrases claires, ")
                .append("en français, sans reformuler le code ligne par ligne. ")
                .append("Ne réponds qu'avec l'explication, sans préambule.\n\n");

        prompt.append("Contexte : ").append(method.parentId()).append("\n");
        prompt.append("Signature : ").append(method.signature()).append("\n");
        prompt.append("\nCode :\n").append(method.sourceCode());

        return prompt.toString();
    }

    /**
     * Prompt pour une classe, une interface, un enum, un record ou un fichier source.
     *
     * @param type    l'élément à documenter
     * @param members ses méthodes et constructeurs (vide pour un fichier sans fonction)
     */
    public String buildTypePrompt(CodeEntity type, List<CodeEntity> members) {
        String kind = switch (type.kind()) {
            case INTERFACE -> "interface";
            case ENUM -> "enum";
            case RECORD -> "record ou structure";
            case MODULE -> "fichier source";
            default -> "classe";
        };

        StringBuilder prompt = new StringBuilder();
        prompt.append("Tu es un générateur de documentation technique pour un projet ")
                .append(languageLabel(type)).append(".\n")
                .append("Explique le rôle de l'élément suivant (").append(kind).append("), en 2 à 3 phrases claires, ")
                .append("en français : à quoi il sert dans l'application. ")
                .append("Ne détaille pas chaque méthode et ne recopie pas le code. ")
                .append("Ne réponds qu'avec l'explication, sans préambule.\n\n");

        prompt.append("Identifiant : ").append(type.id()).append("\n");
        prompt.append("Signature : ").append(type.signature()).append("\n");

        if (!type.annotations().isEmpty()) {
            prompt.append("Annotations : ").append(String.join(", ", type.annotations())).append("\n");
        }

        if (!members.isEmpty()) {
            prompt.append("Membres :\n");
            for (CodeEntity member : members) {
                prompt.append("- ").append(member.signature()).append("\n");
            }
        }

        if (type.sourceCode() != null && type.sourceCode().length() <= MAX_TYPE_SOURCE_CHARS) {
            prompt.append("\nCode :\n").append(type.sourceCode());
        }

        return prompt.toString();
    }

    /** Le nom du langage du fichier ("Python", "Java", "Go"...), d'après son extension. */
    private String languageLabel(CodeEntity entity) {
        Language language = Language.fromPath(entity.filePath());
        return language == null ? "logiciel" : language.displayName();
    }
}