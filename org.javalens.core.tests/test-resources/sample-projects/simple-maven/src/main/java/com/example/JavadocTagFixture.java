package com.example;

import java.io.IOException;

/**
 * Fixture exercising modern Javadoc tag forms beyond the canonical
 * &#64;param/&#64;return/&#64;throws set.
 */
public class JavadocTagFixture {

    /**
     * Demonstrates &#64;apiNote, &#64;implNote, &#64;implSpec — the JEP 285
     * standard tags — plus &#64;exception as the older synonym for
     * &#64;throws.
     *
     * @apiNote callers should never pass null. This is part of the
     *          documented contract since 1.0.
     * @implSpec the implementation evaluates eagerly and is not
     *           thread-safe.
     * @implNote uses a recursive descent; safe up to depth 256.
     * @exception IOException when the resource cannot be opened.
     */
    public void documentedMethod(String input) throws IOException {
    }
}
