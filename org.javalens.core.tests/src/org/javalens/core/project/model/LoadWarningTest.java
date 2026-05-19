package org.javalens.core.project.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pins LoadWarning's record contract and the 3-arg compact constructor.
 *
 * <p>LoadWarning is consumed by load_project's response envelope and by every
 * test that asserts a specific warning code appeared (BazelNotBuiltWarningTest,
 * AnnotationProcessingTest, SilentSubprocessFailureTest, etc.). The code
 * constants are public API surface — pinning their values catches a regression
 * that renames or removes a documented code.
 */
class LoadWarningTest {

    @Test
    @DisplayName("4-arg canonical constructor preserves every field")
    void canonicalConstructor_preservesFields() {
        LoadWarning w = new LoadWarning("CODE", "msg", "fix", "module-x");
        assertEquals("CODE", w.code());
        assertEquals("msg", w.message());
        assertEquals("fix", w.remediation());
        assertEquals("module-x", w.module());
    }

    @Test
    @DisplayName("3-arg compact constructor delegates to canonical with module=null")
    void compactConstructor_setsModuleToNull() {
        LoadWarning w = new LoadWarning("CODE", "msg", "fix");
        assertEquals("CODE", w.code());
        assertEquals("msg", w.message());
        assertEquals("fix", w.remediation());
        assertNull(w.module(),
            "3-arg constructor must default module to null (project-wide warning)");
    }

    @Test
    @DisplayName("Stable code constants have their documented exact values")
    void codeConstants_haveDocumentedValues() {
        // Test consumers downstream (in tests AND in the load_project response) pattern-match
        // on these exact strings. A rename would break every check; pin the constants.
        assertEquals("MAVEN_SUBPROCESS_FAILED", LoadWarning.MAVEN_SUBPROCESS_FAILED);
        assertEquals("MAVEN_SUBPROCESS_TIMEOUT", LoadWarning.MAVEN_SUBPROCESS_TIMEOUT);
        assertEquals("GRADLE_SUBPROCESS_FAILED", LoadWarning.GRADLE_SUBPROCESS_FAILED);
        assertEquals("BAZEL_NOT_BUILT", LoadWarning.BAZEL_NOT_BUILT);
        assertEquals("COMPLIANCE_LEVEL_UNKNOWN", LoadWarning.COMPLIANCE_LEVEL_UNKNOWN);
        assertEquals("APT_PROCESSOR_JARS_MISSING", LoadWarning.APT_PROCESSOR_JARS_MISSING);
    }

    @Test
    @DisplayName("Code constants are mutually distinct")
    void codeConstants_distinct() {
        // Defensive: a typo could accidentally make two constants equal. Verify they're all
        // distinct identifiers.
        String[] codes = {
            LoadWarning.MAVEN_SUBPROCESS_FAILED,
            LoadWarning.MAVEN_SUBPROCESS_TIMEOUT,
            LoadWarning.GRADLE_SUBPROCESS_FAILED,
            LoadWarning.BAZEL_NOT_BUILT,
            LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
            LoadWarning.APT_PROCESSOR_JARS_MISSING
        };
        for (int i = 0; i < codes.length; i++) {
            for (int j = i + 1; j < codes.length; j++) {
                assertNotEquals(codes[i], codes[j],
                    "Code constants must be distinct; got duplicate: " + codes[i]);
            }
        }
    }

    @Test
    @DisplayName("Records use value equality")
    void recordEquality() {
        LoadWarning a = new LoadWarning("X", "m", "r", "mod");
        LoadWarning b = new LoadWarning("X", "m", "r", "mod");
        LoadWarning c = new LoadWarning("X", "m", "r", "different");
        assertEquals(a, b, "Records with the same field values must be equal");
        assertEquals(a.hashCode(), b.hashCode(),
            "Equal records must have equal hashCodes (Object contract)");
        assertNotEquals(a, c,
            "Records differing in any field (module here) must NOT be equal");
    }

    @Test
    @DisplayName("toString contains every field — useful for assertion failure messages")
    void toStringIncludesFields() {
        LoadWarning w = new LoadWarning("MYCODE", "what", "how", "module-x");
        String s = w.toString();
        assertNotNull(s);
        // Record toString lists each component; assertion-failure messages downstream
        // pattern-match on this.
        org.junit.jupiter.api.Assertions.assertAll(
            () -> org.junit.jupiter.api.Assertions.assertTrue(s.contains("MYCODE"),
                "toString must include code; got: " + s),
            () -> org.junit.jupiter.api.Assertions.assertTrue(s.contains("what"),
                "toString must include message; got: " + s),
            () -> org.junit.jupiter.api.Assertions.assertTrue(s.contains("how"),
                "toString must include remediation; got: " + s),
            () -> org.junit.jupiter.api.Assertions.assertTrue(s.contains("module-x"),
                "toString must include module; got: " + s)
        );
    }
}
