package org.javalens.mcp.fixtures;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.search.ModelHierarchy;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;

import java.util.List;
import java.util.function.Predicate;

/**
 * The search service of a {@link FaultyService}: each method the service names throws
 * {@link FaultyService#modelFailure()}; every other call searches for the elements themselves,
 * never the failing ones the service handed out, and a symbol search hands out the declarations
 * it reports as the service hands out its elements.
 */
final class FaultySearchService extends SearchService {

    private final FaultyService service;

    FaultySearchService(IJavaProject project, FaultyService service) {
        super(project);
        this.service = service;
    }

    private void fail(String method) throws CoreException {
        if (service.searchFails(method)) {
            throw FaultyService.modelFailure();
        }
    }

    @Override
    public SearchResult searchSymbols(String pattern, Integer searchFor, Predicate<IJavaElement> accept,
                                      int offset, int limit) throws CoreException {
        fail("searchSymbols");
        SearchResult result = super.searchSymbols(pattern, searchFor, accept, offset, limit);
        if (service.proxiesMembers()) {
            for (SearchMatch match : result.matches()) {
                if (match.getElement() instanceof IJavaElement element) {
                    match.setElement(service.members(element));
                }
            }
        }
        return result;
    }

    @Override
    public SearchResult findReferences(IJavaElement element, int limitTo, int maxResults) throws CoreException {
        fail("findReferences");
        return super.findReferences(FaultyService.unwrap(element), limitTo, maxResults);
    }

    @Override
    public SearchResult findReferences(IType type, ReferenceKind kind, int maxResults) throws CoreException {
        fail("findReferences");
        return super.findReferences(FaultyService.unwrap(type), kind, maxResults);
    }

    @Override
    public SearchResult findAllReferencesInSources(IJavaElement element, int maxResults) throws CoreException {
        fail("findAllReferencesInSources");
        return super.findAllReferencesInSources(FaultyService.unwrap(element), maxResults);
    }

    @Override
    public SearchResult findDeclarationsInSources(IJavaElement element, int maxResults) throws CoreException {
        fail("findDeclarationsInSources");
        return super.findDeclarationsInSources(FaultyService.unwrap(element), maxResults);
    }

    @Override
    public SearchResult findImplementations(IJavaElement element, int maxResults) throws CoreException {
        fail("findImplementations");
        return super.findImplementations(FaultyService.unwrap(element), maxResults);
    }

    @Override
    public ModelHierarchy hierarchyOf(IType type) throws CoreException {
        fail("hierarchyOf");
        return super.hierarchyOf(FaultyService.unwrap(type));
    }

    @Override
    public List<IType> implementorsWithoutModelHandles(IType type) throws CoreException {
        fail("implementorsWithoutModelHandles");
        return super.implementorsWithoutModelHandles(FaultyService.unwrap(type));
    }

    @Override
    public List<IMethod> rippleOf(IMethod method) throws CoreException {
        fail("rippleOf");
        return super.rippleOf(FaultyService.unwrap(method));
    }
}
