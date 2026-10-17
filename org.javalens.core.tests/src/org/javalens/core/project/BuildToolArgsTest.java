package org.javalens.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins how a {@code JAVALENS_*_ARGS} value is split into the build tool's arguments. */
class BuildToolArgsTest {

    @Test
    @DisplayName("Words split on whitespace; quotes group a word and are removed; a backslash escapes outside single quotes")
    void splitsLikeAShell() {
        assertEquals(List.of("-Pextras", "-Dname=a b", "it's", "-Dpath=C:\\dir", "x y"),
            BuildToolArgs.split("  -Pextras \"-Dname=a b\"\tit\\'s '-Dpath=C:\\dir' x\\ y "));
    }

    @Test
    @DisplayName("A blank value gives no arguments")
    void blankGivesNone() {
        assertEquals(List.of(), BuildToolArgs.split(" \t "));
    }
}
