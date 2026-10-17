package org.javalens.core.buildsystem;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.JdtServiceImpl;
import org.javalens.core.fixtures.BazelBinary;
import org.javalens.core.fixtures.ClasspathSnapshot;
import org.javalens.core.fixtures.TestProjectHelper;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.BuildModule;
import org.javalens.core.project.model.Provenance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bazel counterpart of {@link EndToEndIntegrationTest}: a realistic multi-target Bazel build
 * loaded after a real {@code bazel build //...}. Mirrors the Maven and Gradle E2E tests'
 * structure.
 *
 * <p>Layout: three {@code java_library} targets ({@code //model}, {@code //service},
 * {@code //web}) with cross-target dependencies. {@code service} depends on {@code model};
 * {@code web} depends on both. {@code model} and {@code service} declare
 * {@code -source 17 -target 17}, {@code web} declares {@code --release=17}.
 */
class EndToEndBazelIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("realistic Bazel multi-target after a build: each package's sources are linked, its own jar is not a library, the level is the actions' 17, and references cross targets")
    void allFixesExercisedInASingleLoad() throws Exception {
        try (BazelBinary bazel = BazelBinary.require("end-to-end Bazel load")) {
            Path root = helper.copyFixture("realistic-multi-target-bazel");
            // An output root of the test's own, under its temporary directory, so no stale
            // cache of an earlier run is read and everything Bazel writes goes with the test.
            bazel.isolate(root, helper.getTempDirectory().resolve("_bazel"));
            bazel.run(root, "build", "--lockfile_mode=off", "//...");

            JdtServiceImpl service = new JdtServiceImpl();
            service.loadProject(root);
            BuildModel model = service.getBuildModel();
            ClasspathSnapshot snapshot = ClasspathSnapshot.capture(service.getJavaProject());

            IType user = service.findType("com.example.model.User");
            List<String> referencingFiles = service.getSearchService()
                .findReferences(user, IJavaSearchConstants.REFERENCES, 100).matches().stream()
                .map(SearchMatch::getResource)
                .map(resource -> resource.getName())
                .distinct()
                .sorted()
                .toList();
            assertAll(
                () -> assertEquals(Provenance.BUILD_TOOL, model.provenance(), "provenance"),
                () -> assertEquals(List.of("//model", "//service", "//web"),
                    model.modules().stream().map(BuildModule::id).toList(), "module ids"),
                () -> assertEquals(List.of(root.resolve("model/src/main/java"), root.resolve("service/src/main/java"),
                        root.resolve("web/src/main/java")),
                    snapshot.sourceFolders(), "linked source folders"),
                // Each target's jar has a linked source for every class, so the compiled-output
                // rule leaves it off: the libraries hold no jar of the project's own targets.
                () -> assertEquals(Set.of(), Set.copyOf(snapshot.libraries()), "libraries"),
                () -> assertEquals(List.of(), service.getWarnings(), "warnings"),
                () -> assertEquals("17", snapshot.compilerSource(), "compiler source"),
                () -> assertEquals("17", snapshot.compilerCompliance(), "compiler compliance"),
                () -> assertEquals(root.resolve("model/src/main/java/com/example/model/User.java"),
                    location(user), "User"),
                () -> assertEquals(root.resolve("service/src/main/java/com/example/service/UserService.java"),
                    location(service.findType("com.example.service.UserService")), "UserService"),
                () -> assertEquals(root.resolve("web/src/main/java/com/example/web/UserController.java"),
                    location(service.findType("com.example.web.UserController")), "UserController"),
                () -> assertEquals(List.of("UserController.java", "UserService.java"), referencingFiles,
                    "files referencing User"));
        }
    }

    private static Path location(IType type) {
        return type == null ? null : Path.of(type.getResource().getLocation().toOSString());
    }
}
