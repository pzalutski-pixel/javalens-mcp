package org.javalens.core.workspace;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.JavaCore;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

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
    @DisplayName("createLinkedProject should append session ID to name")
    void createLinkedProject_appendsSessionId() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("my-project", fixturePath);

        String expectedName = "my-project-" + workspaceManager.getSessionId();
        assertEquals(expectedName, project.getName(),
            "Project name should include session ID");
    }

    @Test
    @DisplayName("createLinkedProject should recreate if already exists")
    void createLinkedProject_recreatesIfExists() throws CoreException {
        // Create first time
        IProject project1 = workspaceManager.createLinkedProject("recreate-test", fixturePath);
        assertTrue(project1.exists());

        // Create second time - should succeed (recreates)
        IProject project2 = workspaceManager.createLinkedProject("recreate-test", fixturePath);
        assertTrue(project2.exists());
    }

    // ========== Linked Folder Tests ==========

    @Test
    @DisplayName("createLinkedFolder should create link to external directory")
    void createLinkedFolder_createsLink() throws CoreException {
        IProject project = workspaceManager.createLinkedProject("folder-test", fixturePath);
        Path srcPath = fixturePath.resolve("src/main/java");

        workspaceManager.createLinkedFolder(project, "linked-src", srcPath);

        var folder = project.getFolder("linked-src");
        assertTrue(folder.exists(), "Linked folder should exist");
        assertTrue(folder.isLinked(), "Folder should be linked");
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
}
