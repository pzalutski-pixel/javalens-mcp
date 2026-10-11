package org.javalens.core.project;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a Groovy or Kotlin Gradle script into tokens, dropping comments, so a static read
 * of the script sees only code.
 *
 * <p>Comments are a double slash to the end of the line, and block comments from slash-star
 * to star-slash. Kotlin block comments nest; a Groovy block comment ends at the first
 * star-slash. Strings are {@code '...'}, {@code "..."}, {@code '''...'''} and
 * {@code """..."""}; a backslash escapes the next character except in a Kotlin
 * {@code """} string, which is raw. A string token's text is its content as written,
 * without the quotes. Groovy's slashy strings are not recognized: their slashes read as
 * symbols. An unterminated comment or string runs to the end of the script.
 */
final class BuildLexer {

    enum Kind { IDENTIFIER, STRING, SYMBOL }

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
    private final boolean kotlin;
    private int pos;

    private BuildLexer(String source, boolean kotlin) {
        this.source = source;
        this.kotlin = kotlin;
    }

    /** The tokens of a Groovy script ({@code settings.gradle}, {@code build.gradle}). */
    static List<Token> groovy(String source) {
        return new BuildLexer(source, false).tokens();
    }

    /** The tokens of a Kotlin script ({@code settings.gradle.kts}, {@code build.gradle.kts}). */
    static List<Token> kotlin(String source) {
        return new BuildLexer(source, true).tokens();
    }

    private List<Token> tokens() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            char c = source.charAt(pos);
            if (Character.isWhitespace(c)) {
                pos++;
            } else if (source.startsWith("//", pos)) {
                skipLineComment();
            } else if (source.startsWith("/*", pos)) {
                skipBlockComment();
            } else if (c == '\'' || c == '"') {
                tokens.add(new Token(Kind.STRING, string(c)));
            } else if (Character.isJavaIdentifierStart(c)) {
                int start = pos;
                while (pos < source.length() && Character.isJavaIdentifierPart(source.charAt(pos))) {
                    pos++;
                }
                tokens.add(new Token(Kind.IDENTIFIER, source.substring(start, pos)));
            } else {
                tokens.add(new Token(Kind.SYMBOL, String.valueOf(c)));
                pos++;
            }
        }
        return tokens;
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
            } else if (kotlin && source.startsWith("/*", pos)) {
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
        boolean escapes = !(kotlin && isTriple && quote == '"');
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
