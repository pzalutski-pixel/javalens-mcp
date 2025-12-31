package org.javalens.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.GetEnclosingElementTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for GetEnclosingElementTool.
 * Tests context resolution at positions.
 */
class GetEnclosingElementToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private GetEnclosingElementTool tool;
    private ObjectMapper objectMapper;
    private Path projectPath;
    private String calculatorPath;
    private String userServicePath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetEnclosingElementTool(() -> service);
        objectMapper = new ObjectMapper();
        projectPath = helper.getFixturePath("simple-maven");
        calculatorPath = projectPath.resolve("src/main/java/com/example/Calculator.java").toString();
        userServicePath = projectPath.resolve("src/main/java/com/example/service/UserService.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getEnclosingType(Map<String, Object> data) {
        return (Map<String, Object>) data.get("enclosingType");
    }

    // ========== Comprehensive Functionality Tests ==========

    @Test
    @DisplayName("Method body position returns enclosingType with name, qualifiedName, kind, filePath, and package")
    @SuppressWarnings("unchecked")
    void methodBodyPosition_returnsAllContextInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 15);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        // File info
        assertNotNull(data.get("filePath"));
        assertEquals("com.example", data.get("enclosingPackage"));

        // Enclosing type info
        Map<String, Object> enclosingType = getEnclosingType(data);
        assertNotNull(enclosingType);
        assertEquals("Calculator", enclosingType.get("name"));
        assertEquals("com.example.Calculator", enclosingType.get("qualifiedName"));
        assertEquals("Class", enclosingType.get("kind"));

        // Element info if present
        Map<String, Object> element = (Map<String, Object>) data.get("element");
        if (element != null) {
            assertNotNull(element.get("name"));
            assertNotNull(element.get("kind"));
        }
    }

    @Test
    @DisplayName("Method declaration position returns enclosingType and file info")
    void methodDeclarationPosition_returnsContextInfo() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 14);
        args.put("column", 15);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        assertNotNull(data.get("enclosingType"));
        assertNotNull(data.get("filePath"));
    }

    @Test
    @DisplayName("Type level position returns enclosingType with no enclosingMethod")
    void typeLevelPosition_noEnclosingMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", calculatorPath);
        args.put("line", 5);
        args.put("column", 13);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);

        Map<String, Object> enclosingType = getEnclosingType(data);
        assertNotNull(enclosingType);
        assertEquals("Calculator", enclosingType.get("name"));
        assertNull(data.get("enclosingMethod"));
    }

    @Test
    @DisplayName("Service class returns correct package")
    void serviceClass_returnsCorrectPackage() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", userServicePath);
        args.put("line", 20);
        args.put("column", 10);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertEquals("com.example.service", data.get("enclosingPackage"));
    }

    // ========== Parameter Validation Tests ==========

    @Test
    @DisplayName("Missing or invalid parameters return error")
    void parameterValidation_returnsErrors() {
        // Missing filePath
        ObjectNode args1 = objectMapper.createObjectNode();
        args1.put("line", 15);
        args1.put("column", 10);
        assertFalse(tool.execute(args1).isSuccess());
        assertNotNull(tool.execute(args1).getError());

        // Negative line
        ObjectNode args2 = objectMapper.createObjectNode();
        args2.put("filePath", calculatorPath);
        args2.put("line", -1);
        args2.put("column", 10);
        assertFalse(tool.execute(args2).isSuccess());

        // Negative column
        ObjectNode args3 = objectMapper.createObjectNode();
        args3.put("filePath", calculatorPath);
        args3.put("line", 15);
        args3.put("column", -1);
        assertFalse(tool.execute(args3).isSuccess());

        // File not found
        ObjectNode args4 = objectMapper.createObjectNode();
        args4.put("filePath", "/nonexistent/path/File.java");
        args4.put("line", 0);
        args4.put("column", 0);
        assertFalse(tool.execute(args4).isSuccess());
    }
}
