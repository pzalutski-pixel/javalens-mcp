package org.javalens.core.project;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a build script into tokens, dropping comments, so a static read of the script sees
 * only code. Three dialects:
 *
 * <ul>
 *   <li>Groovy and Kotlin (Gradle scripts): comments are a double slash to the end of the
 *       line, and block comments from slash-star to star-slash. Kotlin block comments nest;
 *       a Groovy block comment ends at the first star-slash. Strings are {@code '...'},
 *       {@code "..."}, {@code '''...'''} and {@code """..."""}; a backslash escapes the
 *       next character except in a Kotlin {@code """} string, which is raw. Groovy's slashy
 *       strings are not recognized: their slashes read as symbols.</li>
 *   <li>Starlark (Bazel {@code BUILD} files): comments are {@code #} to the end of the line.
 *       Strings are single, double and triple quoted, optionally prefixed by {@code r},
 *       {@code b}, {@code rb} or {@code br}; a backslash escapes the next character, also in
 *       a raw ({@code r}) string, where it stays in the content but still keeps a quote from
 *       ending the string. Double slash is floor division, not a comment.</li>
 * </ul>
 *
 * <p>A string token's text is its content as written, without the quotes and prefix. An
 * unterminated comment or string runs to the end of the script.
 */
final class BuildLexer {

    enum Kind { IDENTIFIER, STRING, SYMBOL }

    private enum Dialect { GROOVY, KOTLIN, STARLARK }

    /**
     * One token: an identifier's name, a string's content, or a single symbol character.
     */
    record Token(Kind kind, String text) {

        boolean isSymbol(char symbol) {
            return kind == Kind.SYMBOL && text.charAt(0) == symbol;
        }

        boolean isIdentifier(String name) {
            return kind == Kind.IDENTIFIER && text.equals(name);
        }
    }

    private final String source;
    private final Dialect dialect;
    private int pos;

    private BuildLexer(String source, Dialect dialect) {
        this.source = source;
        this.dialect = dialect;
    }

    /** The tokens of a Groovy script ({@code settings.gradle}, {@code build.gradle}). */
    static List<Token> groovy(String source) {
        return new BuildLexer(source, Dialect.GROOVY).tokens();
    }

    /** The tokens of a Kotlin script ({@code settings.gradle.kts}, {@code build.gradle.kts}). */
    static List<Token> kotlin(String source) {
        return new BuildLexer(source, Dialect.KOTLIN).tokens();
    }

    /** The tokens of a Starlark file ({@code BUILD}, {@code BUILD.bazel}, {@code .bzl}). */
    static List<Token> starlark(String source) {
        return new BuildLexer(source, Dialect.STARLARK).tokens();
    }

    private List<Token> tokens() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (dialect == Dialect.STARLARK && c == '#') {
                skipLineComment();
            } else if (dialect != Dialect.STARLARK && source.startsWith("//", pos)) {
                skipLineComment();
            } else if (dialect != Dialect.STARLARK && source.startsWith("/*", pos)) {
                skipBlockComment();
            } else if (c == '\'' || c == '"') {
                tokens.add(new Token(Kind.STRING, string(c)));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = pos;
                while (pos < source.length() && Character.isJavaIdentifierPart(source.charAt(pos))) {
                    pos++;
                }
                String word = source.substring(start, pos);
                if (dialect == Dialect.STARLARK && isStringPrefix(word) && pos < source.length()
                        && (source.charAt(pos) == '\'' || source.charAt(pos) == '"')) {
                    tokens.add(new Token(Kind.STRING, string(source.charAt(pos))));
                } else {
                    tokens.add(new Token(Kind.IDENTIFIER, word));
                }
            } else {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                pos++;
            }
        }
        return tokens;
    }

    private static boolean isStringPrefix(String word) {
        String lower = word.toLowerCase();
        return lower.equals("r") || lower.equals("b") || lower.equals("rb") || lower.equals("br");
    }

    private void skipLineComment() {
        while (pos < source.length() && source.charAt(pos) != '\n' && source.charAt(pos) != '\r') {
            pos++;
        }
    }

    private void skipBlockComment() {
        pos += 2;
        int depth = 1;
        while (pos < source.length()) {
            if (source.startsWith("*/", pos)) {
                pos += 2;
                if (--depth == 0) return;
            } else if (dialect == Dialect.KOTLIN && source.startsWith("/*", pos)) {
                pos += 2;
                depth++;
            } else {
                pos++;
            }
        }
    }

    /** The content of the string opening at {@link #pos} with {@code quote}, consuming it. */
    private String string(char quote) {
        String triple = String.valueOf(quote).repeat(3);
        boolean isTriple = source.startsWith(triple, pos);
        String close = isTriple ? triple : String.valueOf(quote);
        boolean escapes = !(dialect == Dialect.KOTLIN && isTriple && quote == '"');
        pos += close.length();
        int start = pos;
        while (pos < source.length()) {
            if (escapes && source.charAt(pos) == '\\') {
                pos += 2;
            } else if (source.startsWith(close, pos)) {
                String content = source.substring(start, pos);
                pos += close.length();
                return content;
            } else {
                pos++;
            }
        }
        pos = source.length();
        return source.substring(Math.min(start, pos));
    }
}
