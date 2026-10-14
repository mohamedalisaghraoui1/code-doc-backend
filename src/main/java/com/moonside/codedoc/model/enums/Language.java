package com.moonside.codedoc.model.enums;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Les langages que l'outil sait documenter, reconnus par l'extension du fichier.
 * <p>
 * Pour ajouter un langage : une ligne ici, puis (si on veut extraire ses
 * fonctions et classes) ses règles dans GenericSourceParser. Sans règles, le
 * fichier est quand même documenté globalement par le LLM (famille FILE_ONLY).
 */
public enum Language {

    JAVA("Java", Family.BRACE, "java"),
    PYTHON("Python", Family.INDENT, "py"),
    JAVASCRIPT("JavaScript", Family.BRACE, "js", "jsx", "mjs", "cjs"),
    TYPESCRIPT("TypeScript", Family.BRACE, "ts", "tsx"),
    CSHARP("C#", Family.BRACE, "cs"),
    GO("Go", Family.BRACE, "go"),
    RUST("Rust", Family.BRACE, "rs"),
    PHP("PHP", Family.BRACE, "php"),
    KOTLIN("Kotlin", Family.BRACE, "kt"),
    SWIFT("Swift", Family.BRACE, "swift"),
    SCALA("Scala", Family.BRACE, "scala"),
    C("C", Family.BRACE, "c", "h"),
    CPP("C++", Family.BRACE, "cpp", "cc", "cxx", "hpp", "hh"),
    RUBY("Ruby", Family.END_KEYWORD, "rb"),
    SQL("SQL", Family.FILE_ONLY, "sql"),
    SHELL("Shell", Family.FILE_ONLY, "sh", "bash"),
    POWERSHELL("PowerShell", Family.FILE_ONLY, "ps1"),
    HTML("HTML", Family.FILE_ONLY, "html", "htm"),
    CSS("CSS", Family.FILE_ONLY, "css", "scss"),
    VUE("Vue", Family.FILE_ONLY, "vue");

    /** La façon dont un langage délimite ses blocs : c'est elle qui décide comment trouver la fin d'une fonction. */
    public enum Family {
        /** Accolades { } : Java, JS, C#, Go, Rust... */
        BRACE,
        /** Indentation : Python. */
        INDENT,
        /** Mot-clé "end" : Ruby. */
        END_KEYWORD,
        /** Pas d'analyse fine : le fichier entier est décrit par le LLM. */
        FILE_ONLY
    }

    private static final Map<String, Language> BY_EXTENSION = new HashMap<>();

    static {
        for (Language language : values()) {
            for (String extension : language.extensions) {
                BY_EXTENSION.put(extension, language);
            }
        }
    }

    private final String displayName;
    private final Family family;
    private final String[] extensions;

    Language(String displayName, Family family, String... extensions) {
        this.displayName = displayName;
        this.family = family;
        this.extensions = extensions;
    }

    public String displayName() {
        return displayName;
    }

    public Family family() {
        return family;
    }

    /** Langage d'un fichier d'après son nom, ou null si l'extension n'est pas connue. */
    public static Language fromPath(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        return BY_EXTENSION.get(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Toutes les extensions connues, avec le point (".py", ".java"...). */
    public static Set<String> allExtensions() {
        Set<String> result = new LinkedHashSet<>();
        for (String extension : BY_EXTENSION.keySet()) {
            result.add("." + extension);
        }
        return result;
    }
}