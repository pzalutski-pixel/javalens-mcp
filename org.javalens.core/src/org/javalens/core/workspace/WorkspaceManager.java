package org.javalens.core.workspace;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.core.runtime.Status;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
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

    /** Marks each project {@link #createLinkedProject} creates, with the creating session's ID. */
    static final QualifiedName CREATED_BY_JAVALENS = new QualifiedName("org.javalens.core", "createdBy");

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
        // Append session ID to make project name unique per session. Other projects stay:
        // earlier loads' projects go only once a load has completed, through
        // removeCreatedProjectsExcept.
        String uniqueName = name + "-" + sessionId;

        IProject project = root.getProject(uniqueName);

        if (project.exists()) {
            log.info("Project {} already exists, deleting and recreating", uniqueName);
            removeProject(project);
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
        project.setPersistentProperty(CREATED_BY_JAVALENS, sessionId);

        // Fill the project's secondary-type cache while its index is empty. JDT indexes a
        // source file that contains lambdas or method references in two writes: its
        // declarations and references first, then, after resolving the file without holding
        // the index lock, the entries of its lambdas. Resolving the first such file of a project
        // fills this cache with a search of the project's index, and any query of an index
        // holding 100 or more pending document changes merges those changes to disk. A merge
        // between the two writes leaves the file with only its lambda entries, so its
        // declarations drop out of the index. With the cache filled before any source folder
        // is linked or the classpath is set, no search runs inside an indexing job and every
        // file keeps its declarations. Pinned by
        // IndexFidelityTest.indexedBeforeClasspath_aboveMergeThreshold_fullyIndexed.
        JavaModelManager.getJavaModelManager().secondaryTypes(JavaCore.create(project), false, null);

        log.info("Created project in workspace: {} (session: {})", uniqueName, sessionId);
        return project;
    }

    /**
     * Remove every open project {@link #createLinkedProject} created in this workspace, by
     * any WorkspaceManager, other than {@code kept}, through {@link #removeProject}. A project
     * whose removal fails stays, and the removal of the others goes on.
     *
     * @return the projects whose removal failed, in workspace order, each with the reason
     * @throws CoreException if a project's creation marker could not be read
     */
    public List<KeptProject> removeCreatedProjectsExcept(IProject kept) throws CoreException {
        List<KeptProject> notRemoved = new ArrayList<>();
        for (IProject project : root.getProjects()) {
            if (!project.equals(kept) && project.isOpen()
                    && project.getPersistentProperty(CREATED_BY_JAVALENS) != null) {
                try {
                    removeProject(project);
                } catch (CoreException e) {
                    log.warn("Could not remove project {}: {}", project.getName(), e.getMessage());
                    notRemoved.add(new KeptProject(project.getName(), e.getMessage()));
                }
            }
        }
        return notRemoved;
    }

    /** A project {@link #removeCreatedProjectsExcept} could not remove, by name, and why. */
    public record KeptProject(String name, String reason) {
    }

    /**
     * Remove a project whose directory lies inside the workspace directory: delete each of its
     * linked resources first, which removes the link and never its target, then delete the
     * project with its content, so its directory under the workspace goes too. A project
     * located anywhere else is kept and logged.
     *
     * @return true if the project was removed, false if it was kept
     * @throws CoreException if a link or the project could not be deleted, or a link remains
     */
    public boolean removeProject(IProject project) throws CoreException {
        java.nio.file.Path workspaceDirectory = absolute(root.getLocation());
        IPath location = project.getLocation();
        java.nio.file.Path projectDirectory = location == null ? null : absolute(location);
        if (projectDirectory == null || projectDirectory.equals(workspaceDirectory)
                || !projectDirectory.startsWith(workspaceDirectory)) {
            log.warn("Keeping project {}: its location {} is not inside the workspace directory {}",
                project.getName(), location, workspaceDirectory);
            return false;
        }
        for (IResource link : linkedResources(project)) {
            link.delete(IResource.NONE, new NullProgressMonitor());
        }
        List<IResource> remaining = linkedResources(project);
        if (!remaining.isEmpty()) {
            throw new CoreException(new Status(IStatus.ERROR, "org.javalens.core",
                "Project " + project.getName() + " is kept: its linked resources " + remaining
                    + " could not be removed"));
        }
        project.delete(true, true, new NullProgressMonitor());
        log.info("Removed project {}", project.getName());
        return true;
    }

    /** The project's linked resources at any depth outside other links, hidden ones included. */
    private static List<IResource> linkedResources(IProject project) throws CoreException {
        List<IResource> links = new ArrayList<>();
        project.accept(proxy -> {
            if (proxy.isLinked()) {
                links.add(proxy.requestResource());
                return false;
            }
            return true;
        }, IContainer.INCLUDE_HIDDEN | IContainer.INCLUDE_TEAM_PRIVATE_MEMBERS);
        return links;
    }

    private static java.nio.file.Path absolute(IPath location) {
        return java.nio.file.Path.of(location.toOSString()).toAbsolutePath().normalize();
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
