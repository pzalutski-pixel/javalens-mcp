package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.javalens.core.exceptions.ReloadRequiredException;
import org.javalens.core.graph.ProjectGraphService;
import org.javalens.core.project.model.BuildModel;
import org.javalens.core.project.model.LoadWarning;
import org.javalens.core.search.SearchService;
import org.javalens.core.sync.DiskSyncMode;

import java.nio.file.Path;
import java.util.List;

/**
 * Service interface for JDT operations.
 * Provides semantic code analysis capabilities through Eclipse JDT.
 *
 * <p>This interface defines the contract for JDT-based analysis.
 * Implementations wrap the Eclipse JDT SearchEngine and related APIs.
 */
public interface IJdtService {

    /**
     * Get path utilities for the loaded project.
     *
     * @return Path utilities instance
     */
    IPathUtils getPathUtils();

    /**
     * Get the project root path.
     *
     * @return Absolute path to the project root
     */
    Path getProjectRoot();

    /**
     * Get the time limit on each tool call, as {@link TimeoutSetting} reads it from
     * {@code JAVALENS_TIMEOUT_SECONDS}.
     *
     * @return the limit in seconds, from 5 to 300; null when no limit is set
     */
    Integer getTimeoutSeconds();

    /**
     * Start the deadline of one tool call: the configured limit from now.
     *
     * @return the deadline, or null when no limit is set
     */
    Deadline newDeadline();

    /**
     * Get the search service for indexed queries.
     *
     * @return SearchService instance
     */
    SearchService getSearchService();

    /**
     * Get the whole-project graph service for reachability and
     * transitive-impact queries. The graph builds lazily on first use.
     *
     * @return ProjectGraphService instance
     */
    ProjectGraphService getProjectGraphService();

    /**
     * Verify the loaded model against disk and repair exactly what changed
     * (the #26 contract: every answer is true of disk at query time). A no-op
     * under {@link DiskSyncMode#MANUAL}.
     *
     * @return the repaired paths; empty when nothing had changed
     * @throws ReloadRequiredException when build files changed - only a full
     *         load_project restores a trustworthy model
     * @throws java.io.IOException when verification itself fails - callers
     *         must surface this, never answer unverified
     * @throws org.eclipse.core.runtime.CoreException when the workspace
     *         refresh fails
     */
    List<java.nio.file.Path> ensureFresh()
        throws java.io.IOException, org.eclipse.core.runtime.CoreException, ReloadRequiredException;

    /**
     * The active disk-sync integrity mode.
     */
    DiskSyncMode getDiskSyncMode();

    /**
     * Get the underlying Java project.
     *
     * @return IJavaProject instance
     */
    IJavaProject getJavaProject();

    /**
     * Get the ICompilationUnit read from exactly the file at a path. The file maps to the
     * linked folder of the source root whose location holds it, the innermost when roots are
     * nested, so a type of the same qualified name elsewhere never answers for it.
     *
     * @param filePath Path to the source file (absolute or relative to project root)
     * @return the file's ICompilationUnit, or null when no source root holds the file or the
     *         file does not exist
     */
    ICompilationUnit getCompilationUnit(Path filePath);

    /**
     * Get the IJavaElement at a specific position in a file.
     * Uses zero-based line and column numbers.
     *
     * <p>A position on a name is the element the name denotes; a name written as a reference
     * that the compiler cannot resolve denotes none, and the answer is null. A position on no
     * name (indentation, a keyword) is the element enclosing it.
     *
     * @param filePath Path to the source file
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return IJavaElement at position, or null if not found
     * @throws IllegalStateException when JDT cannot read the file's compilation unit
     */
    IJavaElement getElementAtPosition(Path filePath, int line, int column);

    /**
     * Get the IType at a specific position in a file.
     *
     * @param filePath Path to the source file
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return IType at position, or null if not a type
     */
    IType getTypeAtPosition(Path filePath, int line, int column);

    /**
     * Find a type by its fully qualified or simple name. A member type is named with '.' or '$'
     * before its own name ({@code pkg.Outer.Inner}, {@code pkg.Outer$Inner}, the form every tool
     * reports it in); a local or anonymous class by the Java model's '$' form
     * ({@code pkg.Outer$Local}, {@code pkg.Outer$1}), which names the member type when a member
     * type carries it too, and no type when two local classes of the unit share it.
     *
     * @param typeName Type name (e.g., "String" or "java.lang.String")
     * @return IType or null if not found
     * @throws IllegalStateException when JDT cannot read the project's types
     */
    IType findType(String typeName);

    /**
     * Get source line content at a specific position.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return The source line content
     * @throws IllegalStateException when JDT cannot read the unit's source
     */
    String getContextLine(ICompilationUnit cu, int offset);

    /**
     * Convert zero-based line/column to character offset.
     *
     * @param cu Compilation unit
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return Character offset in the source
     * @throws IllegalStateException when JDT cannot read the unit's source
     */
    int getOffset(ICompilationUnit cu, int line, int column);

    /**
     * Convert character offset to zero-based line number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based line number
     * @throws IllegalStateException when JDT cannot read the unit's source
     */
    int getLineNumber(ICompilationUnit cu, int offset);

    /**
     * Convert character offset to zero-based column number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based column number
     * @throws IllegalStateException when JDT cannot read the unit's source
     */
    int getColumnNumber(ICompilationUnit cu, int offset);

    /**
     * Get all Java source files in the project.
     *
     * @return List of source file paths
     * @throws IllegalStateException when JDT cannot read the project's source folders
     */
    List<Path> getAllJavaFiles();

    /**
     * Get warnings accumulated during the most recent {@code loadProject} call.
     *
     * <p>Warnings are surfaced when a build-tool subprocess fails, when expected directories
     * (generated sources, bazel-bin) are missing, or when project metadata is incomplete.
     * MCP clients should display these to the user so degraded analysis is visible rather
     * than silent.
     *
     * @return immutable list of warnings; empty if no issues were detected
     */
    default List<LoadWarning> getWarnings() {
        return List.of();
    }

    /**
     * Get the source files whose declarations the search index is missing, as last verified.
     * The index is verified against every compilation unit when a project loads and against
     * each file a disk-sync repair re-reads; a file found incomplete is re-indexed and verified
     * again, and is listed only if it is still incomplete. While a file is listed, search-based
     * answers do not include its types or references.
     *
     * @return the files relative to the project root with '/' separators, sorted; empty when
     *         the index holds every top-level type of the verified files
     */
    default List<String> getIndexIncompleteFiles() {
        return List.of();
    }

    /**
     * Get the libraries on the classpath whose search index JDT could not build at the last
     * load, even after rebuilding it, as LIBRARY_INDEX_INCOMPLETE names them. While a library is
     * listed, search-based answers do not include its types.
     *
     * @return the libraries, one under the project root relative to it and any other by its
     *         absolute path, with '/' separators, sorted; empty when every library is searchable
     */
    default List<String> getLibraryIndexIncomplete() {
        return List.of();
    }

    /**
     * Get the build model resolved by the most recent {@code loadProject} call: the build
     * system, where the project's structure came from, and its modules.
     *
     * @return the resolved model, or {@code null} before a project is loaded
     */
    BuildModel getBuildModel();

    /**
     * Get the Java level the loaded project is analyzed at: the compiler source level applied
     * to the project. That is the model's level, or the running JVM's level when the model
     * declares none, clamped into the range JDT supports.
     *
     * @return the applied level, or {@code null} before a project is loaded
     */
    String getAnalysisLevel();
}
