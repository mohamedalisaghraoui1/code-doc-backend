package com.moonside.codedoc.services;

import com.moonside.codedoc.exceptions.ParsingException;
import com.moonside.codedoc.model.CodeEntity;
import com.moonside.codedoc.model.SourceFile;
import com.moonside.codedoc.model.enums.EntityKind;
import com.moonside.codedoc.model.enums.Language;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le parseur de l'application, pour tous les langages (Java, Python,
 * JavaScript, Go, C#...) : extrait des CodeEntity en pur Java, sans aucune
 * bibliothèque externe ni binaire natif à installer.
 * <p>
 * Ce n'est pas un vrai parseur de langage :
 * on repère les déclarations avec des expressions régulières propres à chaque
 * langage, puis on cherche la fin du bloc (accolades, indentation ou "end").
 * C'est volontairement tolérant : une déclaration ratée est simplement absente
 * du PDF, elle ne fait jamais échouer toute l'analyse. Pas de relations
 * (appels, héritage) : le LLM ne reçoit que le code de la fonction.
 * <p>
 * Chaque fichier devient une entité MODULE (la "page" du PDF) qui contient ses
 * classes et ses fonctions. Un fichier où rien n'a pu être extrait (SQL, HTML,
 * script...) reste un MODULE seul, décrit globalement par le LLM.
 */
@Service
public class GenericSourceParser {

    private static final long MAX_FILE_BYTES = 400_000;
    private static final int MAX_ENTITY_CHARS = 6_000;
    private static final int MODULE_PREVIEW_CHARS = 3_000;
    private static final int MAX_SIGNATURE_CHARS = 220;
    private static final int MAX_SIGNATURE_LINES = 6;
    private static final int MAX_SCAN_LINES = 40;

    private static final Set<String> KEYWORDS_NOT_NAMES = Set.of(
            "if", "for", "while", "switch", "catch", "function", "return", "else", "do", "with",
            "try", "finally", "typeof", "await", "yield", "throw", "elif", "when", "match",
            "synchronized", "using", "lock", "foreach", "sizeof", "defined"
    );

