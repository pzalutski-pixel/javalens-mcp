package org.javalens.mcp.rewrite;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaStatusContext;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * RefactoringInvoker lists a status's findings, the conflicts and the warnings alike, by the place
 * each names, its file then its offset, those naming no place after them in the order the status
 * holds them; and names a file a message gives by its workspace path as every tool names it. run()
 * lists its refusal reasons and warnings through the same ordering and naming.
 *
 * <p>The fixture is rename-conflicts, package com.example.methods: Methods.java declares run()
 * (line 3) and go() (line 6); Child.java declares begin().
 */
class RefactoringInvokerFindingsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private IMethod run;
    private IMethod go;
    private IMethod begin;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("rename-conflicts");
        run = service.findType("com.example.methods.Methods").getMethod("run", new String[0]);
        go = service.findType("com.example.methods.Methods").getMethod("go", new String[0]);
        begin = service.findType("com.example.methods.Child").getMethod("begin", new String[0]);
    }

    @Test
    @DisplayName("conflicts and warnings each run by file then offset, those naming no place last in the status's order")
    void findingsByPlace() {
        RefactoringStatus status = new RefactoringStatus();
        status.addFatalError("go conflict", JavaStatusContext.create(go));
        status.addError("first placeless conflict");
        status.addError("run conflict", JavaStatusContext.create(run));
        status.addFatalError("second placeless conflict");
        status.addFatalError("begin conflict", JavaStatusContext.create(begin));
        status.addWarning("go warning", JavaStatusContext.create(go));
        status.addInfo("placeless note");
        status.addWarning("begin warning", JavaStatusContext.create(begin));

        RefactoringInvoker.Findings findings = RefactoringInvoker.findings(status, service);

        assertEquals(List.of("begin conflict", "run conflict", "go conflict",
            "first placeless conflict", "second placeless conflict"), findings.conflicts());
        assertEquals(List.of("begin warning", "go warning", "placeless note"), findings.warnings());
    }

    @Test
    @DisplayName("a workspace path in a message, with or without its leading slash, is named as every tool names the file")
    void workspacePathsRenamed() {
        String full = run.getCompilationUnit().getResource().getFullPath().toString();
        String message = "'" + full + "' and '" + full.substring(1) + "' have syntax errors.";

        assertEquals("'src/main/java/com/example/methods/Methods.java' and"
                + " 'src/main/java/com/example/methods/Methods.java' have syntax errors.",
            RefactoringInvoker.withProjectPaths(message, service));
    }

    @Test
    @DisplayName("a workspace path of the project naming no file the workspace holds is an IllegalStateException")
    void unknownWorkspacePathFails() {
        String message = "'/" + service.getJavaProject().getElementName() + "/nowhere/Gone.java' has syntax errors.";

        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> RefactoringInvoker.withProjectPaths(message, service));
        assertEquals("JDT names a file the workspace does not hold: " + message, failure.getMessage());
    }
}
