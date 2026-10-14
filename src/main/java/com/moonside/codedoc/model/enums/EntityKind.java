package com.moonside.codedoc.model.enums;

/** Les types d'éléments de code que l'outil sait reconnaître. */
public enum EntityKind {
    PACKAGE,
    /** Un fichier source : contient ses classes et ses fonctions de premier niveau. */
    MODULE,
    CLASS,
    INTERFACE,
    ENUM,
    RECORD,
    METHOD,
    CONSTRUCTOR,
    FIELD
}