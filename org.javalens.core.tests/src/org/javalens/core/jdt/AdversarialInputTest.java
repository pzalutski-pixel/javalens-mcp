package org.javalens.core.jdt;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins JavaLens's behavior on adversarial / edge-case file inputs from the
 * B2 plan items. Each test constructs its file from simple-maven via
 * {@link TestProjectHelper#copyFixture} and mutates the working copy, so
 * the committed fixtures remain unmodified.
 *
 * <p>Tests here demonstrate that the load/search/position-resolution
 * pipeline tolerates inputs that often break naïve text-handling code:
 * BOM-prefixed files, CRLF line endings, and Unicode identifiers.
 */
class AdversarialInputTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("B2-5: load tolerates a UTF-8 BOM at the start of a source file")
    void utf8BomPrefixedFile_loadsCleanly() throws Exception {
        Path projectRoot = helper.copyFixture("simple-maven");
        Path calc = projectRoot.resolve("src/main/java/com/example/Calculator.java");
        byte[] original = Files.readAllBytes(calc);
        // U+FEFF as the BOM byte sequence in UTF-8 is 0xEF 0xBB 0xBF.
        byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] withBom = new byte[bom.length + original.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(original, 0, withBom, bom.length, original.length);
        Files.write(calc, withBom);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        // Despite the BOM, Calculator must resolve via findType — JDT/JavaLens must
        // strip or tolerate the BOM, not let it derail parsing of `package com.example;`.
        IType type = service.findType("com.example.Calculator");
        assertNotNull(type, "Calculator must resolve even with a UTF-8 BOM at file start");
        assertTrue(type.exists(), "Calculator IType must exist with BOM-prefixed source");
        assertEquals("Calculator", type.getElementName());
    }

    @Test
    @DisplayName("B2-6: load tolerates CRLF line endings, position resolution is correct")
    void crlfLineEndings_positionsResolveCorrectly() throws Exception {
        Path projectRoot = helper.copyFixture("simple-maven");
        Path calc = projectRoot.resolve("src/main/java/com/example/Calculator.java");
        String original = Files.readString(calc, StandardCharsets.UTF_8);
        // Force CRLF; if the original is LF (default on this repo for non-Windows
        // checkout) we replace; if it's already CRLF the replace is a no-op.
        String crlf = original.replace("\r\n", "\n").replace("\n", "\r\n");
        Files.writeString(calc, crlf, StandardCharsets.UTF_8);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        IType type = service.findType("com.example.Calculator");
        assertNotNull(type, "Calculator must resolve with CRLF line endings");
        assertEquals("Calculator", type.getElementName());

        // Position resolution: file line 7 (CRLF count must not skew the offset table).
        // Calculator.java line 7 (0-based 6) is `    private int lastResult;`.
        var element = service.getElementAtPosition(calc, 6, 16);
        assertNotNull(element, "getElementAtPosition must resolve under CRLF; got null");
        assertEquals("lastResult", element.getElementName(),
            "Position on `lastResult` must still resolve under CRLF line endings");
    }

    @Test
    @DisplayName("B2-7: load tolerates a Unicode identifier and the type resolves via findType")
    void unicodeIdentifier_loadsAndResolves() throws Exception {
        Path projectRoot = helper.copyFixture("simple-maven");
        Path unicodeFile = projectRoot.resolve("src/main/java/com/example/Café.java");
        String source = """
                package com.example;

                /**
                 * Class with a non-ASCII identifier (Java spec permits Unicode letters).
                 */
                public class Café {
                    public int naïveCount;
                }
                """;
        Files.writeString(unicodeFile, source, StandardCharsets.UTF_8);

        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(projectRoot);

        IType type = service.findType("com.example.Café");
        assertNotNull(type, "findType must resolve a class with a non-ASCII identifier");
        assertTrue(type.exists(), "IType for non-ASCII class must exist");
        assertEquals("Café", type.getElementName());
    }
}
