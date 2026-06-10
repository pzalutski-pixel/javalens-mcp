package org.javalens.core.graph;

import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Lazily builds and caches the {@link ProjectGraph} for a loaded project.
 *
 * <p>The graph is built on the first {@link #getGraph()} call and reused until
 * {@link #invalidate()}. A fresh service is created per {@code loadProject},
 * so a project reload always observes current sources.
 */
public final class ProjectGraphService {

    private final IJavaProject project;
    private ProjectGraph graph;

    public ProjectGraphService(IJavaProject project) {
        this.project = project;
    }

    public synchronized ProjectGraph getGraph() throws JavaModelException {
        if (graph == null) {
            graph = GraphBuilder.build(project);
        }
        return graph;
    }

    public synchronized void invalidate() {
        graph = null;
    }
}
