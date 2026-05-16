package org.javalens.core.workspace;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.JavaCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Manages Eclipse workspace programmatically for headless operation.
 * Creates and configures projects without requiring Eclipse IDE.
 *
 * Each session gets a unique UUID-based project name to support
 * multiple concurrent sessions without conflicts.
 */
public class WorkspaceManager {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private IWorkspace workspace;
    private IWorkspaceRoot root;
    private final String sessionId;

    public WorkspaceManager() {
        // Generate unique session ID for this instance
        this.sessionId = UUID.randomUUID().toString().substring(0, 8);
        log.debug("WorkspaceManager session ID: {}", sessionId);
    }

    /**
     * Initialize the workspace manager.
     * Must be called before any other operations.
     * Waits for workspace to be ready if needed.
     */
    private static final int WORKSPACE_INIT_MAX_RETRIES = 10;
    private static final long WORKSPACE_INIT_RETRY_INTERVAL_MS = 100L;

    public void initialize() throws CoreException {
        // ResourcesPlugin.getWorkspace() can throw IllegalStateException during OSGi
        // startup if the resources bundle hasn't fully activated. Bounded poll with
        // total wait ≤ 1s (was 5s in 1.3.x — slower than needed).
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= WORKSPACE_INIT_MAX_RETRIES; attempt++) {
            try {
                this.workspace = ResourcesPlugin.getWorkspace();
                this.root = workspace.getRoot();
                log.info("Workspace initialized at: {} (attempt {})", root.getLocation(), attempt);
                return;
            } catch (IllegalStateException e) {
                last = e;
                if (attempt == WORKSPACE_INIT_MAX_RETRIES) break;
                log.debug("Workspace not ready (attempt {}/{}): {}",
                    attempt, WORKSPACE_INIT_MAX_RETRIES, e.getMessage());
                try {
                    Thread.sleep(WORKSPACE_INIT_RETRY_INTERVAL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CoreException(new org.eclipse.core.runtime.Status(
                        org.eclipse.core.runtime.IStatus.ERROR,
                        "org.javalens.core",
                        "Interrupted while waiting for OSGi resources bundle to start"
                    ));
                }
            }
        }
        long totalWaitMs = (long) WORKSPACE_INIT_MAX_RETRIES * WORKSPACE_INIT_RETRY_INTERVAL_MS;
        throw new CoreException(new org.eclipse.core.runtime.Status(
            org.eclipse.core.runtime.IStatus.ERROR,
            "org.javalens.core",
            "Eclipse workspace not available after " + WORKSPACE_INIT_MAX_RETRIES
                + " attempts (" + totalWaitMs + "ms total). The OSGi resources bundle "
                + "(org.eclipse.core.resources) appears not to have started — check the "
                + "product configuration. Last error: " + (last == null ? "<none>" : last.getMessage())
        ));
    }

    /**
     * Get the workspace.
     */
    public IWorkspace getWorkspace() {
        return workspace;
    }

    /**
     * Get the workspace root.
     */
    public IWorkspaceRoot getRoot() {
        return root;
    }

    /**
     * Get the session ID for this workspace manager instance.
     */
    public String getSessionId() {
        return sessionId;
    }

    /**
     * Create a project in the workspace with linked source folders.
     * This approach keeps all Eclipse metadata (.project, .classpath) inside the workspace,
     * not polluting the user's actual project directory.
     *
     * The project name includes a session UUID to support multiple concurrent sessions.
     *
     * @param name Base project name (session ID will be appended)
     * @param externalProjectRoot External filesystem path to the project root
     * @return The created/opened project
     * @throws CoreException if project creation fails
     */
    public IProject createLinkedProject(String name, java.nio.file.Path externalProjectRoot) throws CoreException {
        // Append session ID to make project name unique per session
        String uniqueName = name + "-" + sessionId;
        IProject project = root.getProject(uniqueName);

        if (project.exists()) {
            log.info("Project {} already exists, deleting and recreating", uniqueName);
            project.delete(true, true, new NullProgressMonitor());
        }

        // Create project description WITHOUT external location, using the same
        // unique name as the IProject handle. Passing the base `name` here used
        // to make the description's internal name diverge from the project's
        // actual name — JDT then consults description in some code paths and
        // sees a different identity than the workspace root reports.
        IProjectDescription description = workspace.newProjectDescription(uniqueName);

        // Add Java nature
        description.setNatureIds(new String[] { JavaCore.NATURE_ID });

        // Create and open the project (inside workspace)
        project.create(description, new NullProgressMonitor());
        project.open(new NullProgressMonitor());

        log.info("Created project in workspace: {} (session: {})", uniqueName, sessionId);
        return project;
    }

    /**
     * Create a linked folder in the project pointing to an external directory.
     *
     * @param project The project
     * @param folderName Name for the linked folder in the project
     * @param externalPath External filesystem path
     * @throws CoreException if link creation fails
     */
    public void createLinkedFolder(IProject project, String folderName, java.nio.file.Path externalPath) throws CoreException {
        if (!java.nio.file.Files.exists(externalPath)) {
            log.debug("External path does not exist, skipping: {}", externalPath);
            return;
        }

        org.eclipse.core.resources.IFolder folder = project.getFolder(folderName);
        if (folder.exists()) {
            folder.delete(true, new NullProgressMonitor());
        }

        IPath linkPath = new Path(externalPath.toAbsolutePath().toString());
        folder.createLink(linkPath, org.eclipse.core.resources.IResource.NONE, new NullProgressMonitor());
        log.debug("Created linked folder: {} -> {}", folderName, externalPath);
    }

    /**
     * Delete a project from the workspace.
     * Does NOT delete the actual files on disk.
     *
     * @param name Project name
     */
    public void deleteProject(String name) {
        IProject project = root.getProject(name);
        if (project.exists()) {
            try {
                // false = don't delete contents on disk
                project.delete(false, true, new NullProgressMonitor());
                log.info("Deleted project: {}", name);
            } catch (CoreException e) {
                log.warn("Failed to delete project {}: {}", name, e.getMessage());
            }
        }
    }

    /**
     * Get an existing project by name.
     *
     * @param name Project name
     * @return The project, or null if not found
     */
    public IProject getProject(String name) {
        IProject project = root.getProject(name);
        return project.exists() ? project : null;
    }

    /**
     * Check if a project exists.
     */
    public boolean projectExists(String name) {
        return root.getProject(name).exists();
    }

    /**
     * Refresh the workspace to pick up external changes.
     */
    public void refresh() throws CoreException {
        root.refreshLocal(IWorkspaceRoot.DEPTH_INFINITE, new NullProgressMonitor());
    }
}
