package org.javalens.core;

import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.index.Index;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies that JDT holds a searchable index for every library on a project's classpath, and
 * has JDT rebuild the indexes it does not.
 *
 * <p>JDT can leave a library with an index no search reads. A classpath change that adds a
 * library JDT holds no timestamp for (the first load of a session, or any library new to the
 * session) requests the library's indexing job, then, in the same resource delta, finds the
 * library new and removes its index (DeltaProcessor :1061-1069) before requesting the job
 * again. The job may already be running: AddJrtToIndex creates its index (:174), takes its
 * write lock (:192) and walks the JRE image to compare it with the index (:228-262), then
 * resets the index cached for the library (:266); AddJarFileToIndex does the same for a jar
 * (:110, :130, :172-211, :216). IndexManager.removeIndex (:918-950) drops the job's index from
 * the cache and clears its monitor, so resetIndex finds nothing cached (:1095-1097) and calls
 * recreateIndex (:883-904), which caches a new empty index whose monitor is copied from the
 * index that is no longer cached: null. The job fills its own index, which saveIndex then
 * skips for its null monitor (:1128-1129). Every search selects the cached empty index and
 * PatternSearchJob.search skips it for its null monitor, reporting the search complete
 * (:220-221), and every later indexing request for the library finds an index cached and does
 * nothing (AddJrtToIndex :167-172, AddJarFileToIndex :103-108): the library's types are
 * missing from every search for the rest of the session.
 *
 * <p>A library is searchable when JDT caches an index for it with a monitor. Once the load's
 * index barrier has run, JDT has built or read the index of every library a search can reach,
 * so a library whose target exists and has no cached index is one JDT could not index.
 */
class LibraryIndexVerifier {

    private final IJavaProject project;

    LibraryIndexVerifier(IJavaProject project) {
        this.project = project;
    }

    /**
     * The libraries on the project's resolved classpath whose target exists and for which JDT
     * holds no searchable index, by their classpath path, in classpath order.
     *
     * @throws JavaModelException if the project's classpath cannot be resolved
     */
    List<IPath> findUnsearchable() throws JavaModelException {
        IndexManager indexManager = JavaModelManager.getIndexManager();
        List<IPath> unsearchable = new ArrayList<>();
        for (IClasspathEntry entry : project.getResolvedClasspath(true)) {
            if (entry.getEntryKind() != IClasspathEntry.CPE_LIBRARY
                    || JavaModel.getTarget(entry.getPath(), true) == null) {
                continue;
            }
            Index index = indexManager.getIndex(indexManager.computeIndexLocation(indexedPath(entry.getPath())));
            if (index == null || index.monitor == null) {
                unsearchable.add(entry.getPath());
            }
        }
        return unsearchable;
    }

    /**
     * Drop the index JDT holds for each of {@code libraries}, with its file and state, so the
     * next search has JDT build it again (IndexManager.getIndex :439-443) and waits for it.
     */
    void dropIndexes(List<IPath> libraries) {
        IndexManager indexManager = JavaModelManager.getIndexManager();
        for (IPath library : libraries) {
            indexManager.removeIndex(indexedPath(library));
        }
    }

    /**
     * The path JDT keys a library's index by: the workspace folder that stands for an external
     * class folder, as JDT's IndexSelector maps it, or else the library's own path.
     */
    private static IPath indexedPath(IPath library) {
        return JavaModel.getTarget(library, false) instanceof IFolder folder ? folder.getFullPath() : library;
    }
}
