package org.javalens.mcp.tools;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTRequestor;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringScopeFactory;
import org.eclipse.jdt.internal.corext.refactoring.SearchResultGroup;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaStatusContext;
import org.eclipse.jdt.internal.corext.util.JavaConventionsUtil;
import org.eclipse.jdt.internal.corext.util.JdtFlags;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.corext.util.SearchUtils;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.search.ModelHierarchy;
import org.javalens.core.search.SearchService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The conditions JDT checks before it renames a virtual method, checked on the Java model's own
 * hierarchy ({@link ModelHierarchy}) and the methods rename_symbol renames together
 * ({@link SearchService#rippleOf}), never on JDT's type hierarchy or its RippleMethodFinder2: both
 * drop or misread a local or anonymous class declared in a lambda body (RippleMethodFinder2
 * asserts at :217 for a method related to one), which rename_symbol renames. The conditions, their
 * order and their messages are those of RenameMethodProcessor.checkInitialConditions :323-337 and
 * doCheckFinalConditions :340-427 with RenameVirtualMethodProcessor.checkInitialConditions
 * :113-141 and doCheckFinalConditions :148-194, as JDT runs them for a rename it starts itself
 * (not one RenameTypeProcessor hands it, whose messages name the declaring type).
 *
 * <p>Left out, with the reason each has no case here: Checks.isAvailable's binary refusal and
 * checkRelatedMethods' binary, read-only and not-in-model refusals (rename_symbol refuses a binary
 * method, or a method renamed with one, before: RelatedMethods.binaryRefusal; a source member is
 * never read-only, Member.isReadOnly :462; the methods come from the model); and JDT's check of
 * references in class files (ReferencesInBinaryContext.addErrorIfNecessary :58-62): JDT's search
 * finds a reference in a class file only through its attached source (MatchLocator.process
 * :1997-2011 reports a class file without source by its declarations alone), JavaLens attaches
 * no source to a library (ProjectImporter:507), and a class of the JDK, whose source it may hold,
 * cannot reference a method of the project. For the same reasons JDT's note of matches in class
 * files (RefactoringSearchEngine.addStatusErrors :225-229) has no case: the places renamed are
 * searched in the project's sources, where every match has its compilation unit.
 *
 * <p>Nor do the checks LTK adds to every processor's (ProcessorBasedRefactoring.checkFinalConditions)
 * find anything here: rename participants (no bundle of JavaLens contributes to
 * org.eclipse.ltk.core.refactoring.renameParticipants); the ResourceChangeChecker, which hands the
 * changed files to the workspace's model providers (JavaLens holds only org.eclipse.core.resources'
 * own, which accepts every change); and the ValidateEditChecker, to which a method's rename adds no
 * file (RenameModifications.buildValidateEdits :182-192 adds renamed compilation units only).
 */
final class VirtualMethodRenameConditions {

    /** The methods of java.lang.Object an interface method cannot take with another return type (JLS 9.2). */
    private static final String[] SPECIAL_NAMES = {"toString", "toString", "toString", "toString", "equals",
        "equals", "getClass", "getClass", "hashCode", "notify", "notifyAll", "wait", "wait", "wait"};
    private static final String[][] SPECIAL_PARAMETER_TYPES = {{}, {}, {}, {}, {"QObject;"},
        {"Qjava.lang.Object;"}, {}, {}, {}, {}, {}, {Signature.SIG_LONG, Signature.SIG_INT},
        {Signature.SIG_LONG}, {}};
    private static final String[] SPECIAL_RETURN_TYPES = {"QString;", "QString;", "Qjava.lang.String;",
        "Qjava.lang.String;", Signature.SIG_BOOLEAN, Signature.SIG_BOOLEAN, "QClass;", "Qjava.lang.Class;",
        Signature.SIG_INT, Signature.SIG_VOID, Signature.SIG_VOID, Signature.SIG_VOID, Signature.SIG_VOID,
        Signature.SIG_VOID};

    private VirtualMethodRenameConditions() {
    }

