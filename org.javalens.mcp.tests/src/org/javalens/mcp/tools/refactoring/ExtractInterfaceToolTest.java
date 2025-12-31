package org.javalens.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.ExtractInterfaceTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for ExtractInterfaceTool.
 * Tests interface generation from class.
 */
class ExtractInterfaceToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ExtractInterfaceTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String interfaceTargetPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new ExtractInterfaceTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        interfaceTargetPath = projectPath.resolve("src/main/java/com/example/InterfaceExtractTarget.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getExtractedMethods(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("extractedMethods");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("extracts interface from class with complete response")
    void extractsInterfaceWithCompleteResponse() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);  // Class declaration line (0-based)
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // Verify interface info
        assertEquals("IExtractTarget", data.get("interfaceName"));
        assertNotNull(data.get("interfaceContent"));

        // Verify interface content structure
        String content = (String) data.get("interfaceContent");
        assertTrue(content.contains("package com.example"));
        assertTrue(content.contains("public interface IExtractTarget"));

        // Verify public methods are included
        List<Map<String, Object>> methods = getExtractedMethods(data);
        assertFalse(methods.isEmpty());

        // Verify class edits for implements clause
        assertNotNull(data.get("classEdits"));
    }

    @Test
    @DisplayName("excludes private and static methods from interface")
    void excludesPrivateAndStaticMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> methods = getExtractedMethods(data);

        // Should NOT include helper() which is private
        boolean hasHelper = methods.stream()
            .anyMatch(m -> ((String) m.get("name")).contains("helper"));
        assertFalse(hasHelper);

        // Should NOT include create() which is static
        boolean hasCreate = methods.stream()
            .anyMatch(m -> ((String) m.get("name")).contains("create"));
        assertFalse(hasCreate);
    }

    // ========== Optional Parameters Tests ==========

    @Test
    @DisplayName("allows selecting specific methods for interface")
    void allowsSelectingSpecificMethods() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        args.set("methodNames", objectMapper.createArrayNode().add("getName").add("setName"));

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        List<Map<String, Object>> methods = getExtractedMethods(data);
        assertEquals(2, methods.size());
    }

    // ========== Required Parameter Tests ==========

    @Test
    @DisplayName("requires interfaceName parameter")
    void requiresInterfaceName() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        // No interfaceName

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("requires filePath parameter")
    void requiresFilePath() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    // ========== Error Handling Tests ==========

    @Test
    @DisplayName("rejects invalid interface names")
    void rejectsInvalidInterfaceNames() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "123Invalid");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }

    @Test
    @DisplayName("handles invalid position gracefully")
    void handlesInvalidPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", interfaceTargetPath);
        args.put("line", 999);  // Way beyond file length
        args.put("column", 999);
        args.put("interfaceName", "IExtractTarget");

        ToolResponse response = tool.execute(args);

        assertFalse(response.isSuccess());
    }
}
