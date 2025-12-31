package org.javalens.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.javalens.core.search.SearchService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

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
     * Get the configured timeout in seconds.
     *
     * @return Timeout value (default 30, range 5-300)
     */
    int getTimeoutSeconds();

    /**
     * Execute an operation with timeout protection.
     * Prevents long-running operations from blocking the server.
     *
     * @param operation The operation to execute
     * @param operationName Name for error messages
     * @param <T> Return type
     * @return The operation result
     * @throws RuntimeException if timeout, interrupted, or operation fails
     */
    <T> T executeWithTimeout(Callable<T> operation, String operationName);

    /**
     * Get the search service for indexed queries.
     *
     * @return SearchService instance
     */
    SearchService getSearchService();

    /**
     * Get the underlying Java project.
     *
     * @return IJavaProject instance
     */
    IJavaProject getJavaProject();

    /**
     * Get the ICompilationUnit for a file path.
     *
     * @param filePath Path to the source file (absolute or relative to project root)
     * @return ICompilationUnit or null if not found
     */
    ICompilationUnit getCompilationUnit(Path filePath);

    /**
     * Get the IJavaElement at a specific position in a file.
     * Uses zero-based line and column numbers.
     *
     * @param filePath Path to the source file
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return IJavaElement at position, or null if not found
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
     * Find a type by its fully qualified or simple name.
     *
     * @param typeName Type name (e.g., "String" or "java.lang.String")
     * @return IType or null if not found
     */
    IType findType(String typeName);

    /**
     * Get source line content at a specific position.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return The source line content
     */
    String getContextLine(ICompilationUnit cu, int offset);

    /**
     * Convert zero-based line/column to character offset.
     *
     * @param cu Compilation unit
     * @param line Zero-based line number
     * @param column Zero-based column number
     * @return Character offset in the source
     */
    int getOffset(ICompilationUnit cu, int line, int column);

    /**
     * Convert character offset to zero-based line number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based line number
     */
    int getLineNumber(ICompilationUnit cu, int offset);

    /**
     * Convert character offset to zero-based column number.
     *
     * @param cu Compilation unit
     * @param offset Character offset
     * @return Zero-based column number
     */
    int getColumnNumber(ICompilationUnit cu, int offset);

    /**
     * Get all Java source files in the project.
     *
     * @return List of source file paths
     */
    List<Path> getAllJavaFiles();
}
