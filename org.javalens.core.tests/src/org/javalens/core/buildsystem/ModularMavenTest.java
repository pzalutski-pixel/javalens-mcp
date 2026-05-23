package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins JavaLens's load behavior for projects declaring a {@code module-info.java}
 * (JPMS / Java 9+ module system).
 *
 * <p>The contract is: a {@code module-info.java} alongside regular source files must
 * not cause the load to abort, hang, or skip the rest of the project. JDT supports
 * module descriptors via {@link org.eclipse.jdt.core.IModuleDescription}; this test
 * pins only the JavaLens-side guarantees that the load completes, the module file
 * is enumerated, and a member type inside the module is reachable through
 * {@link JdtServiceImpl#findType(String)}.
 */
class ModularMavenTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Modular Maven project loads without errors and surfaces the module descriptor")
    void modularMaven_loadsCleanly() throws Exception {
        JdtServiceImpl service = helper.loadProject("modular-maven");

        assertNotNull(service.getJavaProject(), "JavaProject must exist after load");
        assertTrue(service.getJavaProject().exists(),
            "JavaProject must report exists() after load");

        Path modulePath = helper.getFixturePath("modular-maven")
            .resolve("src/main/java/module-info.java");
        assertTrue(Files.exists(modulePath),
            "module-info.java must be present in the fixture: " + modulePath);
    }

    @Test
    @DisplayName("Source files inside a modular project are enumerable via getAllJavaFiles")
    void modularMaven_enumeratesSources() throws Exception {
        JdtServiceImpl service = helper.loadProject("modular-maven");

        long javaFiles = service.getAllJavaFiles().stream().count();
        // Fixture has module-info.java + Hub.java = 2 files. The load must surface both.
        assertEquals(2, javaFiles,
            "Expected exactly 2 source files (module-info.java + Hub.java); got: " + javaFiles);
    }

    @Test
    @DisplayName("Types inside a modular project resolve via findType")
    void modularMaven_findTypeResolvesMember() throws Exception {
        JdtServiceImpl service = helper.loadProject("modular-maven");

        IType hub = service.findType("com.example.modular.Hub");
        assertNotNull(hub,
            "findType must resolve com.example.modular.Hub from a modular project");
        assertTrue(hub.exists(), "Hub IType must report exists()");
        assertEquals("Hub", hub.getElementName());
    }

    @Test
    @DisplayName("Project compliance is the declared 21 — module-info presence does not regress level detection")
    void modularMaven_complianceIs21() throws Exception {
        var fixture = helper.loadFixture("modular-maven");
        assertEquals("21", fixture.classpath().compilerSource(),
            "modular-maven declares Java 21; the module descriptor must not override that");
        assertEquals("21", fixture.classpath().compilerCompliance(),
            "modular-maven declares Java 21; compliance must follow");
    }
}
