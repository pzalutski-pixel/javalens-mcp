package org.javalens.core.project;

import java.util.ArrayList;
import java.util.List;

/**
 * Extra arguments a user passes to a build tool through an environment variable
 * ({@code JAVALENS_MAVEN_ARGS}, {@code JAVALENS_GRADLE_ARGS}, {@code JAVALENS_BAZEL_ARGS}),
 * so the load runs the build as the user runs it: profiles, properties, flags.
 *
 * <p>The value is split as a shell splits words: on whitespace, except inside single or
 * double quotes, which group a word and are removed. A backslash escapes the next character
 * outside single quotes.
 */
final class BuildToolArgs {

    static final String MAVEN = "JAVALENS_MAVEN_ARGS";
    static final String GRADLE = "JAVALENS_GRADLE_ARGS";
    static final String BAZEL = "JAVALENS_BAZEL_ARGS";

    private BuildToolArgs() {
    }

    /**
     * The arguments the environment variable {@code variable} holds, or the system property of
     * the same name, which takes precedence (a test seam); none when neither is set.
     */
    static List<String> of(String variable) {
        String value = System.getProperty(variable);
        if (value == null) {
            value = System.getenv(variable);
        }
        return value == null ? List.of() : split(value);
    }

    /** {@code value} split into words. */
    static List<String> split(String value) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        char quote = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (quote == '\'') {
                if (c == '\'') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\\' && i + 1 < value.length()) {
                word.append(value.charAt(++i));
                inWord = true;
            } else if (quote == '"') {
                if (c == '"') {
                    quote = 0;
                } else {
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                inWord = true;
            } else if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
            } else {
                word.append(c);
                inWord = true;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return List.copyOf(words);
    }
}