    /**
     * What renaming {@code method}, with every method of {@code ripple}, to {@code newName}, by the
     * edits {@code occurrences}, finds: the entries JDT's method rename would report, with the
     * contexts JDT gives them. {@code potential} are the places the search reports but cannot
     * confirm, which JDT's occurrences hold too (RefactoringSearchEngine.search keeps them).
     */
    static RefactoringStatus check(IMethod method, List<IMethod> ripple, String newName,
                                   List<RenameSymbolTool.Occurrence> occurrences, List<SearchMatch> potential,
                                   IJdtService service) throws CoreException {
        SearchService search = service.getSearchService();

        // Initial conditions, of the method at the position (RenameMethodProcessor :323-337).
        if (!method.exists()) {
            return RefactoringStatus.createFatalErrorStatus(Messages.format(
                RefactoringCoreMessages.RenameMethodRefactoring_deleted,
                BasicElementLabels.getFileName(method.getCompilationUnit())));
        }
        RefactoringStatus result = Checks.checkAvailability(method);
        if (result.hasFatalError()) {
            return result;
        }
        result.merge(Checks.checkIfCuBroken(method));
        if (JdtFlags.isNative(method)) {
            result.addError(RefactoringCoreMessages.RenameMethodRefactoring_no_native);
        }
        if (result.hasFatalError()) {
            return result;
        }

        // The rename runs from the topmost method (RenameVirtualMethodProcessor :119-135).
        IMethod topmost = topmost(method, search);

        // Final conditions (RenameMethodProcessor :340-427).
        result.merge(Checks.checkIfCuBroken(topmost));
        if (result.hasFatalError()) {
            return result;
        }
        result.merge(checkNewElementName(topmost, newName));
        if (result.hasFatalError()) {
            return result;
        }
        boolean mustAnalyzeShadowing = declaresClashingMethod(topmost, newName, service);
        result.merge(checkRelatedMethods(ripple, newName));
        Set<ICompilationUnit> kept = analyzeCompilationUnits(occurrences, potential, result);
        if (result.hasFatalError()) {
            return result;
        }
        if (mustAnalyzeShadowing) {
            List<RenameSymbolTool.Occurrence> updated = occurrences.stream()
                .filter(occurrence -> kept.contains(occurrence.unit())).toList();
            result.merge(analyzeRenameChanges(newName, updated, service));
        }

        // RenameVirtualMethodProcessor.doCheckFinalConditions :157-188.
        IType declaring = topmost.getDeclaringType();
        if (declaring.isInterface()) {
            if (isSpecialCase(topmost, newName)) {
                result.addError(RefactoringCoreMessages.RenameMethodInInterfaceRefactoring_special_case);
            }
            for (IMethod related : relatedTypesDeclareMethodName(topmost, ripple, newName, search)) {
                result.addError(RefactoringCoreMessages.RenameMethodInInterfaceRefactoring_already_defined,
                    JavaStatusContext.create(related));
            }
        } else {
            ModelHierarchy hierarchy = search.hierarchyOf(declaring);
            if (classesDeclareOverridingNativeMethod(hierarchy.allSubtypes(), topmost)) {
                result.addError(Messages.format(RefactoringCoreMessages.RenameVirtualMethodRefactoring_requieres_renaming_native,
                    new String[] {BasicElementLabels.getJavaElementName(topmost.getElementName()), "UnsatisfiedLinkError"}));
            }
            for (IMethod hierarchyMethod : hierarchyDeclaresMethodName(hierarchy, topmost, newName)) {
                if (Checks.compareParamTypes(topmost.getParameterTypes(), hierarchyMethod.getParameterTypes())) {
                    result.addError(Messages.format(RefactoringCoreMessages.RenameVirtualMethodRefactoring_hierarchy_declares2,
                        BasicElementLabels.getJavaElementName(newName)), JavaStatusContext.create(hierarchyMethod));
                } else {
                    result.addWarning(Messages.format(RefactoringCoreMessages.RenameVirtualMethodRefactoring_hierarchy_declares1,
                        BasicElementLabels.getJavaElementName(newName)), JavaStatusContext.create(hierarchyMethod));
                }
            }
        }
        return result;
    }

