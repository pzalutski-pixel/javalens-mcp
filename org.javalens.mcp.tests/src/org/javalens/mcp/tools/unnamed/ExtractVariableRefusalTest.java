package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.tools.ExtractVariableTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;

/**
 * extract_variable refuses an expression no variable can be declared for, as JDT's extract local
 * variable does (ExtractTempRefactoring.checkExpression refuses a null literal, :668-669;
 * checkExpressionFragmentIsRValue refuses an expression of type void, :697-699): a void
 * expression has no value to hold, and a null literal alone has no type of its own.
 *
 * <p>Notes.java (codegen-names): {@code write()} calls the void method {@code log("start")}
 * (line 11, columns 8 to 20) and assigns {@code null} (line 12, columns 15 to 19).
 */
class ExtractVariableRefusalTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(CodegenNames.FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    @Test
    @DisplayName("extract_variable of the void call log(\"start\") is INVALID_PARAMETER: an expression of type void has no value to hold")
    void voidExpression() {
        assertPayload(mapper, new ExtractVariableTool(() -> service), envelope, selection(11, 8, 20),
            invalidParameter("Invalid parameter 'selection': Expression at selection is of type void, "
                + "which has no value to hold in a variable"));
    }

    @Test
    @DisplayName("extract_variable of the null literal is INVALID_PARAMETER: a null literal alone has no type to declare the variable with")
    void nullLiteral() {
        assertPayload(mapper, new ExtractVariableTool(() -> service), envelope, selection(12, 15, 19),
            invalidParameter("Invalid parameter 'selection': Expression at selection is the null literal alone, "
                + "which has no type to declare the variable with"));
    }

    private ObjectNode selection(int line, int startColumn, int endColumn) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath(CodegenNames.FIXTURE).resolve(CodegenNames.GEN + "Notes.java").toString());
        args.put("startLine", line);
        args.put("startColumn", startColumn);
        args.put("endLine", line);
        args.put("endColumn", endColumn);
        return args;
    }
}
