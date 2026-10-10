package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameVirtualMethodProcessor;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * rename_symbol's conditions for renaming a virtual method, checked on the Java model's own
 * hierarchy, find what JDT's own RenameVirtualMethodProcessor finds wherever JDT's processor can
 * run: the refusal names exactly the FATAL and ERROR findings of JDT's processor, in the same
 * order and words, and a rename that goes ahead lists exactly its WARNING and INFO findings. The
 * cases are every virtual-method rename of the rename-conflicts fixture whose related classes are
 * not declared in a lambda body (JDT's RippleMethodFinder2 asserts for those: RenameConflictTest
 * pins them by hand). Positions are zero-based, as RenameConflictTest gives them.
 */
class RenameConflictParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "rename-conflicts";
    private static final String ROOT = "src/main/java/com/example/";

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
    }

    @ParameterizedTest(name = "{0} {1}:{2} renamed to {3}")
    @DisplayName("rename_symbol's findings for a virtual method equal those of JDT's RenameVirtualMethodProcessor")
    @CsvSource({
        "methods/Methods.java, 3, 9, go",
        "methods/Methods.java, 3, 9, Methods",
        "methods/Parent.java, 3, 9, begin",
        "methods/StaticUser.java, 5, 8, max",
        "methods/Shape.java, 3, 9, paint",
        "methods/Circle.java, 3, 16, paint",
        "virtuals/Widen.java, 3, 9, set",
        "virtuals/NativeBase.java, 3, 9, toil",
        "virtuals/NativeChild.java, 3, 16, toil",
        "virtuals/Described.java, 3, 8, toString",
        "shadowmethods/Base.java, 3, 9, m",
        "shadowmethods/Host.java, 3, 9, m",
        "broken/Fine.java, 3, 9, ring",
        "broken/Broken.java, 3, 9, mend"
    })
    @SuppressWarnings("unchecked")
    void findingsEqualJdts(String file, int line, int column, String newName) throws Exception {
        Path path = helper.getFixturePath(FIXTURE).resolve(ROOT + file);
        IJavaElement element = service.getElementAtPosition(path, line, column);
        IMethod method = assertInstanceOf(IMethod.class, element);
        RenameVirtualMethodProcessor processor = new RenameVirtualMethodProcessor(method);
        processor.setUpdateReferences(true);
        RefactoringInvoker.Findings jdt = RefactoringInvoker.findings(new RenameRefactoring(processor),
            () -> processor.setNewElementName(newName), service);

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", path.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        ToolResponse response = new RenameSymbolTool(() -> service).execute(args);

        if (jdt.conflicts().isEmpty()) {
            assertEquals(true, response.isSuccess(), () -> "rename_symbol refused: " + response.getError().getMessage());
            Object warnings = ((Map<String, Object>) response.getData()).getOrDefault("warnings", List.of());
            assertEquals(jdt.warnings(), warnings);
        } else {
            assertEquals("Invalid parameter 'newName': " + String.join("; ", jdt.conflicts()),
                response.getError().getMessage());
        }
    }
}
