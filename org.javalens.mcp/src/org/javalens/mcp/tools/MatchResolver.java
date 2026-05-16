package org.javalens.mcp.tools;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.TypeReferenceMatch;

/**
 * Resolves the {@link ICompilationUnit} that owns a {@link SearchMatch}. JDT
 * returns multiple {@link SearchMatch} subclasses whose {@code getElement()}
 * shapes differ, so callers historically replicated a fallback chain. This
 * class centralizes the chain.
 *
 * <p>Precedence (first non-null wins):
 * <ol>
 *   <li>{@link IType#getCompilationUnit()} when the element is an {@link IType}</li>
 *   <li>{@link IMember#getCompilationUnit()} when the element is an {@link IMember}</li>
 *   <li>{@link IJavaElement#getAncestor(int)} for any other {@link IJavaElement}</li>
 *   <li>For {@link TypeReferenceMatch}, the same chain re-applied to
 *       {@link TypeReferenceMatch#getLocalElement()}</li>
 *   <li>{@link JavaCore#create(IFile)} on the match's resource (last-resort
 *       fallback for edge cases where the element is detached)</li>
 * </ol>
 *
 * <p>Never throws — returns {@code null} when no fallback succeeds.
 */
public final class MatchResolver {

    private MatchResolver() {}

    public static ICompilationUnit resolveCu(SearchMatch match) {
        if (match == null) return null;
        ICompilationUnit cu = fromElement(match.getElement());
        if (cu != null) return cu;
        if (match instanceof TypeReferenceMatch typeRefMatch) {
            cu = fromTypeReferenceLocalElement(typeRefMatch);
            if (cu != null) return cu;
        }
        return fromIFile(match.getResource());
    }

    static ICompilationUnit fromElement(Object element) {
        if (element instanceof IType type) return type.getCompilationUnit();
        if (element instanceof IMember member) return member.getCompilationUnit();
        if (element instanceof IJavaElement je) {
            return (ICompilationUnit) je.getAncestor(IJavaElement.COMPILATION_UNIT);
        }
        return null;
    }

    static ICompilationUnit fromTypeReferenceLocalElement(TypeReferenceMatch match) {
        IJavaElement local = match.getLocalElement();
        if (local instanceof IMember member) return member.getCompilationUnit();
        if (local != null) {
            return (ICompilationUnit) local.getAncestor(IJavaElement.COMPILATION_UNIT);
        }
        return null;
    }

    static ICompilationUnit fromIFile(Object resource) {
        if (resource instanceof IFile file
            && "java".equalsIgnoreCase(file.getFileExtension())) {
            IJavaElement je = JavaCore.create(file);
            if (je instanceof ICompilationUnit cu) return cu;
        }
        return null;
    }
}
