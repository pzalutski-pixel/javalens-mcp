package org.javalens.core.workspace;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for WorkspaceManager.
 * Tests workspace initialization, project creation, and lifecycle management.
 */
class WorkspaceManagerTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private WorkspaceManager workspaceManager;
    private Path fixturePath;

    @BeforeEach
    void setUp() throws Exception {
        workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
        fixturePath = helper.getFixturePath("simple-maven");
    }

    // ========== Initialization Tests ==========

    @Test
    @DisplayName("initialize should create workspace")
    void initialize_createsWorkspace() {
        IWorkspace workspace = workspaceManager.getWorkspace();

        assertNotNull(workspace, "Workspace should be available after initialization");
    }

    @Test
    @DisplayName("initialize should create workspace root")
    void initialize_createsWorkspaceRoot() {
        IWorkspaceRoot root = workspaceManager.getRoot();

        assertNotNull(root, "Workspace root should be available after initialization");
        assertNotNull(root.getLocation(), "Workspace root should have a location");
    }

    // ========== Session ID Tests ==========

    @Test
    @DisplayName("getSessionId should return unique 8-character ID")
    void getSessionId_returnsUniqueId() {
        String sessionId = workspaceManager.getSessionId();

        assertNotNull(sessionId, "Session ID should not be null");
        assertEquals(8, sessionId.length(), "Session ID should be 8 characters");
        assertTrue(sessionId.matches("[a-f0-9]+"),
            "Session ID should be hexadecimal: " + sessionId);
    }

    @Test
    @DisplayName("different WorkspaceManager instances should have different session IDs")
    void getSessionId_isDifferentPerInstance() throws Exception {
        WorkspaceManager other = new WorkspaceManager();
        other.initialize();

        String id1 = workspaceManager.getSessionId();
        String id2 = other.getSessionId();

        assertNotEquals(id1, id2, "Different instances should have different session IDs");
    }

    // ========== Project Creation Tests ==========

    @Test
    @DisplayName("createLinkedProject should create project in workspace")
    void createLinkedProject_createsProjectInWorkspace() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("test-project", fixturePath);

        assertNotNull(project, "Project should be created");
        assertTrue(project.exists(), "Project should exist in workspace");
        assertTrue(project.isOpen(), "Project should be open");
    }

    @Test
    @DisplayName("createLinkedProject should add Java nature")
    void createLinkedProject_addsJavaNature() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("java-test", fixturePath);

        assertTrue(project.hasNature(JavaCore.NATURE_ID),
            "Project should have Java nature");
    }

    @Test
    @DisplayName("createLinkedProject names each project it creates by the base name and a fresh 8-character hexadecimal suffix")
    void createLinkedProject_namesEachProjectFreshly() throws CoreException {
        IProject first = workspaceManager.createLinkedProject("my-project", fixturePath);
        IProject second = workspaceManager.createLinkedProject("my-project", fixturePath);

        // The suffix is random, so the names are pinned by their form and their difference.
        assertAll(
            () -> assertTrue(first.getName().matches("my-project-[0-9a-f]{8}"), "first name: " + first.getName()),
            () -> assertTrue(second.getName().matches("my-project-[0-9a-f]{8}"), "second name: " + second.getName()),
            () -> assertNotEquals(first.getName(), second.getName(), "two projects of one base name"));
    }

    @Test
    @DisplayName("createLinkedProject: IProjectDescription internal name matches IProject handle name")
    void createLinkedProject_descriptionNameMatchesProjectName() throws CoreException {
        // Regression test: before the fix, newProjectDescription was called with
        // the base `name` while the IProject handle used `name + sessionId`. JDT
        // sometimes consults the description's internal name, leading to
        // two-identity bugs. Description and handle must agree.
        IProject project = workspaceManager.createLinkedProject("desc-name-test", fixturePath);
        IProjectDescription description = project.getDescription();
        assertEquals(project.getName(), description.getName(),
            "Description's internal name must match the IProject handle's name");
    }

    @Test
    @DisplayName("createLinkedProject leaves every other project in place, whatever its name or creating session")
    void createLinkedProject_leavesEveryOtherProjectInPlace() throws CoreException {
        // Earlier projects go only once a load has completed (removeCreatedProjectsExcept),
        // never when the next project is created.
        WorkspaceManager earlierSession = new WorkspaceManager();
        earlierSession.initialize();
        IProject sameName = earlierSession.createLinkedProject("keep-test", fixturePath);
        IProject otherName = earlierSession.createLinkedProject("other-base", fixturePath);
        IProject sameSession = workspaceManager.createLinkedProject("keep-test", fixturePath);

        IProject created = workspaceManager.createLinkedProject("keep-test", fixturePath);

        assertAll(
            () -> assertTrue(created.exists(), "the new project must exist"),
            () -> assertTrue(sameName.exists(), "an earlier session's project of the same base name must stay"),
            () -> assertTrue(otherName.exists(), "a project of another base name must stay"),
            () -> assertTrue(sameSession.exists(), "this session's earlier project of the same base name must stay"),
            () -> assertEquals(4, Set.of(created.getName(), sameName.getName(), otherName.getName(),
                sameSession.getName()).size(), "four projects, four names"));
    }

    // ========== Linked Folder Tests ==========

    @Test
    @DisplayName("createLinkedFolder creates a link pointing at the requested external directory")
    void createLinkedFolder_createsLink() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("folder-test", fixturePath);
        Path srcPath = fixturePath.resolve("src/main/java");

        workspaceManager.createLinkedFolder(project, "linked-src", srcPath);

        var folder = project.getFolder("linked-src");
        assertTrue(folder.exists(), "Linked folder should exist");
        assertTrue(folder.isLinked(), "Folder should be linked");
        // Pin the link target — previously only existence + isLinked were checked; a
        // regression that linked to the wrong path (e.g. project root, or a sibling
        // directory) would have passed silently.
        Path actualTarget = Path.of(folder.getLocation().toOSString())
            .toAbsolutePath().normalize();
        Path expectedTarget = srcPath.toAbsolutePath().normalize();
        assertEquals(expectedTarget, actualTarget,
            "Linked folder's location must equal the requested external directory; "
                + "expected=" + expectedTarget + ", actual=" + actualTarget);
    }

    @Test
    @DisplayName("createLinkedFolder should skip non-existent paths")
    void createLinkedFolder_skipsNonExistentPaths() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("missing-folder-test", fixturePath);
        Path nonExistent = fixturePath.resolve("does-not-exist");

        // Should not throw
        workspaceManager.createLinkedFolder(project, "missing", nonExistent);

        var folder = project.getFolder("missing");
        assertFalse(folder.exists(), "Folder should not exist for non-existent external path");
    }

    // ========== Project Deletion Tests ==========

    @Test
    @DisplayName("deleteProject should remove project from workspace")
    void deleteProject_removesFromWorkspace() throws CoreException {
        String baseName = "delete-test";
        IProject project = workspaceManager.createLinkedProject(baseName, fixturePath);
        String fullName = project.getName();
        assertTrue(project.exists());

        workspaceManager.deleteProject(fullName);

        // Check via getProject - should return null after deletion
        IProject deleted = workspaceManager.getProject(fullName);
        assertNull(deleted, "Project should be null after deletion");
    }

    @Test
    @DisplayName("deleteProject should not throw for non-existent project")
    void deleteProject_noThrowForMissing() {
        // Should not throw exception
        assertDoesNotThrow(() -> workspaceManager.deleteProject("non-existent-project"));
    }

    // ========== Project Existence Tests ==========

    @Test
    @DisplayName("projectExists should return true for existing project")
    void projectExists_returnsTrueForExistingProject() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("exists-test", fixturePath);
        String fullName = project.getName();

        assertTrue(workspaceManager.projectExists(fullName),
            "Should return true for existing project");
    }

    @Test
    @DisplayName("projectExists should return false for missing project")
    void projectExists_returnsFalseForMissingProject() {
        assertFalse(workspaceManager.projectExists("definitely-not-exists"),
            "Should return false for non-existent project");
    }

    // ========== Project Retrieval Tests ==========

    @Test
    @DisplayName("getProject should return existing project")
    void getProject_returnsExistingProject() throws CoreException {
        IProject created = workspaceManager.createLinkedProject("get-test", fixturePath);
        String fullName = created.getName();

        IProject retrieved = workspaceManager.getProject(fullName);

        assertNotNull(retrieved, "Should return existing project");
        assertEquals(fullName, retrieved.getName());
    }

    @Test
    @DisplayName("getProject should return null for missing project")
    void getProject_returnsNullForMissing() {
        IProject project = workspaceManager.getProject("not-found-project");

        assertNull(project, "Should return null for non-existent project");
    }

    // ========== Refresh Tests ==========

    @Test
    @DisplayName("refresh should not throw")
    void refresh_doesNotThrow() throws CoreException {
        // Just verify refresh doesn't throw
        assertDoesNotThrow(() -> workspaceManager.refresh());
    }

    @Test
    @DisplayName("getProject returns null when called on a non-existent name (defensive guard)")
    void getProject_returnsNullForNonExistent() {
        // Already covered by getProject_returnsNullForMissing, but pin the implementation
        // contract: getProject(unknown).exists()==false is the underlying check; the
        // method translates that to null. Catches a regression where it returned the
        // empty IProject handle instead.
        IProject p = workspaceManager.getProject("never-created");
        assertNull(p);
    }

    // ========== Removing created projects ==========

    @Test
    @DisplayName("removeCreatedProjectsExcept deletes a project and its workspace directory while every file under its linked folders and linked file survives byte for byte")
    void removeCreatedProjectsExcept_linkedSourcesSurviveByteForByte() throws Exception {
        Path userTree = helper.getTempDirectory().resolve("user-tree");
        write(userTree.resolve("README.md"), "# user project\n".getBytes(StandardCharsets.UTF_8));
        write(userTree.resolve("src/main/java/com/acme/App.java"),
            "package com.acme;\n\nclass App {\n}\n".getBytes(StandardCharsets.UTF_8));
        write(userTree.resolve("src/main/java/com/acme/util/Strings.java"),
            "package com.acme.util;\n\nfinal class Strings {\n}\n".getBytes(StandardCharsets.UTF_8));
        write(userTree.resolve("data/notes.txt"), "kept\n".getBytes(StandardCharsets.UTF_8));
        write(userTree.resolve("data/blob.bin"), new byte[] {0, 1, 2, (byte) 0xFF});
        Map<String, String> expectedTree = new TreeMap<>(Map.of(
            "README.md", hex("# user project\n".getBytes(StandardCharsets.UTF_8)),
            "data", DIRECTORY,
            "data/blob.bin", "000102ff",
            "data/notes.txt", hex("kept\n".getBytes(StandardCharsets.UTF_8)),
            "src", DIRECTORY,
            "src/main", DIRECTORY,
            "src/main/java", DIRECTORY,
            "src/main/java/com", DIRECTORY,
            "src/main/java/com/acme", DIRECTORY,
            "src/main/java/com/acme/App.java", hex("package com.acme;\n\nclass App {\n}\n".getBytes(StandardCharsets.UTF_8))));
        expectedTree.put("src/main/java/com/acme/util", DIRECTORY);
        expectedTree.put("src/main/java/com/acme/util/Strings.java",
            hex("package com.acme.util;\n\nfinal class Strings {\n}\n".getBytes(StandardCharsets.UTF_8)));
        assertEquals(expectedTree, snapshot(userTree), "precondition: the user tree as written");

        IProject project = workspaceManager.createLinkedProject("removal-test", userTree);
        workspaceManager.createLinkedFolder(project, "src-0-src-main-java", userTree.resolve("src/main/java"));
        workspaceManager.createLinkedFolder(project, "data", userTree.resolve("data"));
        project.getFile("README.md").createLink(
            org.eclipse.core.runtime.Path.fromOSString(userTree.resolve("README.md").toString()),
            IResource.NONE, null);
        Path projectDirectory = Path.of(project.getLocation().toOSString());
        assertTrue(Files.isDirectory(projectDirectory), "precondition: the project's directory exists");
        IProject kept = workspaceManager.createLinkedProject("kept-test", fixturePath);

        workspaceManager.removeCreatedProjectsExcept(kept);

        assertAll(
            () -> assertFalse(project.exists(), "the created project must be removed"),
            () -> assertFalse(Files.exists(projectDirectory),
                "no directory for the project may remain under the workspace: " + projectDirectory),
            () -> assertEquals(expectedTree, snapshot(userTree), "the linked user tree must be unchanged"),
            () -> assertTrue(kept.exists(), "the excepted project must stay"));
    }

    @Test
    @DisplayName("removeCreatedProjectsExcept removes every other project createLinkedProject made, under any name or session, and keeps the excepted project and projects it did not create")
    void removeCreatedProjectsExcept_removesEveryOtherCreatedProjectOnly() throws Exception {
        WorkspaceManager earlierSession = new WorkspaceManager();
        earlierSession.initialize();
        IProject alpha = earlierSession.createLinkedProject("created-alpha", fixturePath);
        IProject beta = workspaceManager.createLinkedProject("created-beta", fixturePath);
        IProject kept = workspaceManager.createLinkedProject("kept-test", fixturePath);
        IProject other = workspaceManager.getRoot().getProject("not-created-by-javalens");
        other.create(null);
        other.open(null);
        try {
            workspaceManager.removeCreatedProjectsExcept(kept);

            assertAll(
                () -> assertFalse(alpha.exists(), "a project created by an earlier session must be removed"),
                () -> assertFalse(beta.exists(), "a project created by this session must be removed"),
                () -> assertTrue(kept.exists(), "the excepted project must stay"),
                () -> assertTrue(other.exists(), "a project createLinkedProject did not create must be kept"));
        } finally {
            other.delete(true, true, null);
        }
    }

    @Test
    @DisplayName("removeCreatedProjectsExcept reports a project it could not remove, with the reason, keeps it, and still removes the others")
    void removeCreatedProjectsExcept_reportsProjectItCouldNotRemove() throws Exception {
        IProject stuck = workspaceManager.createLinkedProject("stuck-test", fixturePath);
        IProject removable = workspaceManager.createLinkedProject("removable-test", fixturePath);
        IProject kept = workspaceManager.createLinkedProject("kept-test", fixturePath);
        WorkspaceManager refusing = new WorkspaceManager() {
            @Override
            public boolean removeProject(IProject project) throws CoreException {
                if (project.equals(stuck)) {
                    throw new CoreException(new Status(IStatus.ERROR, "org.javalens.core.tests",
                        "a file in its directory is locked"));
                }
                return super.removeProject(project);
            }
        };
        refusing.initialize();
        try {
            List<WorkspaceManager.KeptProject> notRemoved = refusing.removeCreatedProjectsExcept(kept);

            assertAll(
                () -> assertEquals(List.of(new WorkspaceManager.KeptProject(stuck.getName(),
                    "a file in its directory is locked")), notRemoved),
                () -> assertTrue(stuck.exists(), "the project that could not be removed stays"),
                () -> assertFalse(removable.exists(), "the other created project must still be removed"),
                () -> assertTrue(kept.exists(), "the excepted project must stay"));
        } finally {
            stuck.delete(true, true, null);
        }
    }

    @Test
    @DisplayName("removeCreatedProjectsExcept keeps a created project located outside the workspace directory, with its files")
    void removeCreatedProjectsExcept_keepsProjectLocatedOutsideWorkspace() throws Exception {
        Path outside = helper.getTempDirectory().resolve("outside-project");
        write(outside.resolve("keep.txt"), "outside\n".getBytes(StandardCharsets.UTF_8));
        IProject project = workspaceManager.getRoot().getProject("outside-location-test");
        IProjectDescription description = workspaceManager.getWorkspace().newProjectDescription(project.getName());
        description.setLocation(org.eclipse.core.runtime.Path.fromOSString(outside.toString()));
        project.create(description, null);
        project.open(null);
        project.setPersistentProperty(WorkspaceManager.CREATED_BY_JAVALENS, "earlier-session");
        Map<String, String> before = snapshot(outside);
        IProject kept = workspaceManager.createLinkedProject("kept-test", fixturePath);
        try {
            workspaceManager.removeCreatedProjectsExcept(kept);

            assertAll(
                () -> assertTrue(kept.exists(), "the excepted project must stay"),
                () -> assertTrue(project.exists(), "a project located outside the workspace must be kept"),
                () -> assertEquals(Set.of(".project", "keep.txt"), before.keySet(), "the project's files"),
                () -> assertEquals(before, snapshot(outside), "its files must be unchanged"));
        } finally {
            project.delete(false, true, null);
        }
    }

    private static final String DIRECTORY = "<directory>";

    private static void write(Path file, byte[] content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, content);
    }

    /** Every path under {@code root}, relative with '/' separators, to its content in hex or {@link #DIRECTORY}. */
    private static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> tree = new TreeMap<>();
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : (Iterable<Path>) paths::iterator) {
                if (path.equals(root)) continue;
                String relative = root.relativize(path).toString().replace('\\', '/');
                tree.put(relative, Files.isDirectory(path) ? DIRECTORY : hex(Files.readAllBytes(path)));
            }
        }
        return tree;
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
