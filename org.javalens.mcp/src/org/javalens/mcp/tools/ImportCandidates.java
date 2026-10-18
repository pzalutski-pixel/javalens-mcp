package org.javalens.mcp.tools;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IModuleDescription;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IModuleBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jdt.core.search.TypeNameMatchRequestor;
import org.javalens.core.Deadline;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The types a single-type import in the project can name for a simple name (JLS 7.5.1), from
 * the project's sources, its libraries and the JDK, in search order. A type is one when:
 *
 * <ul>
 *   <li>it has a package: a type in the default package cannot be imported;</li>
 *   <li>the compiler resolves it from the project; and</li>
 *   <li>its module, when named, is the project's own or exports its package to the project's
 *       module (JLS 7.7.2): a package a module does not export, or exports only to other
 *       modules, is not accessible outside them, as for the JDK's internal packages.</li>
 * </ul>
 *
 * <p>What a package is named never decides: an exported {@code com.sun.} or {@code sun.} package
 * is importable, and so is a project's or library's {@code .internal.} or {@code .impl.} package.
 */
final class ImportCandidates {

    private ImportCandidates() {
    }

    /** Every type named {@code simpleName} that the project can import. */
    static List<IType> of(IJavaProject project, String simpleName) throws CoreException {
        IJavaSearchScope scope = SearchEngine.createJavaSearchScope(
            new IJavaElement[] { project },
            IJavaSearchScope.SOURCES |
            IJavaSearchScope.APPLICATION_LIBRARIES |
            IJavaSearchScope.SYSTEM_LIBRARIES
        );
        List<IType> matched = new ArrayList<>();
        new SearchEngine().searchAllTypeNames(
            null,  // any package
            SearchPattern.R_EXACT_MATCH,
            simpleName.toCharArray(),
            SearchPattern.R_EXACT_MATCH | SearchPattern.R_CASE_SENSITIVE,
            IJavaSearchConstants.TYPE,
            scope,
            new TypeNameMatchRequestor() {
                @Override
                public void acceptTypeNameMatch(TypeNameMatch match) {
                    if (!match.getPackageName().isEmpty()) {
                        matched.add(match.getType());
                    }
                }
            },
            IJavaSearchConstants.WAIT_UNTIL_READY_TO_SEARCH,
            Deadline.monitor()
        );
        if (matched.isEmpty()) {
            return List.of();
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setProject(project);
        IBinding[] bindings = parser.createBindings(matched.toArray(new IJavaElement[0]), Deadline.monitor());
        IModuleDescription ownModule = project.getModuleDescription();
        String ownModuleName = ownModule != null ? ownModule.getElementName() : null;
        List<IType> candidates = new ArrayList<>();
        for (int i = 0; i < matched.size(); i++) {
            if (bindings[i] instanceof ITypeBinding type && isAccessibleFrom(type.getPackage(), ownModuleName)) {
                candidates.add(matched.get(i));
            }
        }
        return candidates;
    }

    /**
     * Whether code in the module named {@code ownModuleName} ({@code null} for the unnamed
     * module) can access {@code pkg}: a package of the unnamed module, of that module itself, or
     * exported by its named module to every module or to that one.
     */
    private static boolean isAccessibleFrom(IPackageBinding pkg, String ownModuleName) {
        IModuleBinding module = pkg.getModule();
        if (module == null || module.getName().isEmpty() || module.getName().equals(ownModuleName)) {
            return true;
        }
        for (IPackageBinding exported : module.getExportedPackages()) {
            if (exported.getName().equals(pkg.getName())) {
                String[] targets = module.getExportedTo(exported);
                return targets.length == 0 || Arrays.asList(targets).contains(ownModuleName);
            }
        }
        return false;
    }
}
