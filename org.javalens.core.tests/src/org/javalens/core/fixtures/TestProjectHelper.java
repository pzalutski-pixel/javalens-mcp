package org.javalens.core.fixtures;

import org.eclipse.core.runtime.CoreException;
import org.javalens.core.JdtServiceImpl;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;

/**
 * JUnit 5 extension for managing test workspaces.
 * Provides isolated workspace per test to prevent test interference.
 *
 * <p>Usage with {@code @RegisterExtension}:</p>
 * <pre>
 * class MyTest {
 *     {@code @RegisterExtension}
 *     TestProjectHelper helper = new TestProjectHelper();
 *
 *     {@code @Test}
 *     void testSomething() throws Exception {
 *         JdtServiceImpl service = helper.loadProject("simple-maven");
 *         // Use the service...
 *     }
 * }
 * </pre>
 */
public class TestProjectHelper implements BeforeEachCallback, AfterEachCallback {

    private static final String FIXTURES_PROPERTY = "javalens.test.fixtures";

    private Path tempDirectory;
    private JdtServiceImpl loadedService;

    @Override
    public void beforeEach(ExtensionContext context) throws Exception {
        tempDirectory = Files.createTempDirectory("javalens-test-");
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        // Clean up temp directory if created
        if (tempDirectory != null && Files.exists(tempDirectory)) {
            try {
                Files.walk(tempDirectory)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // Ignore cleanup errors
                        }
                    });
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }

    /**
     * Get the path to a test fixture project.
     *
     * @param projectName Name of the fixture project (e.g., "simple-maven")
     * @return Path to the fixture
     * @throws IllegalStateException if fixtures directory is not configured
     */
    public Path getFixturePath(String projectName) {
        String fixturesDir = System.getProperty(FIXTURES_PROPERTY);
        if (fixturesDir == null) {
            // Fallback: try to find relative to current directory
            Path fallback = Path.of("org.javalens.core.tests/test-resources/sample-projects", projectName);
            if (Files.exists(fallback)) {
                return fallback.toAbsolutePath();
            }

            throw new IllegalStateException(
                "Test fixtures directory not configured. " +
                "Set system property: " + FIXTURES_PROPERTY);
        }
        return Path.of(fixturesDir, projectName);
    }

    /**
     * Load a test project fixture and return the JDT service.
     *
     * @param fixtureName Name of the fixture project (e.g., "simple-maven")
     * @return Configured JdtServiceImpl
     * @throws CoreException if project loading fails
     */
    public JdtServiceImpl loadProject(String fixtureName) throws CoreException {
        Path projectPath = getFixturePath(fixtureName);
        loadedService = new JdtServiceImpl();
        loadedService.loadProject(projectPath);
        return loadedService;
    }

    /**
     * Copy a fixture project to a temporary directory.
     * Useful for tests that modify project files.
     *
     * @param fixtureName Name of the fixture project
     * @return Path to the copied project in temp directory
     * @throws IOException if copy fails
     */
    public Path copyFixture(String fixtureName) throws IOException {
        Path source = getFixturePath(fixtureName);
        Path dest = tempDirectory.resolve(fixtureName);
        copyDirectory(source, dest);
        return dest;
    }

    /**
     * Load a project from a temporary copy of a fixture.
     * Use this when tests need to modify project files.
     *
     * @param fixtureName Name of the fixture project
     * @return Configured JdtServiceImpl pointing to the copy
     * @throws CoreException if project loading fails
     * @throws IOException if copy fails
     */
    public JdtServiceImpl loadProjectCopy(String fixtureName) throws CoreException, IOException {
        Path projectPath = copyFixture(fixtureName);
        loadedService = new JdtServiceImpl();
        loadedService.loadProject(projectPath);
        return loadedService;
    }

    /**
     * Get the temporary directory for this test.
     *
     * @return Temporary directory path
     */
    public Path getTempDirectory() {
        return tempDirectory;
    }

    /**
     * Get the last loaded JdtServiceImpl.
     *
     * @return The service, or null if none loaded
     */
    public JdtServiceImpl getService() {
        return loadedService;
    }

    private void copyDirectory(Path source, Path dest) throws IOException {
        Files.walk(source).forEach(src -> {
            try {
                Path rel = source.relativize(src);
                Path target = dest.resolve(rel);
                if (Files.isDirectory(src)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new RuntimeException("Failed to copy " + src, e);
            }
        });
    }
}
