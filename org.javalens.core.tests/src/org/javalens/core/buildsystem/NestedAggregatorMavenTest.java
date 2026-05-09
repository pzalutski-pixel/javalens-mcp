package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #8 — sources under nested aggregator Maven modules were not indexed.
 *
 * <p>The original {@code getAllSourcePaths} walked direct children of the root pom.
 * Reported by an external user: a real Spring-style multi-module project where the
 * {@code core/} module is itself a {@code <packaging>pom</packaging>} aggregator with no
 * {@code src/main/java}, listing {@code core-lib} and {@code core-api} as its modules.
 * Since {@code core/} contributed no sources of its own and the walk never descended into
 * its children, every type defined under {@code core/core-lib} or {@code core/core-api}
 * was unindexed and unresolvable.
 *
 * <p>The fix recursively visits every module — pure aggregators contribute nothing
 * themselves but hand off to their declared children, so leaf source roots at any depth
 * are reached.
 */
class NestedAggregatorMavenTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("Maven sources under nested aggregator modules are discovered")
    void nestedAggregatorSourcesDiscovered() throws Exception {
        // Fixture shape: root → core (aggregator, no sources) → core-lib + core-api (leaves)
        // root → simple (direct leaf, ensures regression coverage for the depth-1 case)
        JdtServiceImpl service = helper.loadProject("nested-aggregator-maven");
        ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

        // Each leaf module's src/main/java must be a source folder. Use forward-slash
        // normalization so the assertions are platform-agnostic.
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("core/core-lib/src/main/java")),
            "Expected core/core-lib/src/main/java among source folders. " +
            "Got: " + snapshot.sourceFolders());
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("core/core-api/src/main/java")),
            "Expected core/core-api/src/main/java among source folders. " +
            "Got: " + snapshot.sourceFolders());
        assertTrue(
            snapshot.sourceFolders().stream().anyMatch(p -> p.toString().replace('\\', '/')
                .endsWith("simple/src/main/java")),
            "Expected simple/src/main/java among source folders (regression check that " +
            "depth-1 leaf modules still work). Got: " + snapshot.sourceFolders());

        // The deeper assertion: types declared in nested leaves must resolve through JDT.
        IType coreLib = service.findType("com.example.core.lib.CoreLib");
        assertNotNull(coreLib, "Expected CoreLib (under core/core-lib) to resolve");
        IType coreApi = service.findType("com.example.core.api.CoreApi");
        assertNotNull(coreApi, "Expected CoreApi (under core/core-api) to resolve");
        IType simple = service.findType("com.example.simple.SimpleLeaf");
        assertNotNull(simple, "Expected SimpleLeaf (under direct leaf simple/) to resolve");
    }
}
