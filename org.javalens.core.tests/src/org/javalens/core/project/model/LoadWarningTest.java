package org.javalens.core.project.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
        assertEquals("BUILD_TOOL_UNAVAILABLE", LoadWarning.BUILD_TOOL_UNAVAILABLE);
        assertEquals("BUILD_TOOL_FAILED", LoadWarning.BUILD_TOOL_FAILED);
        assertEquals("BUILD_TOOL_TIMEOUT", LoadWarning.BUILD_TOOL_TIMEOUT);
        assertEquals("BAZEL_NOT_BUILT", LoadWarning.BAZEL_NOT_BUILT);
        assertEquals("COMPLIANCE_LEVEL_UNKNOWN", LoadWarning.COMPLIANCE_LEVEL_UNKNOWN);
        assertEquals("APT_PROCESSOR_JARS_MISSING", LoadWarning.APT_PROCESSOR_JARS_MISSING);
        assertEquals("MODULE_NOT_LOADED", LoadWarning.MODULE_NOT_LOADED);
        assertEquals("MODULE_LAYOUT_ASSUMED", LoadWarning.MODULE_LAYOUT_ASSUMED);
        assertEquals("COMPILED_OUTPUT_INCLUDED", LoadWarning.COMPILED_OUTPUT_INCLUDED);
        assertEquals("INCLUDED_OUTSIDE_BUILD", LoadWarning.INCLUDED_OUTSIDE_BUILD);
        assertEquals("COMPLIANCE_LEVEL_MIXED", LoadWarning.COMPLIANCE_LEVEL_MIXED);
    }

    @Test
    @DisplayName("The public code constants are exactly the documented codes, each valued as its name")
    void codeConstants_areExactlyTheDocumentedSet() {
        // Consumers read the codes from the schema, so a code that is replaced is removed,
        // never kept as an alias.
        Map<String, Object> constants = new TreeMap<>();
        for (Field field : LoadWarning.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isPublic(modifiers) && Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && field.getType() == String.class) {
                try {
                    constants.put(field.getName(), field.get(null));
                } catch (IllegalAccessException e) {
                    throw new AssertionError("public constant " + field.getName() + " is not readable", e);
                }
            }
        }

        Map<String, Object> expected = new TreeMap<>();
        for (String code : List.of(
                "BUILD_TOOL_UNAVAILABLE", "BUILD_TOOL_FAILED", "BUILD_TOOL_TIMEOUT",
                "BAZEL_NOT_BUILT", "COMPLIANCE_LEVEL_UNKNOWN", "COMPLIANCE_LEVEL_UNSUPPORTED",
                "APT_PROCESSOR_JARS_MISSING", "JRE_REGISTRATION_FAILED",
                "MODULE_NOT_LOADED", "MODULE_LAYOUT_ASSUMED", "COMPILED_OUTPUT_INCLUDED",
                "INCLUDED_OUTSIDE_BUILD", "COMPLIANCE_LEVEL_MIXED", "EARLIER_PROJECT_KEPT")) {
            expected.put(code, code);
        }
        assertEquals(expected, constants);
    }

    @Test
    @DisplayName("Code constants are mutually distinct")
    void codeConstants_distinct() {
        // Defensive: a typo could accidentally make two constants equal. Verify they're all
        // distinct identifiers.
        String[] codes = {
            LoadWarning.BUILD_TOOL_UNAVAILABLE,
            LoadWarning.BUILD_TOOL_FAILED,
            LoadWarning.BUILD_TOOL_TIMEOUT,
            LoadWarning.BAZEL_NOT_BUILT,
            LoadWarning.COMPLIANCE_LEVEL_UNKNOWN,
            LoadWarning.APT_PROCESSOR_JARS_MISSING,
            LoadWarning.MODULE_NOT_LOADED,
            LoadWarning.MODULE_LAYOUT_ASSUMED,
            LoadWarning.COMPILED_OUTPUT_INCLUDED,
            LoadWarning.INCLUDED_OUTSIDE_BUILD,
            LoadWarning.COMPLIANCE_LEVEL_MIXED
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