    /**
     * The method JDT renames from (MethodChecks.getTopmostMethod :149-172): for a method of a class,
     * the first method of an interface of a class of its hierarchy with its name and parameter types
     * (MethodChecks.isDeclaredInInterface :105-128); else the method it overrides at the top of its
     * chain, as MethodOverrideTester.findDeclaringMethod follows it, when that is neither static nor
     * private (MethodChecks.overridesAnotherMethod :130-138); else the method itself.
     */
    private static IMethod topmost(IMethod method, SearchService search) throws CoreException {
        IType declaring = method.getDeclaringType();
        if (!declaring.isInterface()) {
            ModelHierarchy hierarchy = search.hierarchyOf(declaring);
            for (IType type : allClasses(hierarchy, method.getJavaProject())) {
                List<IType> interfaces = type.equals(declaring)
                    ? hierarchy.allSuperInterfaces() : search.hierarchyOf(type).allSuperInterfaces();
                for (IType superInterface : interfaces) {
                    IMethod found = Checks.findSimilarMethod(method, superInterface);
                    if (found != null && !found.equals(method)) {
                        return found;
                    }
                }
            }
        }
        IMethod declaringMethod = null;
        for (IMethod overridden = search.hierarchyOf(declaring).overridden(method); overridden != null;
                overridden = search.hierarchyOf(overridden.getDeclaringType()).overridden(overridden)) {
            declaringMethod = overridden;
        }
        if (declaringMethod != null && !declaringMethod.equals(method)
                && !JdtFlags.isStatic(declaringMethod) && !JdtFlags.isPrivate(declaringMethod)) {
            return declaringMethod;
        }
        return method;
    }

    /**
     * The classes of {@code hierarchy}, as JDT's type hierarchy lists them (TypeHierarchy.getAllClasses
     * :398-402): its type when a class, and its superclasses, or for an interface java.lang.Object,
     * which JDT's hierarchy of an interface holds as its root class (HierarchyResolver.reportHierarchy
     * :611-615); then its subtypes that are classes. JDT leaves java.lang.Object out where a
     * superclass of the hierarchy does not resolve; here, as in {@link ModelHierarchy}, every class
     * has java.lang.Object above it.
     */
    private static List<IType> allClasses(ModelHierarchy hierarchy, IJavaProject project) throws CoreException {
        List<IType> classes = new ArrayList<>();
        if (!hierarchy.type().isInterface()) {
            classes.add(hierarchy.type());
            classes.addAll(hierarchy.allSuperclasses());
        } else {
            IType object = project.findType("java.lang.Object");
            if (object != null) {
                classes.add(object);
            }
        }
        for (IType subtype : hierarchy.allSubtypes()) {
            if (!subtype.isInterface()) {
                classes.add(subtype);
            }
        }
        return classes;
    }

    /** RenameMethodProcessor.checkNewElementName :221-243, for a rename JDT starts itself. */
    private static RefactoringStatus checkNewElementName(IMethod topmost, String newName) {
        RefactoringStatus status = Checks.checkName(newName, JavaConventionsUtil.validateMethodName(newName, topmost));
        if (status.isOK() && !Checks.startsWithLowerCase(newName)) {
            status = RefactoringStatus.createWarningStatus(RefactoringCoreMessages.Checks_method_names_lowercase);
        }
        if (Checks.isAlreadyNamed(topmost, newName)) {
            status.addFatalError(RefactoringCoreMessages.RenameMethodRefactoring_same_name, JavaStatusContext.create(topmost));
        }
        IImportDeclaration staticImport = Checks.findMatchingStaticMethodImport(topmost, newName);
        if (staticImport != null) {
            status.addFatalError(RefactoringCoreMessages.RenameMethodRefactoring_overrides_static_name,
                JavaStatusContext.create(staticImport));
        }
        return status;
    }