    private static final Set<String> CONSTRUCTOR_NAMES = Set.of(
            "constructor", "__init__", "__construct", "init", "initialize"
    );

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`");

    // ------------------------------------------------------------------
    // Règles de reconnaissance par langage
    // ------------------------------------------------------------------

    private enum Role { TYPE, SCOPE, FUNCTION }

    /**
     * @param keywordGroup groupe donnant class/interface/enum... (0 = aucun)
     * @param nameGroup    groupe donnant le nom
     * @param ownerGroup   groupe donnant le type propriétaire (méthodes Go), 0 = aucun
     * @param insideType   true : n'a de sens qu'à l'intérieur d'une classe/impl
     * @param ctorOnly     true : n'est gardé que si le nom est celui de la classe englobante
     *                     (constructeur sans type de retour : "Box(int w) : w_(w) {}")
     */
    private record Rule(Pattern pattern, Role role, int keywordGroup, int nameGroup,
                        int ownerGroup, boolean insideType, boolean ctorOnly) {
    }

    private static final Map<Language, List<Rule>> RULES = new EnumMap<>(Language.class);

    private static Rule type(String regex, int keywordGroup, int nameGroup) {
        return new Rule(Pattern.compile(regex), Role.TYPE, keywordGroup, nameGroup, 0, false, false);
    }

    private static Rule scope(String regex, int nameGroup) {
        return new Rule(Pattern.compile(regex), Role.SCOPE, 0, nameGroup, 0, false, false);
    }

    private static Rule function(String regex, int nameGroup) {
        return new Rule(Pattern.compile(regex), Role.FUNCTION, 0, nameGroup, 0, false, false);
    }

    private static Rule method(String regex, int nameGroup) {
        return new Rule(Pattern.compile(regex), Role.FUNCTION, 0, nameGroup, 0, true, false);
    }

    /** Constructeur sans type de retour : le nom doit être celui de la classe englobante. */
    private static Rule constructor(String regex, int nameGroup) {
        return new Rule(Pattern.compile(regex), Role.FUNCTION, 0, nameGroup, 0, true, true);
    }

    static {
        String jsMods = "(?:(?:public|private|protected|static|async|readonly|override|abstract)\\s+)*";
        List<Rule> js = List.of(
                type("^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?(class|interface|enum)\\s+(\\w+)", 1, 2),
                function("^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(\\w+)", 1),
                function("^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?(?:\\([^)]*\\)|\\w+)\\s*(?::[^=>]+)?=>", 1),
                method("^\\s*" + jsMods + "(\\w+)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?\\([^)]*\\)\\s*(?::[^=>]+)?=>", 1),
                method("^\\s*" + jsMods + "(?:get\\s+|set\\s+)?(\\w+)\\s*(?:<[^>]*>)?\\s*\\([^)]*\\)\\s*(?::\\s*[^{;=]+)?\\{", 1)
        );
        RULES.put(Language.JAVASCRIPT, js);
        RULES.put(Language.TYPESCRIPT, js);

        RULES.put(Language.JAVA, List.of(
                type("^\\s*(?:(?:public|private|protected|static|abstract|final|sealed|strictfp)\\s+)*(class|interface|enum|record|@interface)\\s+(\\w+)", 1, 2),
                method("^\\s*(?:(?:public|private|protected|static|final|abstract|synchronized|native|default|strictfp)\\s+)*(?:<[^>]+>\\s+)?(?:[\\w<>\\[\\],.?]+\\s+)?(\\w+)\\s*\\([^;{]*(?:\\{.*)?$", 1)
        ));

        RULES.put(Language.PYTHON, List.of(
                type("^\\s*class\\s+(\\w+)", 0, 1),
                function("^\\s*(?:async\\s+)?def\\s+(\\w+)\\s*\\(", 1)
        ));

        RULES.put(Language.GO, List.of(
                type("^\\s*type\\s+(\\w+)\\s+(?:\\[[^\\]]*\\]\\s*)?(struct|interface)", 2, 1),
                new Rule(Pattern.compile("^func\\s+(?:\\(\\s*\\w*\\s*\\*?\\s*(\\w+)(?:\\[[^\\]]*\\])?\\s*\\)\\s*)?(\\w+)"),
                        Role.FUNCTION, 0, 2, 1, false, false)
        ));

        String rustVis = "(?:pub(?:\\([^)]*\\))?\\s+)?";
        RULES.put(Language.RUST, List.of(
                type("^\\s*" + rustVis + "(struct|enum|trait|union)\\s+(\\w+)", 1, 2),
                scope("^\\s*impl(?:<[^>]*>)?\\s+(?:[\\w:<>,\\s&']+?\\s+for\\s+)?(\\w+)", 1),
                function("^\\s*" + rustVis + "(?:const\\s+)?(?:async\\s+)?(?:unsafe\\s+)?fn\\s+(\\w+)", 1)
        ));

        RULES.put(Language.PHP, List.of(
                type("^\\s*(?:abstract\\s+|final\\s+|readonly\\s+)*(class|interface|trait|enum)\\s+(\\w+)", 1, 2),
                function("^\\s*(?:(?:public|private|protected|static|final|abstract)\\s+)*function\\s+&?(\\w+)\\s*\\(", 1)
        ));

        RULES.put(Language.CSHARP, List.of(
                type("^\\s*(?:(?:public|private|protected|internal|static|abstract|sealed|partial|unsafe|readonly|ref)\\s+)*(class|interface|enum|struct|record)\\s+(\\w+)", 1, 2),
                method("^\\s*(?:(?:public|private|protected|internal|static|virtual|override|abstract|async|sealed|extern|unsafe|new|partial)\\s+)+(?:[\\w<>\\[\\],.?]+\\s+)?(\\w+)\\s*(?:<[^>]*>)?\\s*\\(", 1)
        ));

        RULES.put(Language.KOTLIN, List.of(
                type("^\\s*(?:(?:public|private|protected|internal|abstract|open|sealed|data|enum|annotation|inner|value)\\s+)*(class|interface|object)\\s+(\\w+)", 1, 2),
                function("^\\s*(?:(?:public|private|protected|internal|abstract|open|override|suspend|inline|operator|tailrec|infix|external)\\s+)*fun\\s+(?:<[^>]*>\\s*)?(?:[\\w.<>?,\\s]+\\.)?(\\w+)\\s*\\(", 1)
        ));

        String swiftMods = "(?:(?:public|private|fileprivate|internal|open|final|static|class|override|mutating|convenience|required)\\s+)*";
        RULES.put(Language.SWIFT, List.of(
                type("^\\s*(?:(?:public|private|fileprivate|internal|open|final)\\s+)*(class|struct|enum|protocol|extension|actor)\\s+(\\w+)", 1, 2),
                function("^\\s*" + swiftMods + "func\\s+(\\w+)", 1),
                function("^\\s*" + swiftMods + "(init)\\s*[?(]", 1)
        ));

        RULES.put(Language.SCALA, List.of(
                type("^\\s*(?:(?:private|protected|sealed|abstract|final|case|implicit)\\s+)*(class|object|trait)\\s+(\\w+)", 1, 2),
                function("^\\s*(?:(?:private|protected|override|final|implicit|lazy|inline)\\s+)*def\\s+(\\w+)", 1)
        ));

        String cNotKeyword = "(?!(?:if|for|while|switch|return|else|do|sizeof|typedef|using|namespace|case|goto|catch|delete|new|throw)\\b)";
        String cFunction = "[A-Za-z_][\\w:<>,*&\\s~]*?[\\s*&]+(~?\\w+(?:::~?\\w+)*)\\s*\\([^;{]*(?:\\{.*)?$";
        List<Rule> c = List.of(
                type("^\\s*(?:typedef\\s+)?(class|struct|enum|union)\\s+(?:class\\s+)?(\\w+)\\s*(?::[^{;]*)?\\{?\\s*$", 1, 2),
                scope("^\\s*namespace\\s+(\\w+)", 1),
                function("^" + cNotKeyword + cFunction, 1),
                method("^\\s+" + cNotKeyword + cFunction, 1),
                constructor("^\\s+(~?\\w+)\\s*\\([^;{]*\\)\\s*(?::[^;{]*)?(?:\\{.*)?$", 1)
        );
        RULES.put(Language.C, c);
        RULES.put(Language.CPP, c);

        RULES.put(Language.RUBY, List.of(
                type("^\\s*(class|module)\\s+(\\w+(?:::\\w+)*)", 1, 2),
                function("^\\s*def\\s+(?:self\\.)?(\\w+[?!=]?)", 1)
        ));
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    /** Analyse tous les fichiers donnés et renvoie leurs entités. */
    public List<CodeEntity> parse(List<SourceFile> sources) {
        List<CodeEntity> entities = new ArrayList<>();
        for (SourceFile source : sources) {
            Language language = Language.fromPath(source.relativePath());
            if (language == null) {
                continue;
            }
            try {
                parseOneFile(source, language, entities);
            } catch (RuntimeException e) {
                // Un fichier illisible ou bizarre ne doit jamais bloquer le reste du projet.
                entities.add(moduleEntity(source, language, null, 1));
            }
        }
        return entities;
    }

    // ------------------------------------------------------------------
    // Analyse d'un fichier
    // ------------------------------------------------------------------

    /** Une déclaration repérée dans le fichier, avant de devenir une CodeEntity. */
    private static final class Candidate {
        Rule rule;
        Role role;
        boolean insideType;
        boolean ctorOnly;
        String name;
        String keyword;
        String owner;
        int start;
        int sigEnd;
        int end;
        boolean hasBody;
        Candidate parent;
    }

    private void parseOneFile(SourceFile source, Language language, List<CodeEntity> entities) {
        String text = readText(source);
        List<String> lines = text == null ? List.of() : List.of(text.split("\\R", -1));

        List<Candidate> candidates = new ArrayList<>();
        List<Rule> rules = RULES.get(language);
        if (rules != null && !lines.isEmpty() && source.sizeBytes() <= MAX_FILE_BYTES) {
            candidates = findCandidates(lines, language, rules);
        }
        entities.addAll(entitiesFrom(source, language, text, lines, candidates));
    }

    /**
     * Transforme les déclarations repérées en CodeEntity : ids, parents,
     * signature, doc existante, décorateurs.
     */
    private List<CodeEntity> entitiesFrom(SourceFile source, Language language, String text,
                                          List<String> lines, List<Candidate> candidates) {
        List<CodeEntity> entities = new ArrayList<>();
        CodeEntity module = moduleEntity(source, language, text, lines.size());
        String pythonModuleDoc = language == Language.PYTHON ? pythonModuleDocstring(lines) : null;
        if (pythonModuleDoc != null) {
            module = withDoc(module, pythonModuleDoc);
        }
        entities.add(module);

        // Les types d'abord : leurs ids servent de parentId aux fonctions.
        Map<Candidate, String> ids = new HashMap<>();
        Map<String, String> typeIdByName = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (Candidate c : candidates) {
            if (c.role == Role.TYPE) {
                String id = source.relativePath() + "#" + c.name;
                if (!usedIds.add(id)) {
                    id = id + ":" + (c.start + 1);
                    usedIds.add(id);
                }
                ids.put(c, id);
                typeIdByName.putIfAbsent(c.name, id);
            }
        }

        for (Candidate c : candidates) {
            if (c.role == Role.SCOPE) {
                continue;
            }
            String parentId = resolveParentId(c, module.id(), ids, typeIdByName);
            boolean parentIsType = !parentId.equals(module.id());

            EntityKind kind;
            if (c.role == Role.TYPE) {
                kind = kindFromKeyword(c.keyword);
            } else {
                String ownerName = c.owner != null ? c.owner : (c.parent != null ? c.parent.name : null);
                boolean isConstructor = CONSTRUCTOR_NAMES.contains(c.name)
                        || (parentIsType && c.name.equals(ownerName));
                kind = isConstructor ? EntityKind.CONSTRUCTOR : EntityKind.METHOD;
            }

            String id = ids.get(c);
            if (id == null) {
                String owner = c.owner != null ? c.owner : (c.parent != null ? c.parent.name : null);
                id = source.relativePath() + "#" + (owner != null ? owner + "." : "") + c.name + ":" + (c.start + 1);
            }

            String doc = language == Language.PYTHON
                    ? pythonDocstring(lines, c)
                    : leadingComment(lines, c.start, language);

            entities.add(new CodeEntity(
                    id,
                    kind,
                    c.name,
                    parentId,
                    source.relativePath(),
                    c.start + 1,
                    c.end + 1,
                    buildSignature(lines, c, language),
                    truncate(String.join("\n", lines.subList(c.start, c.end + 1)), MAX_ENTITY_CHARS),
                    doc,
                    Set.of(),
                    decoratorsAbove(lines, c.start)
            ));
        }
        return entities;
    }

    private String resolveParentId(Candidate c, String moduleId, Map<Candidate, String> ids,
                                   Map<String, String> typeIdByName) {
        if (c.owner != null) {
            return typeIdByName.getOrDefault(c.owner, moduleId);
        }
        Candidate parent = c.parent;
        if (parent == null) {
            return moduleId;
        }
        if (parent.role == Role.TYPE) {
            return ids.getOrDefault(parent, moduleId);
        }
        // Bloc "impl Foo" (Rust) ou "namespace" (C++) : on rattache au type du même nom s'il existe.
        return typeIdByName.getOrDefault(parent.name, moduleId);
    }

    private CodeEntity moduleEntity(SourceFile source, Language language, String text, int lineCount) {
        String fileName = source.relativePath().substring(source.relativePath().lastIndexOf('/') + 1);
        String preview = text == null ? null : truncate(text, MODULE_PREVIEW_CHARS);
        return new CodeEntity(
                source.relativePath(),
                EntityKind.MODULE,
                fileName,
                null,
                source.relativePath(),
                1,
                Math.max(lineCount, 1),
                language.displayName() + " : " + source.relativePath(),
                preview,
                null,
                Set.of(),
                Set.of()
        );
    }

    private CodeEntity withDoc(CodeEntity e, String doc) {
        return new CodeEntity(e.id(), e.kind(), e.name(), e.parentId(), e.filePath(), e.startLine(),
                e.endLine(), e.signature(), e.sourceCode(), doc, e.modifiers(), e.annotations());
    }

    // ------------------------------------------------------------------
    // Repérage des déclarations
    // ------------------------------------------------------------------

    private List<Candidate> findCandidates(List<String> lines, Language language, List<Rule> rules) {
        boolean[] code = codeMask(lines, language);
        List<Candidate> found = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            if (!code[i]) {
                continue;
            }
            String line = lines.get(i);
            for (Rule rule : rules) {
                Matcher m = rule.pattern().matcher(line);
                if (!m.find()) {
                    continue;
                }
                String name = m.group(rule.nameGroup());
                if (name == null || KEYWORDS_NOT_NAMES.contains(name)) {
                    break;
                }
                Candidate c = new Candidate();
                c.rule = rule;
                c.role = rule.role();
                c.insideType = rule.insideType();
                c.ctorOnly = rule.ctorOnly();
                c.name = name;
                c.keyword = rule.keywordGroup() > 0 ? m.group(rule.keywordGroup()) : null;
                c.owner = rule.ownerGroup() > 0 ? m.group(rule.ownerGroup()) : null;
                c.start = i;
                computeBlock(lines, code, language, c);
                found.add(c);
                break; // une seule règle par ligne
            }
        }
        return keepRelevant(found);
    }

    /**
     * Rattache chaque déclaration à son conteneur le plus proche et écarte
     * celles qui n'ont pas de sens : méthode hors de toute classe, fonction
     * imbriquée dans une autre fonction...
     */
    private List<Candidate> keepRelevant(List<Candidate> found) {
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : found) {
            Candidate innermost = null;
            for (Candidate other : found) {
                if (other == c || other.start >= c.start || other.end < c.start) {
                    continue;
                }
                if (innermost == null || other.start > innermost.start) {
                    innermost = other;
                }
            }
            if (innermost != null && innermost.role == Role.FUNCTION) {
                continue; // fonction ou classe locale à l'intérieur d'une fonction
            }
            c.parent = innermost;
            if (c.ctorOnly && (innermost == null || innermost.role != Role.TYPE || !c.name.equals(innermost.name))) {
                continue; // "Foo(...)" dans une classe qui ne s'appelle pas Foo : un appel, pas un constructeur
            }
            if (c.insideType && innermost == null) {
                continue; // "méthode" sans classe autour : en fait un simple appel
            }
            kept.add(c);
        }
        kept.sort(Comparator.comparingInt(c -> c.start));
        return kept;
    }

    /** Marque les lignes qui sont du vrai code (pas un commentaire ni l'intérieur d'une docstring). */
    private boolean[] codeMask(List<String> lines, Language language) {
        boolean[] code = new boolean[lines.size()];
        boolean inBlockComment = false;
        String tripleQuote = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.strip();

            if (language == Language.PYTHON) {
                if (tripleQuote != null) {
                    if (line.contains(tripleQuote)) {
                        tripleQuote = null;
                    }
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    continue;
                }
                for (String quote : new String[]{"\"\"\"", "'''"}) {
                    int occurrences = (line.length() - line.replace(quote, "").length()) / quote.length();
                    if (occurrences % 2 == 1) {
                        tripleQuote = quote;
                    }
                }
                code[i] = true;
            } else if (language.family() == Language.Family.END_KEYWORD) {
                code[i] = !trimmed.startsWith("#");
            } else {
                if (inBlockComment) {
                    if (line.contains("*/")) {
                        inBlockComment = false;
                    }
                    continue;
                }
                if (trimmed.startsWith("//") || (language == Language.PHP && trimmed.startsWith("#"))) {
                    continue;
                }
                if (trimmed.startsWith("/*")) {
                    if (!trimmed.contains("*/")) {
                        inBlockComment = true;
                    }
                    continue;
                }
                code[i] = true;
            }
        }
        return code;
    }

    // ------------------------------------------------------------------
    // Fin de bloc
    // ------------------------------------------------------------------

    private void computeBlock(List<String> lines, boolean[] code, Language language, Candidate c) {
        switch (language.family()) {
            case INDENT -> computeIndentBlock(lines, c);
            case END_KEYWORD -> computeEndKeywordBlock(lines, c);
            default -> computeBraceBlock(lines, code, c);
        }
    }

    private void computeBraceBlock(List<String> lines, boolean[] code, Candidate c) {
        int braceDepth = 0;
        int parenDepth = 0;
        boolean opened = false;
        c.sigEnd = c.start;
        c.end = c.start;
        c.hasBody = false;
        boolean expressionBody = false;

        for (int i = c.start; i < lines.size(); i++) {
            if (!opened && i > c.start + MAX_SCAN_LINES) {
                return; // pas de corps trouvé (fonction à expression, déclaration exotique...)
            }
            if (!code[i] && i != c.start) {
                continue;
            }
            String cleaned = STRING_LITERAL.matcher(lines.get(i)).replaceAll("\"\"");
            int comment = cleaned.indexOf("//");
            if (comment >= 0) {
                cleaned = cleaned.substring(0, comment);
            }
            for (int k = 0; k < cleaned.length(); k++) {
                char ch = cleaned.charAt(k);
                switch (ch) {
                    case '=' -> {
                        // "fun f() = x" ou "(a) => a + 1" : fonction à expression, sans accolades.
                        char before = k > 0 ? cleaned.charAt(k - 1) : ' ';
                        char after = k + 1 < cleaned.length() ? cleaned.charAt(k + 1) : ' ';
                        if (!opened && parenDepth <= 0 && after != '=' && "=!<>".indexOf(before) < 0) {
                            expressionBody = true;
                        }
                    }
                    case '(' -> parenDepth++;
                    case ')' -> parenDepth--;
                    case '{' -> {
                        if (parenDepth <= 0) {
                            if (!opened) {
                                c.sigEnd = i;
                            }
                            opened = true;
                            braceDepth++;
                        }
                    }
                    case '}' -> {
                        if (opened && parenDepth <= 0) {
                            braceDepth--;
                            if (braceDepth == 0) {
                                c.end = i;
                                c.hasBody = true;
                                return;
                            }
                        }
                    }
                    case ';' -> {
                        if (!opened && parenDepth <= 0) {
                            c.sigEnd = i;
                            c.end = i; // déclaration sans corps (prototype, interface...) ou fonction à expression
                            return;
                        }
                    }
                    default -> {
                    }
                }
            }
            if (expressionBody && !opened && parenDepth <= 0) {
                c.sigEnd = i;
                c.end = i; // l'expression tient sur cette ligne (on ne suit pas les expressions multi-lignes)
                return;
            }
        }
    }

    private void computeIndentBlock(List<String> lines, Candidate c) {
        int baseIndent = indentOf(lines.get(c.start));
        int parenDepth = 0;
        int sigEnd = c.start;
        for (int i = c.start; i < lines.size() && i < c.start + 15; i++) {
            String line = lines.get(i);
            int hash = line.indexOf('#');
            String stripped = (hash >= 0 ? line.substring(0, hash) : line).stripTrailing();
            for (char ch : stripped.toCharArray()) {
                if (ch == '(' || ch == '[' || ch == '{') {
                    parenDepth++;
                } else if (ch == ')' || ch == ']' || ch == '}') {
                    parenDepth--;
                }
            }
            sigEnd = i;
            if (parenDepth <= 0 && stripped.endsWith(":")) {
                break;
            }
        }
        c.sigEnd = sigEnd;
        int end = sigEnd;
        for (int j = sigEnd + 1; j < lines.size(); j++) {
            String line = lines.get(j);
            if (line.isBlank()) {
                continue;
            }
            if (indentOf(line) > baseIndent) {
                end = j;
            } else {
                break;
            }
        }
        c.end = end;
        c.hasBody = end > sigEnd;
    }

    private void computeEndKeywordBlock(List<String> lines, Candidate c) {
        c.sigEnd = c.start;
        c.end = c.start;
        String first = lines.get(c.start).strip();
        if (first.matches(".*\\bend\\s*$") && !first.equals("end")) {
            return; // def x; end sur une seule ligne
        }
        int baseIndent = indentOf(lines.get(c.start));
        for (int j = c.start + 1; j < lines.size(); j++) {
            String line = lines.get(j);
            if (!line.isBlank() && indentOf(line) == baseIndent && line.strip().matches("end\\b.*")) {
                c.end = j;
                c.hasBody = true;
                return;
            }
        }
    }

    private int indentOf(String line) {
        int n = 0;
        while (n < line.length() && (line.charAt(n) == ' ' || line.charAt(n) == '\t')) {
            n += line.charAt(n) == '\t' ? 4 : 1;
        }
        return n;
    }

    // ------------------------------------------------------------------
    // Signature, décorateurs, commentaires
    // ------------------------------------------------------------------

    private String buildSignature(List<String> lines, Candidate c, Language language) {
        int last = Math.min(Math.max(c.sigEnd, c.start), c.start + MAX_SIGNATURE_LINES - 1);
        StringBuilder sb = new StringBuilder();
        for (int i = c.start; i <= last && i < lines.size(); i++) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append(lines.get(i).strip());
        }
        String text = sb.toString().replaceAll("\\s+", " ");

        if (language.family() == Language.Family.INDENT) {
            text = text.replaceAll(":\\s*$", "");
        } else {
            text = cutAtBodyStart(text);
        }
        return truncate(text.strip(), MAX_SIGNATURE_CHARS);
    }

    /** Coupe au début du corps : la première "{" ou ";" hors parenthèses. */
    private String cutAtBodyStart(String text) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '(') {
                depth++;
            } else if (ch == ')') {
                depth--;
            } else if (depth <= 0 && (ch == '{' || ch == ';')) {
                return text.substring(0, i).strip();
            }
        }
        return text;
    }

    private Set<String> decoratorsAbove(List<String> lines, int start) {
        Set<String> result = new LinkedHashSet<>();
        for (int i = start - 1; i >= 0; i--) {
            String line = lines.get(i).strip();
            if (!line.startsWith("@")) {
                break;
            }
            String name = line.substring(1);
            int paren = name.indexOf('(');
            result.add((paren >= 0 ? name.substring(0, paren) : name).strip());
        }
        return result;
    }

    /**
     * Commentaire de documentation juste au-dessus de la déclaration (en
     * sautant les décorateurs / attributs) : bloc /** ... *&#47;, lignes ///,
     * lignes // (Go) ou # (Ruby).
     */
    private String leadingComment(List<String> lines, int start, Language language) {
        int i = start - 1;
        while (i >= 0) {
            String t = lines.get(i).strip();
            if (t.startsWith("@") || t.startsWith("#[") || (t.startsWith("[") && t.endsWith("]"))) {
                i--;
            } else {
                break;
            }
        }
        if (i < 0) {
            return null;
        }
        String last = lines.get(i).strip();

        if (last.endsWith("*/")) {
            int open = i;
            while (open >= 0 && !lines.get(open).contains("/*")) {
                open--;
            }
            if (open < 0) {
                return null;
            }
            List<String> block = new ArrayList<>();
            for (int k = open; k <= i; k++) {
                String l = lines.get(k).strip()
                        .replaceFirst("^/\\*+", "")
                        .replaceFirst("\\*+/$", "")
                        .replaceFirst("^\\*\\s?", "")
                        .strip();
                block.add(l);
            }
            return cleanComment(block);
        }

        String prefix = null;
        if (last.startsWith("///")) {
            prefix = "///";
        } else if (language == Language.GO && last.startsWith("//")) {
            prefix = "//";
        } else if ((language == Language.RUBY) && last.startsWith("#")) {
            prefix = "#";
        }
        if (prefix == null) {
            return null;
        }
        List<String> block = new ArrayList<>();
        while (i >= 0 && lines.get(i).strip().startsWith(prefix)) {
            block.addFirst(lines.get(i).strip().substring(prefix.length()).strip());
            i--;
        }
        return cleanComment(block);
    }

    private String cleanComment(List<String> block) {
        // Retire les balises XML des commentaires C# (<summary>, <param name="x">...).
        String joined = String.join("\n", block).replaceAll("</?[A-Za-z][^>]*>", "").replaceAll("\n{3,}", "\n\n").strip();
        return joined.isEmpty() ? null : joined;
    }

    /** Docstring Python : première instruction du corps si c'est une chaîne entre triples guillemets. */
    private String pythonDocstring(List<String> lines, Candidate c) {
        if (!c.hasBody) {
            return null;
        }
        return docstringFrom(lines, c.sigEnd + 1, c.end);
    }

    private String pythonModuleDocstring(List<String> lines) {
        int i = 0;
        while (i < lines.size() && (lines.get(i).isBlank() || lines.get(i).strip().startsWith("#"))) {
            i++;
        }
        return docstringFrom(lines, i, lines.size() - 1);
    }

    private String docstringFrom(List<String> lines, int from, int to) {
        int i = from;
        while (i <= to && i < lines.size() && lines.get(i).isBlank()) {
            i++;
        }
        if (i > to || i >= lines.size()) {
            return null;
        }
        String first = lines.get(i).strip().replaceFirst("^[rRuUbB]{0,2}", "");
        String quote = first.startsWith("\"\"\"") ? "\"\"\"" : first.startsWith("'''") ? "'''" : null;
        if (quote == null) {
            return null;
        }
        List<String> block = new ArrayList<>();
        String rest = first.substring(3);
        int closing = rest.indexOf(quote);
        if (closing >= 0) {
            block.add(rest.substring(0, closing).strip());
            return cleanComment(block);
        }
        block.add(rest.strip());
        for (int k = i + 1; k <= to && k < lines.size(); k++) {
            String l = lines.get(k);
            int end = l.indexOf(quote);
            if (end >= 0) {
                block.add(l.substring(0, end).strip());
                break;
            }
            block.add(l.strip());
        }
        return cleanComment(block);
    }

    // ------------------------------------------------------------------
    // Utilitaires
    // ------------------------------------------------------------------

    private static EntityKind kindFromKeyword(String keyword) {
        if (keyword == null) {
            return EntityKind.CLASS;
        }
        return switch (keyword) {
            case "interface", "trait", "protocol" -> EntityKind.INTERFACE;
            case "enum" -> EntityKind.ENUM;
            case "struct", "record", "union" -> EntityKind.RECORD;
            default -> EntityKind.CLASS;
        };
    }

    private String readText(SourceFile source) {
        if (source.sizeBytes() > MAX_FILE_BYTES) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(source.absolutePath());
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                text = new String(bytes, StandardCharsets.ISO_8859_1);
            }
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        } catch (IOException e) {
            throw new ParsingException("Impossible de lire " + source.relativePath(), e);
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "\n… (tronqué)";
    }
}