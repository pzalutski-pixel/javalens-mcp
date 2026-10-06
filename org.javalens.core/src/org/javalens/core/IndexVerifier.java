package org.javalens.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameRequestor;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.search.indexing.IndexManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Verifies the search index against the compilation units of a project and re-indexes the
 * units it finds incomplete.
 *
 * <p>JDT can leave a source file in the index with only part of its entries. It indexes a file
 * holding lambdas or method references in two writes, the file's declarations and references
 * first and the entries of its lambdas after resolving the file, and a merge of the in-memory
 * index between the two writes keeps only the second. Every index-backed search then misses the
 * file's types and references while reporting success. A unit is complete when every top-level
 * type it declares is indexed for its document; the type named after the file is checked first,
 * from the index alone, and only a unit without it is opened to compare its declared types.
 */
class IndexVerifier {

    private static final Logger log = LoggerFactory.getLogger(IndexVerifier.class);

    private final IJavaProject project;

    IndexVerifier(IJavaProject project) {
        this.project = project;
    }

    /**
     * Verify {@code units} and, when {@code repair} is set, re-index the incomplete ones and
     * verify those again.
     *
     * @return the units still incomplete, in {@code units} order
     * @throws CoreException if the index cannot be searched, or a unit whose file exists cannot
     *         be read
     */
    List<ICompilationUnit> verifyAndRepair(Collection<ICompilationUnit> units, boolean repair) throws CoreException {
        List<ICompilationUnit> gaps = findGaps(units);
        if (gaps.isEmpty() || !repair) {
            return gaps;
        }
        log.warn("The search index lacks declarations from {} file(s); re-indexing {}",
            gaps.size(), gaps.stream().map(ICompilationUnit::getPath).toList());
        reindex(gaps);
        return findGaps(gaps);
    }

    /**
     * The units among {@code units} whose document lacks a top-level type the unit declares.
     * The index is read once, after the indexer has run every job queued so far, and not at
     * all for no units. JDT writes a document's declarations in one pass under the index's
     * write lock, so the type named after the file is indexed exactly when the others are, and
     * finding it settles a unit without opening it. Any other unit is complete when every type
     * it declares is indexed for its document; a unit that declares no type, such as
     * {@code package-info.java}, is complete. A unit whose file was deleted after the units
     * were listed is no longer part of the project and is skipped: neither complete nor a gap.
     *
     * @return the incomplete units, in {@code units} order
     * @throws CoreException if the index cannot be searched, or a unit whose file exists cannot
     *         be read
     */
    List<ICompilationUnit> findGaps(Collection<ICompilationUnit> units) throws CoreException {
        if (units.isEmpty()) {
            return List.of();
        }
        Map<String, Set<String>> indexed = indexedTopLevelTypes();
        List<ICompilationUnit> gaps = new ArrayList<>();
        for (ICompilationUnit unit : units) {
            Set<String> indexedNames = indexed.getOrDefault(unit.getPath().toString(), Set.of());
            if (indexedNames.contains(JavaCore.removeJavaLikeExtension(unit.getElementName()))) {
                continue;
            }
            Optional<Set<String>> declared = declaredTypeNames(unit);
            if (declared.isPresent() && !indexedNames.containsAll(declared.get())) {
                gaps.add(unit);
            }
        }
        return gaps;
    }

    /**
     * The simple names of the top-level types {@code unit} declares, or empty when reading the
     * unit fails because its file no longer exists. JDT reports every failure to read a file
     * as a missing element, so only a file confirmed absent from disk counts as deleted; any
     * other failure propagates.
     */
    private Optional<Set<String>> declaredTypeNames(ICompilationUnit unit) throws JavaModelException {
        IType[] types;
        try {
            types = declaredTypes(unit);
        } catch (JavaModelException e) {
            if (!isDeletedFromDisk(unit)) {
                throw e;
            }
            log.debug("Skipping {} in the index verification: its file no longer exists", unit.getPath());
            return Optional.empty();
        }
        Set<String> names = new HashSet<>();
        for (IType type : types) {
            names.add(type.getElementName());
        }
        return Optional.of(names);
    }

    /** Whether {@code unit}'s file is confirmed absent from disk. */
    private static boolean isDeletedFromDisk(ICompilationUnit unit) {
        IResource resource = unit.getResource();
        IPath location = resource == null ? null : resource.getLocation();
        return location != null && Files.notExists(Path.of(location.toOSString()));
    }

    /**
     * The top-level types {@code unit} declares, read from its file when the unit is not open.
     * Package-private so a test can act on the file just before it is read.
     */
    IType[] declaredTypes(ICompilationUnit unit) throws JavaModelException {
        return unit.getTypes();
    }

    /**
     * The simple names of the top-level types the index holds for each document of the
     * project's source folders, by document path. A member type is reported with its enclosing
     * types, and a lambda's or local type's entry with JDT's local-type mark, so neither counts
     * as a top-level type here.
     */
    private Map<String, Set<String>> indexedTopLevelTypes() throws JavaModelException {
        Map<String, Set<String>> byDocument = new HashMap<>();
        new SearchEngine().searchAllTypeNames(
            null, SearchPattern.R_EXACT_MATCH,
            null, SearchPattern.R_EXACT_MATCH,
            IJavaSearchConstants.TYPE,
            SearchEngine.createJavaSearchScope(new IJavaElement[] { project }, IJavaSearchScope.SOURCES),
            new TypeNameRequestor() {
                @Override
                public void acceptType(int modifiers, char[] packageName, char[] simpleTypeName,
                                       char[][] enclosingTypeNames, String path) {
                    if (enclosingTypeNames.length == 0) {
                        byDocument.computeIfAbsent(path, document -> new HashSet<>()).add(new String(simpleTypeName));
                    }
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
            new NullProgressMonitor());
        return byDocument;
    }

    /**
     * Re-index each unit's document from its file, as JDT does when a file changes: the
     * document's entries are removed and written again from the file's current content.
     * Nothing is written to the file. The indexer runs the requests in the background; the
     * next {@link #findGaps} waits for them.
     */
    private void reindex(Collection<ICompilationUnit> units) {
        IndexManager indexManager = JavaModelManager.getIndexManager();
        // One parser for every document, as JDT's delta processing does. Its type lives in JDT's
        // compiler package, which this bundle does not import, so it is not named here.
        var parser = indexManager.getSourceElementParser(project, null);
        for (ICompilationUnit unit : units) {
            IFile file = (IFile) unit.getResource();
            indexManager.addSource(file, file.getProject().getFullPath(), parser);
        }
    }
}