    /**
     * Whether a method named {@code newName} with as many parameters as {@code topmost} is declared
     * in the project's sources, when JDT analyzes what the renamed references bind to
     * (RenameMethodProcessor.searchForDeclarationsOfClashingMethods :453-486, used at :357-396).
     */
    private static boolean declaresClashingMethod(IMethod topmost, String newName, IJdtService service)
            throws CoreException {
        StringBuilder pattern = new StringBuilder(newName).append('(');
        for (int i = 0; i < topmost.getNumberOfParameters(); i++) {
            pattern.append(i > 0 ? ",*" : "*");
        }
        SearchPattern declarations = SearchPattern.createPattern(pattern.append(')').toString(),
            IJavaSearchConstants.METHOD, IJavaSearchConstants.DECLARATIONS, SearchUtils.GENERICS_AGNOSTIC_MATCH_RULE);
        if (declarations == null) {
            return false;
        }
        boolean[] found = {false};
        new SearchEngine().search(declarations, SearchUtils.getDefaultSearchParticipants(),
            RefactoringScopeFactory.create(service.getJavaProject()), new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    if (match.getElement() instanceof IMethod) {
                        found[0] = true;
                    }
                }
            }, Deadline.monitor());
        return found[0];
    }

    /**
     * RenameMethodProcessor.checkRelatedMethods :513-531: a related method that would take its
     * class's name, a warning; a native related method, an error.
     */
    private static RefactoringStatus checkRelatedMethods(List<IMethod> ripple, String newName) throws CoreException {
        RefactoringStatus result = new RefactoringStatus();
        for (IMethod method : ripple) {
            result.merge(Checks.checkIfConstructorName(method, newName, method.getDeclaringType().getElementName()));
            if (JdtFlags.isNative(method)) {
                result.addError(Messages.format(RefactoringCoreMessages.RenameMethodRefactoring_no_native_1,
                    new String[] {BasicElementLabels.getJavaElementName(method.getElementName()),
                        BasicElementLabels.getJavaElementName(method.getDeclaringType().getFullyQualifiedName('.'))}));
            }
        }
        return result;
    }

    /**
     * RenameMethodProcessor.analyzeCompilationUnits :533-545: a unit the rename edits whose
     * structure JDT could not read for syntax errors is an error and is not analyzed further
     * (Checks.excludeCompilationUnits :586-607); a unit with compile errors, a warning
     * (Checks.checkCompileErrorsInAffectedFiles :609-615). The units are those of the edits and of
     * the places the search could not confirm, as JDT's occurrences hold both; a match outside a
     * compilation unit is passed over, as Checks.excludeCompilationUnits :592-594 passes it. The
     * units kept, each once.
     */
    private static Set<ICompilationUnit> analyzeCompilationUnits(List<RenameSymbolTool.Occurrence> occurrences,
                                                                 List<SearchMatch> potential,
                                                                 RefactoringStatus result) throws CoreException {
        Map<ICompilationUnit, SearchResultGroup> groups = new LinkedHashMap<>();
        for (RenameSymbolTool.Occurrence occurrence : occurrences) {
            groups.computeIfAbsent(occurrence.unit().getPrimary(),
                unit -> new SearchResultGroup(unit.getResource(), new SearchMatch[0]));
        }
        for (SearchMatch match : potential) {
            if (match.getResource() != null && JavaCore.create(match.getResource()) instanceof ICompilationUnit unit) {
                groups.computeIfAbsent(unit.getPrimary(),
                    primary -> new SearchResultGroup(primary.getResource(), new SearchMatch[0]));
            }
        }
        SearchResultGroup[] kept = Checks.excludeCompilationUnits(groups.values().toArray(new SearchResultGroup[0]), result);
        result.merge(Checks.checkCompileErrorsInAffectedFiles(kept));
        Set<ICompilationUnit> units = new HashSet<>();
        for (SearchResultGroup group : kept) {
            units.add((ICompilationUnit) JavaCore.create(group.getResource()));
        }
        return units;
    }

    /**
     * RenameMethodProcessor.analyzeRenameChanges :549-589 with RenameAnalyzeUtil.analyzeRenameChanges2
     * :259-387: with every edit applied, a reference that no longer reaches a renamed method is
     * shadowed (RenameAnalyzeUtil_shadows, at the reference), and a reference that reaches a renamed
     * method without having been renamed now reaches it in place of another
     * (RenameAnalyzeUtil_reference_shadowed, at the member enclosing it). What a name reaches is
     * read from the compiler's bindings in working copies holding the edits, where JDT searches
     * them: JDT's search cannot find a method of a class declared in a lambda body by its handle.
     * The references read are those in every unit the rename edits and in every unit the search
     * index says names newName.
     */
    private static RefactoringStatus analyzeRenameChanges(String newName, List<RenameSymbolTool.Occurrence> occurrences,
                                                          IJdtService service) throws CoreException {
        RefactoringStatus result = new RefactoringStatus();
        Map<ICompilationUnit, List<RenameSymbolTool.Occurrence>> byUnit = new LinkedHashMap<>();
        for (RenameSymbolTool.Occurrence occurrence : occurrences) {
            byUnit.computeIfAbsent(occurrence.unit().getPrimary(), unit -> new ArrayList<>()).add(occurrence);
        }
        WorkingCopyOwner owner = new WorkingCopyOwner() { };
        Map<ICompilationUnit, ICompilationUnit> copies = new LinkedHashMap<>();
        try {
            for (Map.Entry<ICompilationUnit, List<RenameSymbolTool.Occurrence>> edited : byUnit.entrySet()) {
                ICompilationUnit copy = edited.getKey().getWorkingCopy(owner, null);
                copies.put(edited.getKey(), copy);
                IBuffer buffer = copy.getBuffer();
                buffer.setContents(applied(edited.getKey().getSource(), edited.getValue()));
                copy.reconcile(ICompilationUnit.NO_AST, false, owner, Deadline.monitor());
            }

            Set<ICompilationUnit> units = new LinkedHashSet<>(byUnit.keySet());
            units.addAll(unitsNaming(newName, service));
            List<ICompilationUnit> parsed = new ArrayList<>();
            for (ICompilationUnit unit : units) {
                parsed.add(copies.getOrDefault(unit, unit));
            }
            Map<ICompilationUnit, CompilationUnit> trees = new HashMap<>();
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setResolveBindings(true);
            parser.setWorkingCopyOwner(owner);
            parser.setProject(service.getJavaProject());
            parser.createASTs(parsed.toArray(new ICompilationUnit[0]), new String[0], new ASTRequestor() {
                @Override
                public void acceptAST(ICompilationUnit source, CompilationUnit ast) {
                    trees.put(source.getPrimary(), ast);
                }
            }, Deadline.monitor());

            // The renamed methods, by the bindings of their renamed declarations: a method's, or an
            // annotation type member's
            Set<String> renamed = new HashSet<>();
            for (Map.Entry<ICompilationUnit, List<RenameSymbolTool.Occurrence>> edited : byUnit.entrySet()) {
                CompilationUnit tree = tree(trees, edited.getKey());
                for (RenameSymbolTool.Occurrence occurrence : edited.getValue()) {
                    if (occurrence.declaration()
                            && NodeFinder.perform(tree, updatedOffset(occurrence, edited.getValue()), newName.length())
                                instanceof SimpleName name
                            && name.resolveBinding() instanceof IMethodBinding method) {
                        renamed.add(method.getMethodDeclaration().getKey());
                    }
                }
            }

            for (ICompilationUnit unit : units) {
                CompilationUnit tree = tree(trees, unit);
                Set<Integer> reaching = referencesReaching(tree, newName, renamed);
                List<RenameSymbolTool.Occurrence> edits = byUnit.getOrDefault(unit, List.of());
                Set<Integer> renamedReferences = new HashSet<>();
                for (RenameSymbolTool.Occurrence occurrence : edits) {
                    if (occurrence.declaration()) {
                        continue;
                    }
                    int updated = updatedOffset(occurrence, edits);
                    renamedReferences.add(updated);
                    if (!reaching.contains(updated)) {
                        result.addError(Messages.format(RefactoringCoreMessages.RenameAnalyzeUtil_shadows,
                                BasicElementLabels.getFileName(unit)),
                            JavaStatusContext.create(unit, new SourceRange(occurrence.offset(), occurrence.length())));
                    }
                }
                List<Integer> taken = reaching.stream().filter(offset -> !renamedReferences.contains(offset))
                    .sorted(Comparator.naturalOrder()).toList();
                for (int offset : taken) {
                    // The member enclosing the reference, as the unit on disk holds it: none when the
                    // member is one the rename creates (RenameAnalyzeUtil.getOldSourceRange :362-375)
                    IJavaElement enclosing = copies.getOrDefault(unit, unit).getElementAt(offset);
                    IJavaElement primary = enclosing == null ? null : enclosing.getPrimaryElement();
                    ISourceRange range = primary != null && primary.exists() && primary instanceof ISourceReference source
                        ? source.getSourceRange() : null;
                    result.addError(Messages.format(RefactoringCoreMessages.RenameAnalyzeUtil_reference_shadowed,
                            new String[] {BasicElementLabels.getFileName(unit), BasicElementLabels.getJavaElementName(newName)}),
                        JavaStatusContext.create(unit, range));
                }
            }
        } finally {
            discardAll(copies.values());
        }
        return result;
    }

    /**
     * Discards each of {@code copies}, every one even when discarding another fails; the first
     * failure is thrown once all are tried, the others suppressed in it.
     */
    private static void discardAll(Collection<ICompilationUnit> copies) throws JavaModelException {
        JavaModelException first = null;
        for (ICompilationUnit copy : copies) {
            try {
                copy.discardWorkingCopy();
            } catch (JavaModelException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** The tree parsed for {@code unit}, which the parser gives for every unit it is handed. */
    private static CompilationUnit tree(Map<ICompilationUnit, CompilationUnit> trees, ICompilationUnit unit) {
        CompilationUnit tree = trees.get(unit);
        if (tree == null) {
            throw new IllegalStateException("JDT's parser gave no tree for " + unit.getElementName());
        }
        return tree;
    }

    /**
     * The starts of the names in {@code tree} that are {@code newName} and reach a method whose
     * binding key is in {@code renamed}, declarations aside: calls, method references, Javadoc
     * references, static imports and annotation element names.
     */
    private static Set<Integer> referencesReaching(CompilationUnit tree, String newName, Set<String> renamed) {
        Set<Integer> reaching = new HashSet<>();
        tree.accept(new ASTVisitor(true) {
            @Override
            public boolean visit(SimpleName node) {
                if (node.getIdentifier().equals(newName) && !node.isDeclaration()) {
                    IBinding binding = node.resolveBinding();
                    if (binding instanceof IMethodBinding method
                            && renamed.contains(method.getMethodDeclaration().getKey())) {
                        reaching.add(node.getStartPosition());
                    }
                }
                return true;
            }
        });
        return reaching;
    }

    /**
     * The compilation units of the project's sources that the search index lists as naming a
     * method {@code newName}, with any parameters, whether or not the search resolves the name
     * to a method; what each name reaches is read from bindings afterwards.
     */
    private static Set<ICompilationUnit> unitsNaming(String newName, IJdtService service) throws CoreException {
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        SearchPattern references = SearchPattern.createPattern(newName, IJavaSearchConstants.METHOD,
            IJavaSearchConstants.REFERENCES, SearchUtils.GENERICS_AGNOSTIC_MATCH_RULE);
        new SearchEngine().search(references, SearchUtils.getDefaultSearchParticipants(),
            RefactoringScopeFactory.create(service.getJavaProject()), new SearchRequestor() {
                @Override
                public void acceptSearchMatch(SearchMatch match) {
                    if (match.getResource() != null && JavaCore.create(match.getResource()) instanceof ICompilationUnit unit) {
                        units.add(unit.getPrimary());
                    }
                }
            }, Deadline.monitor());
        return units;
    }

    /** {@code source} with {@code edits}, all of one unit, applied. */
    private static String applied(String source, List<RenameSymbolTool.Occurrence> edits) {
        List<RenameSymbolTool.Occurrence> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(RenameSymbolTool.Occurrence::offset).reversed());
        StringBuilder text = new StringBuilder(source);
        for (RenameSymbolTool.Occurrence edit : ordered) {
            text.replace(edit.offset(), edit.offset() + edit.length(), edit.newText());
        }
        return text.toString();
    }

    /** Where {@code occurrence}'s new text starts once every edit of its unit, {@code edits}, is applied. */
    private static int updatedOffset(RenameSymbolTool.Occurrence occurrence, List<RenameSymbolTool.Occurrence> edits) {
        int offset = occurrence.offset();
        for (RenameSymbolTool.Occurrence edit : edits) {
            if (edit.offset() < occurrence.offset()) {
                offset += edit.newText().length() - edit.length();
            }
        }
        return offset;
    }

    /** RenameVirtualMethodProcessor.isSpecialCase :214-236. */
    private static boolean isSpecialCase(IMethod topmost, String newName) throws CoreException {
        for (int i = 0; i < SPECIAL_NAMES.length; i++) {
            if (SPECIAL_NAMES[i].equals(newName)
                    && Checks.compareParamTypes(topmost.getParameterTypes(), SPECIAL_PARAMETER_TYPES[i])
                    && !SPECIAL_RETURN_TYPES[i].equals(topmost.getReturnType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * RenameVirtualMethodProcessor.relatedTypeDeclaresMethodName :198-212: for the type declaring
     * each related method, the methods of its hierarchy {@link #hierarchyDeclaresMethodName} finds
     * for its method of the old name and as many parameters.
     */
    private static Set<IMethod> relatedTypesDeclareMethodName(IMethod topmost, List<IMethod> ripple, String newName,
                                                              SearchService search) throws CoreException {
        Set<IType> types = new LinkedHashSet<>();
        for (IMethod method : ripple) {
            types.add(method.getDeclaringType());
        }
        Set<IMethod> result = new LinkedHashSet<>();
        for (IType type : types) {
            IMethod found = Checks.findMethod(topmost, type);
            result.addAll(hierarchyDeclaresMethodName(search.hierarchyOf(found.getDeclaringType()), found, newName));
        }
        return result;
    }

    /**
     * RenameMethodProcessor.hierarchyDeclaresMethodName :719-739 with classesDeclareMethodName
     * :695-717: the methods named {@code newName} with as many parameters as {@code method} that
     * its type declares, and that the classes of its hierarchy and the classes implementing it
     * declare, a private one only when {@code method} is not private or its class is a subclass.
     */
    private static Set<IMethod> hierarchyDeclaresMethodName(ModelHierarchy hierarchy, IMethod method, String newName)
            throws CoreException {
        Set<IMethod> result = new LinkedHashSet<>();
        IMethod found = Checks.findMethod(newName, method.getParameterTypes().length, false, method.getDeclaringType());
        if (found != null) {
            result.add(found);
        }
        result.addAll(classesDeclareMethodName(hierarchy, allClasses(hierarchy, method.getJavaProject()), method, newName));
        List<IType> implementing = new ArrayList<>();
        if (hierarchy.type().isInterface()) {
            for (IType subtype : hierarchy.subtypes()) {
                if (!subtype.isInterface()) {
                    implementing.add(subtype);
                }
            }
        }
        result.addAll(classesDeclareMethodName(hierarchy, implementing, method, newName));
        return result;
    }

    private static Set<IMethod> classesDeclareMethodName(ModelHierarchy hierarchy, List<IType> classes, IMethod method,
                                                         String newName) throws CoreException {
        Set<IMethod> result = new LinkedHashSet<>();
        IType type = method.getDeclaringType();
        List<IType> subtypes = hierarchy.allSubtypes();
        int parameterCount = method.getParameterTypes().length;
        boolean isMethodPrivate = JdtFlags.isPrivate(method);
        for (IType clazz : classes) {
            boolean isSubclass = subtypes.contains(clazz);
            for (IMethod candidate : clazz.getMethods()) {
                IMethod found = Checks.findMethod(newName, parameterCount, false, new IMethod[] {candidate});
                if (found != null && (isSubclass || type.equals(clazz) || (!isMethodPrivate && !JdtFlags.isPrivate(candidate)))) {
                    result.add(found);
                }
            }
        }
        return result;
    }

    /** RenameVirtualMethodProcessor.classesDeclareOverridingNativeMethod :249-258. */
    private static boolean classesDeclareOverridingNativeMethod(List<IType> classes, IMethod topmost) throws CoreException {
        for (IType type : classes) {
            for (IMethod method : type.getMethods()) {
                if (!method.equals(topmost) && JdtFlags.isNative(method)
                        && Checks.findSimilarMethod(topmost, new IMethod[] {method}) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
