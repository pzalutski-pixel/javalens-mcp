package org.javalens.core.fixtures;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Contract test for {@link GradleBinary}'s override: it sets the importer's binary
 * property while open and restores exactly the previous value when closed.
 */
class GradleBinaryTest {

    private String saved;

    @BeforeEach
    void saveProperty() {
        saved = System.getProperty(GradleBinary.PROPERTY);
    }

    @AfterEach
    void restoreProperty() {
        if (saved == null) {
            System.clearProperty(GradleBinary.PROPERTY);
        } else {
            System.setProperty(GradleBinary.PROPERTY, saved);
        }
    }

    @Test
    @DisplayName("The property names the binary the Gradle importer reads")
    void propertyName() {
        assertEquals("javalens.gradle.binary", GradleBinary.PROPERTY);
    }

    @Test
    @DisplayName("use sets the property while open; close clears it when it was unset before")
    void useThenCloseClearsAnUnsetProperty() {
        System.clearProperty(GradleBinary.PROPERTY);

        String whileOpen;
        try (GradleBinary ignored = GradleBinary.use("/fake/gradle")) {
            whileOpen = System.getProperty(GradleBinary.PROPERTY);
        }

        String afterClose = System.getProperty(GradleBinary.PROPERTY);
        assertAll(
            () -> assertEquals("/fake/gradle", whileOpen, "while open"),
            () -> assertNull(afterClose, "after close"));
    }

    @Test
    @DisplayName("close restores the value the property held before use")
    void useThenCloseRestoresThePreviousValue() {
        System.setProperty(GradleBinary.PROPERTY, "/previous/gradle");

        String whileOpen;
        try (GradleBinary ignored = GradleBinary.use("/fake/gradle")) {
            whileOpen = System.getProperty(GradleBinary.PROPERTY);
        }

        String afterClose = System.getProperty(GradleBinary.PROPERTY);
        assertAll(
            () -> assertEquals("/fake/gradle", whileOpen, "while open"),
            () -> assertEquals("/previous/gradle", afterClose, "after close"));
    }
}
