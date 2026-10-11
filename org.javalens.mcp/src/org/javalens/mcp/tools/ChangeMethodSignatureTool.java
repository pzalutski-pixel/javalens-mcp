package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.SourceRange;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeMemberDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MemberRef;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.MethodRef;
import org.eclipse.jdt.core.dom.MethodRefParameter;
import org.eclipse.jdt.core.dom.MethodReference;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.internal.core.manipulation.util.BasicElementLabels;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.RefactoringCoreMessages;
import org.eclipse.jdt.internal.corext.refactoring.rename.RefactoringAnalyzeUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaElementUtil;
import org.eclipse.jdt.internal.corext.refactoring.util.JavaStatusContext;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.RefactoringInvoker;
import org.javalens.mcp.rewrite.TextEditConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Change method signature (parameters, return type, name) and update every place JDT's
 * change-signature refactoring updates, together with every method related to it
 * ({@code SearchService.rippleOf}), as rename_symbol renames them.
 */
public class ChangeMethodSignatureTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(ChangeMethodSignatureTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ChangeMethodSignatureTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "change_method_signature";
    }

    @Override
    public String getDescription() {
        return """
            Change method signature (parameters, return type, or name) and update all call sites.

            Returns text edits for the method declaration and every place that must change.
            The caller should apply these edits to perform the change.

            USAGE: Position on method declaration, provide changes
            OUTPUT: Edits for the declarations and every place updated

            The method changes together with every method it overrides, every
            method that overrides it and every method a subtype inherits together
            with it, as rename_symbol renames them: one declaration edit each,
            each keeping its own parameter names and unchanged parameter types,
            and every call of any of them. A method related to a method of a
            class file is refused, naming that method: it cannot change. A place
            JDT's search reports but cannot confirm as a call, where a type or name
            does not resolve, is never rewritten: potentialMatches lists every one,
            to review by hand. An element of an annotation type is refused: it has
            no parameters; rename it with rename_symbol.

            Every place JDT's change-signature refactoring updates is updated:
            calls, including the arguments of enum constants; Javadoc references
            (#method(Type) and Type#method(Type) links); a single static import of
            the method, renamed, or followed by an import of the new name when a
            member it names keeps the old name; the parameters of each lambda
            expression implementing the method; and, when a constructor without
            parameters is given some, its implicit super() calls: super(...) is
            added to each constructor of a direct subclass that calls it
            implicitly, and a constructor calling super(...) to each direct
            subclass that declares none. A call nested in another changed place,
            such as a chained call, changes within that place's edit.

            Each edit replaces a span (type replace, with oldText and newText, or
            oldSignature and newSignature for a declaration, isDeclaration true)
            or inserts text (type insert, with line, column, offset and newText).
            A file's edits come in this order: declarations, the other places the
            search reports, in source order, lambda expressions, implicit super()
            calls, static imports.

            PARAMETER OPERATIONS:
            - Add new parameter with default value for existing calls
            - Remove parameter (will remove from calls)
            - Rename parameter: give it its new name and, as oldName, the name it
              has now. Each declaration and lambda expression that names it so
              takes the new name in its parameter list and every reference to it
              in its body, a declaration's @param tag naming it too, and a
              Javadoc reference naming it takes the new name; calls keep their
              arguments. A declaration or lambda expression that names it
              otherwise keeps its own name. An oldName that names no parameter
              of the method is INVALID_PARAMETER.
            - Reorder parameters (specify all parameters in new order)

            A renamed parameter's @param tag and references follow its
            declaration's or lambda expression's edit, in source order. The edits
            of the lambda expressions come in source order, file by file, as the
            other places do.

            Every declaration keeps each kept parameter's modifiers and
            annotations as written. A changed type replaces only the type: the
            parameter is varargs when the new type ends in ..., and dimensions
            written after its name are dropped, as JDT does.

            Refused as JDT refuses them (INVALID_PARAMETER 'newParameters', with
            JDT's messages): a blank parameter name or one that is no Java
            identifier; two parameters of one name; a name the change gives that
            a method changed with it already gives one of its parameters, naming
            that method; and a rename that changes what a name refers to: a
            renamed reference a local variable would capture (in the body, an
            anonymous class or a lambda expression), or a reference to a field
            or another variable the renamed parameter would capture. Each
            collision names the declaration it lies in and its place, as the
            file is now.

            Compile errors that follow from the requested change (a changed
            return or parameter type breaking a caller, a removed parameter
            still used) do not refuse it. introducedProblems lists every compile
            error the applied edits introduce in each file the change edits or
            reports a place in, and JDT's note for each removed parameter a body
            still uses, at its first use, also where the code compiles because a
            field of that name captures it. Each entry gives filePath, line and
            column, as the file is now, and message, which names the method,
            field, initializer or type the error lies in ("a lambda expression
            in" it when it lies in one; an anonymous class by its number, as in
            Outer$1). Entries come by file, then line and column, then message.
            introducedProblems is present only when it has an entry.

            warnings lists JDT's warnings on the new parameter names, in the
            order of the parameters, then one for each file holding a method
            reference that cannot be updated, in the order of methodReferences.

            IMPORTANT: Uses ZERO-BASED coordinates.

            """ + POTENTIAL_MATCHES + """
            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return SchemaBuilder.object()
            .required("filePath", "string", "Path to source file containing the method")
            .required("line", "integer", "Zero-based line number of method declaration")
            .required("column", "integer", "Zero-based column number")
            .optional("newName", "string", "New method name (optional, omit to keep current)")
            .optional("newReturnType", "string", "New return type (optional, omit to keep current)")
            .optionalCustom("newParameters", Map.of(
                "type", "array",
                "description", "New parameter list. Each item: {name, type, oldName?, defaultValue?}. Order matters.",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Parameter name"),
                        "type", Map.of("type", "string", "description", "Parameter type"),
                        "oldName", Map.of("type", "string", "description",
                            "The parameter's current name, when it is renamed to name"),
                        "defaultValue", Map.of("type", "string", "description", "Default value for new params at call sites")
                    )
                )
            ))
            .build();
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
        }

        String newName = getStringParam(arguments, "newName");
        String newReturnType = getStringParam(arguments, "newReturnType");

        // Parse new parameters
        List<ParameterInfo> newParameters = null;
        if (arguments.has("newParameters") && arguments.get("newParameters").isArray()) {
            newParameters = new ArrayList<>();
            for (JsonNode param : arguments.get("newParameters")) {
                String pName = param.has("name") ? param.get("name").asText() : null;
                String pType = param.has("type") ? param.get("type").asText() : null;
                String pDefault = param.has("defaultValue") ? param.get("defaultValue").asText() : null;
                String pOldName = param.has("oldName") ? param.get("oldName").asText() : null;

                if (pName == null || pType == null) {
                    return ToolResponse.invalidParameter("newParameters",
                        "Each parameter must have 'name' and 'type'");
                }
                newParameters.add(new ParameterInfo(pName, pType, pDefault, pOldName));
            }
        }

        // Validate at least one change is specified
        if (newName == null && newReturnType == null && newParameters == null) {
            return ToolResponse.invalidParameter("changes",
                "At least one of newName, newReturnType, or newParameters must be specified");
        }

        if (newName != null && !isValidJavaIdentifier(newName)) {
            return ToolResponse.invalidParameter("newName", "Not a valid Java identifier");
        }

        try {
            Path path = Path.of(filePath);

            // Get the method at position
            IJavaElement element = service.getElementAtPosition(path, line, column);
            if (!(element instanceof IMethod method)) {
                return ToolResponse.symbolNotFound("No method found at position");
            }

            String oldName = method.getElementName();

            // An element of an annotation type has no parameters to change; JDT offers no
            // change-signature for it (RefactoringAvailabilityTesterCore.isChangeSignatureAvailable
            // :232-234). It is renamed with rename_symbol.
            if (method.getDeclaringType().isAnnotation()) {
                return ToolResponse.invalidParameter("position", "Method at position, " + oldName + " in "
                    + ElementNames.qualifiedName(method.getDeclaringType())
                    + ", cannot be changed: it is an element of an annotation type; rename it with rename_symbol");
            }

            if (newName == null) {
                newName = oldName;
            }

            // Get current parameters
            String[] oldParamTypes = method.getParameterTypes();
            String[] oldParamNames = method.getParameterNames();
            String oldReturnType = Signature.toString(method.getReturnType());

            if (newReturnType == null) {
                newReturnType = oldReturnType;
            }

            // If no parameter changes specified, keep existing
            boolean varargs = Flags.isVarargs(method.getFlags());
            if (newParameters == null) {
                newParameters = new ArrayList<>();
                for (int i = 0; i < oldParamTypes.length; i++) {
                    newParameters.add(new ParameterInfo(
                        oldParamNames[i],
                        declaredType(oldParamTypes, i, varargs),
                        null,
                        null
                    ));
                }
            }

            // A renamed parameter is the parameter its oldName names
            for (ParameterInfo param : newParameters) {
                if (param.oldName != null && !List.of(oldParamNames).contains(param.oldName)) {
                    return ToolResponse.invalidParameter("newParameters", "oldName '" + param.oldName
                        + "' names no parameter of " + oldName + (oldParamNames.length == 0 ? ", which has none"
                            : ", whose parameters are " + String.join(", ", oldParamNames)));
                }
            }

            // An oldName names the parameter one new parameter renames
            Set<String> givenOldNames = new HashSet<>();
            for (ParameterInfo param : newParameters) {
                if (param.oldName != null && !givenOldNames.add(param.oldName)) {
                    return ToolResponse.invalidParameter("newParameters",
                        "oldName '" + param.oldName + "' is given to more than one parameter");
                }
            }

            // JDT's checks of the new parameter names, each refusal fatal at once
            // (ChangeSignatureProcessor.checkParameterNamesAndValues :789-804 with checkParameterName
            // :806-813, then checkForDuplicateParameterNames :1263-1275)
            RefactoringStatus findings = new RefactoringStatus();
            for (int i = 0; i < newParameters.size(); i++) {
                ParameterInfo param = newParameters.get(i);
                if (param.name.trim().isEmpty()) {
                    findings.addFatalError(Messages.format(
                        RefactoringCoreMessages.ChangeSignatureRefactoring_param_name_not_empty, Integer.toString(i + 1)));
                } else {
                    findings.merge(Checks.checkTempName(param.name, method));
                }
                if (findings.hasFatalError()) {
                    return refusal(findings, service);
                }
            }
            findings.merge(duplicateParameterNames(newParameters));
            if (findings.hasFatalError()) {
                return refusal(findings, service);
            }

            // Build parameter mapping for call site updates
            // Map old param index -> new param index (or -1 if removed)
            int[] paramMapping = buildParameterMapping(oldParamNames, newParameters);

            ICompilationUnit methodCu = method.getCompilationUnit();
            if (methodCu == null) {
                return ToolResponse.invalidParameter("method", "Cannot access method source");
            }

            // The method changes together with every method it overrides, every method that
            // overrides it and every method a subtype inherits together with it; a method of a
            // class file cannot change, so neither can a method related to one.
            List<IMethod> ripple = service.getSearchService().rippleOf(method);
            String binary = RelatedMethods.binaryRefusal(method, ripple);
            if (binary != null) {
                return ToolResponse.invalidParameter("position", "Method at position, " + oldName + " in "
                    + ElementNames.qualifiedName(method.getDeclaringType()) + ", cannot be changed: " + binary);
            }
            findings.merge(rippleParameterNames(newParameters, oldParamNames, ripple));

            // Every call site of each of them must be rewritten, each once. The calls are
            // searched in the project's sources only, where the edits are made.
            // A place the search could not confirm is never rewritten: it is reported, once.
            List<SearchMatch> references = new ArrayList<>();
            Set<String> referenced = new HashSet<>();
            List<SearchMatch> potential = new ArrayList<>();
            Set<String> potentialPlaces = new HashSet<>();
            for (IMethod declared : ripple) {
                SearchResult found = service.getSearchService().findAllReferencesInSources(
                    declared, SearchService.ALL_MATCHES);
                for (SearchMatch match : found.matches()) {
                    if (referenced.add(placeOf(match))) {
                        references.add(match);
                    }
                }
                for (SearchMatch match : found.potentialMatches()) {
                    if (potentialPlaces.add(placeOf(match))) {
                        potential.add(match);
                    }
                }
            }
            // A place the search for one of the methods confirms is no potential match, whatever
            // the search for another reports there
            potential.removeIf(match -> referenced.contains(placeOf(match)));
            // In source order, file by file: JDT's search reports a type's field initializers
            // before its methods (MatchLocator.reportMatching :3246-3300), and each related
            // method's places after those of the method searched before it.
            references.sort(SOURCE_ORDER);

            Change change = new Change(service, method, ripple, oldName, newName, oldReturnType, newReturnType,
                oldParamTypes, oldParamNames, newParameters, paramMapping);

            // 1. Each declaration, in the order of their types by file and position
            for (IMethod declared : ripple) {
                change.declaration(declared, declared.equals(method));
            }

            // 2. Every place the search reports: calls, enum constants, Javadoc references,
            // static imports (updated in step 5) and method references (reported, not rewritten)
            for (SearchMatch match : references) {
                change.reference(match);
            }

            // 3. The lambda expressions implementing any of the methods, whose parameters change
            // with theirs (JDT's search reports each as a declaration of the method), in source
            // order, file by file, whichever method's search reports them
            if (!method.isConstructor() && (change.parametersChanged() || change.parametersRenamed())) {
                List<SearchMatch> lambdas = new ArrayList<>();
                Set<String> lambdaPlaces = new HashSet<>();
                List<SearchMatch> potentialLambdas = new ArrayList<>();
                for (IMethod declared : ripple) {
                    SearchResult found = service.getSearchService().findDeclarationsInSources(
                        declared, SearchService.ALL_MATCHES);
                    for (SearchMatch match : found.matches()) {
                        if (isLambdaMethod(match) && lambdaPlaces.add(placeOf(match))) {
                            lambdas.add(match);
                        }
                    }
                    for (SearchMatch match : found.potentialMatches()) {
                        if (isLambdaMethod(match)) {
                            potentialLambdas.add(match);
                        }
                    }
                }
                // A lambda expression one method's search confirms is no potential match either
                for (SearchMatch match : potentialLambdas) {
                    if (!lambdaPlaces.contains(placeOf(match)) && potentialPlaces.add(placeOf(match))) {
                        potential.add(match);
                    }
                }
                lambdas.sort(SOURCE_ORDER);
                for (SearchMatch match : lambdas) {
                    change.lambda(match);
                }
            }

            // 4. A constructor without parameters given some: its implicit super() calls
            // (ChangeSignatureProcessor.modifyImplicitCallsToNoArgConstructor :1725-1737)
            if (method.isConstructor() && oldParamTypes.length == 0 && !newParameters.isEmpty()) {
                change.implicitSuperCalls(method.getDeclaringType());
            }

            // 5. The single static imports of a renamed method
            if (!newName.equals(oldName)) {
                change.staticImports();
            }

            Map<String, List<Map<String, Object>>> editsByFile = change.edits();
            Analysis analysis = change.analyze(potential);
            findings.merge(analysis.collisions());
            RefactoringInvoker.Findings found = RefactoringInvoker.findings(findings, service);
            if (!found.conflicts().isEmpty()) {
                return ToolResponse.invalidParameter("newParameters", String.join("; ", found.conflicts()));
            }
            List<Map<String, Object>> methodReferences = change.methodReferences;

            // Count total edits
            int totalEdits = editsByFile.values().stream()
                .mapToInt(List::size)
                .sum();

            boolean methodIsConstructor = method.isConstructor();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("oldName", oldName);
            data.put("newName", newName);
            if (!methodIsConstructor) {
                data.put("oldReturnType", oldReturnType);
                data.put("newReturnType", newReturnType);
            }
            data.put("oldParameterCount", oldParamTypes.length);
            data.put("newParameterCount", newParameters.size());
            data.put("newParameters", newParameters.stream()
                .map(p -> Map.of("name", p.name, "type", p.type))
                .toList());
            data.put("totalEdits", totalEdits);
            data.put("filesAffected", editsByFile.size());
            data.put("editsByFile", editsByFile);
            if (!analysis.problems().isEmpty()) {
                data.put("introducedProblems", analysis.problems());
            }

            // Method references cannot be automatically rewritten because the
            // functional-interface signature they bind to would no longer match.
            // Surface them as informational entries plus top-level warnings so the
            // AI consumer knows manual rewrite is required. JDT's warnings on the new names come first.
            List<String> warnings = new ArrayList<>(found.warnings());
            if (!methodReferences.isEmpty()) {
                data.put("methodReferences", methodReferences);
                Set<String> affectedFiles = new LinkedHashSet<>();
                for (Map<String, Object> ref : methodReferences) {
                    Object filePathObj = ref.get("filePath");
                    if (filePathObj != null) affectedFiles.add(filePathObj.toString());
                }
                for (String f : affectedFiles) {
                    warnings.add(f + " has method-reference call sites that cannot be " +
                        "automatically updated. Replace each with a lambda or refactor " +
                        "the functional-interface signature.");
                }
            }
            if (!warnings.isEmpty()) {
                data.put("warnings", warnings);
            }
            Map<String, Object> potentialMatches = potentialMatches(potential, potential.size(), service);
            if (potentialMatches != null) {
                data.put("potentialMatches", potentialMatches);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(totalEdits)
                .returnedCount(totalEdits)
                .suggestedNextTools(List.of(
                    "Apply the text edits to complete the signature change",
                    "get_diagnostics to verify no errors"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error changing method signature: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * The type of the old parameter at {@code index} as its declaration writes it: the last
     * parameter of a varargs method with its ellipsis ({@code String...}), as JDT's change-signature
     * refactoring names it.
     */
    private static String declaredType(String[] oldParamTypes, int index, boolean varargs) {
        String type = Signature.toString(oldParamTypes[index]);
        return varargs && index == oldParamTypes.length - 1 && type.endsWith("[]")
            ? type.substring(0, type.length() - 2) + "..." : type;
    }

    /** The refusal naming the conflicts {@code findings} holds, by place. */
    private static ToolResponse refusal(RefactoringStatus findings, IJdtService service) {
        return ToolResponse.invalidParameter("newParameters",
            String.join("; ", RefactoringInvoker.findings(findings, service).conflicts()));
    }

    /** ChangeSignatureProcessor.checkForDuplicateParameterNames :1263-1275: each name given twice, once. */
    private static RefactoringStatus duplicateParameterNames(List<ParameterInfo> newParameters) {
        RefactoringStatus result = new RefactoringStatus();
        Set<String> found = new HashSet<>();
        Set<String> doubled = new HashSet<>();
        for (ParameterInfo param : newParameters) {
            if (!found.add(param.name) && doubled.add(param.name)) {
                result.addFatalError(Messages.format(RefactoringCoreMessages.ChangeSignatureRefactoring_duplicate_name,
                    BasicElementLabels.getJavaElementName(param.name)));
            }
        }
        return result;
    }

    /**
     * ChangeSignatureProcessor.checkParameterNamesInRippleMethods :1404-1418: a name the change
     * gives that the method at position does not have now, which a method changed with it already
     * gives one of its parameters, is an error, at that method's name.
     */
    private static RefactoringStatus rippleParameterNames(List<ParameterInfo> newParameters, String[] oldParamNames,
                                                          List<IMethod> ripple) throws JavaModelException {
        Set<String> newNames = new LinkedHashSet<>();
        for (ParameterInfo param : newParameters) {
            newNames.add(param.name);
        }
        newNames.removeAll(List.of(oldParamNames));
        RefactoringStatus result = new RefactoringStatus();
        for (IMethod rippleMethod : ripple) {
            for (String paramName : rippleMethod.getParameterNames()) {
                if (newNames.contains(paramName)) {
                    result.addError(Messages.format(RefactoringCoreMessages.ChangeSignatureRefactoring_already_has,
                            new String[] {JavaElementUtil.createMethodSignature(rippleMethod),
                                BasicElementLabels.getJavaElementName(paramName)}),
                        JavaStatusContext.create(rippleMethod.getCompilationUnit(), rippleMethod.getNameRange()));
                }
            }
        }
        return result;
    }

    /**
     * Each old parameter's index in the new list, or -1 when it is removed: the new parameter
     * whose oldName names it, renamed, or else the one of its name.
     */
    private int[] buildParameterMapping(String[] oldNames, List<ParameterInfo> newParams) {
        int[] mapping = new int[oldNames.length];

        for (int oldIdx = 0; oldIdx < oldNames.length; oldIdx++) {
            mapping[oldIdx] = -1; // Default to removed

            // Find this param in new list
            for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
                if (oldNames[oldIdx].equals(newParams.get(newIdx).identity())) {
                    mapping[oldIdx] = newIdx;
                    break;
                }
            }
        }

        return mapping;
    }

    /** The old index of the parameter at {@code newIdx}, or -1 for a new parameter. */
    private static int oldIndexOf(int[] paramMapping, int newIdx) {
        for (int i = 0; i < paramMapping.length; i++) {
            if (paramMapping[i] == newIdx) {
                return i;
            }
        }
        return -1;
    }

    /** Whether a declaration match is that of a lambda expression. */
    private static boolean isLambdaMethod(SearchMatch match) {
        return match.getElement() instanceof IMethod method && method.isLambdaMethod();
    }

    /** A match's place: its file and offset. */
    private static String placeOf(SearchMatch match) {
        return (match.getResource() == null ? "" : match.getResource().getFullPath().toString()) + "@" + match.getOffset();
    }

    /** Matches by file, its location compared segment by segment, then by offset. */
    private static final Comparator<SearchMatch> SOURCE_ORDER = (a, b) -> {
        String[] left = locationSegments(a);
        String[] right = locationSegments(b);
        for (int i = 0; i < Math.min(left.length, right.length); i++) {
            int bySegment = left[i].compareTo(right[i]);
            if (bySegment != 0) {
                return bySegment;
            }
        }
        int byFile = Integer.compare(left.length, right.length);
        return byFile != 0 ? byFile : Integer.compare(a.getOffset(), b.getOffset());
    };

    private static String[] locationSegments(SearchMatch match) {
        IPath location = match.getResource() == null ? null : match.getResource().getLocation();
        return location == null ? new String[0] : location.segments();
    }

    /**
     * Whether {@code node} is the name of a class that declares no constructor. JDT's search
     * reports the implicit super() call of its default constructor at that name, with the synthetic
     * flag of the class's last method: true while the default constructor is its only method, false
     * once the class declares one (ConstructorLocator.newDeclarationMatch :298-305).
     */
    private static boolean namesClassWithoutConstructor(ASTNode node) {
        if (!(node instanceof SimpleName name) || !(name.getParent() instanceof TypeDeclaration type)
                || type.getName() != name) {
            return false;
        }
        for (Object body : type.bodyDeclarations()) {
            if (body instanceof MethodDeclaration declaration && declaration.isConstructor()) {
                return false;
            }
        }
        return true;
    }

    private MethodDeclaration findMethodDeclaration(CompilationUnit ast, IMethod method) throws JavaModelException {
        final MethodDeclaration[] result = {null};
        final String methodName = method.getElementName();

        ISourceRange nameRange = method.getNameRange();
        final int nameOffset = nameRange != null ? nameRange.getOffset() : -1;

        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (methodName.equals(node.getName().getIdentifier())) {
                    if (nameOffset >= 0 && node.getName().getStartPosition() == nameOffset) {
                        result[0] = node;
                        return false;
                    }
                    if (result[0] == null) {
                        result[0] = node;
                    }
                }
                return true;
            }
        });

        return result[0];
    }

    /** The arguments list property for each rewritable call-site node type. */
    private static ChildListPropertyDescriptor argumentsProperty(ASTNode node) {
        if (node instanceof MethodInvocation) return MethodInvocation.ARGUMENTS_PROPERTY;
        if (node instanceof SuperMethodInvocation) return SuperMethodInvocation.ARGUMENTS_PROPERTY;
        if (node instanceof ClassInstanceCreation) return ClassInstanceCreation.ARGUMENTS_PROPERTY;
        if (node instanceof ConstructorInvocation) return ConstructorInvocation.ARGUMENTS_PROPERTY;
        if (node instanceof EnumConstantDeclaration) return EnumConstantDeclaration.ARGUMENTS_PROPERTY;
        return SuperConstructorInvocation.ARGUMENTS_PROPERTY;
    }

    /**
     * The renameable name node of a call site, or null where the call form has
     * none (this/super constructor invocations) or where the "name" is the
     * constructed type (class instance creation, enum constant), which this tool
     * does not rename at call sites.
     */
    private static SimpleName invocationName(ASTNode node) {
        if (node instanceof MethodInvocation mi) return mi.getName();
        if (node instanceof SuperMethodInvocation smi) return smi.getName();
        return null;
    }

    private static boolean isCall(ASTNode node) {
        return node instanceof MethodInvocation || node instanceof SuperMethodInvocation
            || node instanceof ClassInstanceCreation || node instanceof ConstructorInvocation
            || node instanceof SuperConstructorInvocation || node instanceof EnumConstantDeclaration;
    }

    private static List<?> callArguments(ASTNode node) {
        if (node instanceof MethodInvocation mi) return mi.arguments();
        if (node instanceof SuperMethodInvocation smi) return smi.arguments();
        if (node instanceof ClassInstanceCreation cic) return cic.arguments();
        if (node instanceof ConstructorInvocation ci) return ci.arguments();
        if (node instanceof SuperConstructorInvocation sci) return sci.arguments();
        if (node instanceof EnumConstantDeclaration ecd) return ecd.arguments();
        return List.of();
    }

    /**
     * The node the change updates for {@code node}, the node a match selects: a name or type in a
     * creation, an enum constant, a method reference, a Javadoc reference or the name of a call
     * stands for that node; an anonymous class body for its creation or enum constant.
     */
    private static ASTNode occurrence(ASTNode node) {
        ASTNode current = node;
        while ((current instanceof Name || current instanceof Type)
                && (current.getParent() instanceof Name || current.getParent() instanceof Type)) {
            current = current.getParent();
        }
        ASTNode parent = current.getParent();
        if ((current instanceof Name || current instanceof Type)
                && (parent instanceof EnumConstantDeclaration || parent instanceof MethodInvocation
                    || parent instanceof SuperMethodInvocation || parent instanceof ClassInstanceCreation
                    || parent instanceof MethodReference || parent instanceof MethodRef
                    || parent instanceof MemberRef)) {
            return parent;
        }
        if (current instanceof AnonymousClassDeclaration
                && (parent instanceof ClassInstanceCreation || parent instanceof EnumConstantDeclaration)) {
            return parent;
        }
        return current;
    }

    /** The nearest node of {@code type} enclosing {@code node}, {@code node} included; or null. */
    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> type) {
        for (ASTNode current = node; current != null; current = current.getParent()) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
        }
        return null;
    }

    /** The index just past the {@code ch} that {@code from} reaches across whitespace and comments. */
    private static int past(String source, int from, char ch) {
        int i = from;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (source.startsWith("//", i)) {
                int lineEnd = source.indexOf('\n', i);
                i = lineEnd < 0 ? source.length() : lineEnd + 1;
            } else if (source.startsWith("/*", i)) {
                int close = source.indexOf("*/", i + 2);
                if (close < 0) {
                    break;
                }
                i = close + 2;
            } else if (c == ch) {
                return i + 1;
            } else {
                break;
            }
        }
        throw new IllegalStateException("Expected '" + ch + "' at " + from + " in the source");
    }

    /** Whether {@code from} reaches {@code ch} across whitespace and comments. */
    private static boolean reaches(String source, int from, char ch) {
        try {
            past(source, from, ch);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    private static int end(ASTNode node) {
        return node.getStartPosition() + node.getLength();
    }

    /** {@code type} as a Javadoc reference names it: raw, without type arguments. */
    private static String erasure(String type) {
        return type.replaceAll("<.*>", "");
    }

    /** The text a call passes for {@code param}, a new parameter. */
    private static String argumentFor(ParameterInfo param) {
        return param.defaultValue != null ? param.defaultValue : "/* TODO: " + param.name + " */";
    }

    /**
     * A call whose arguments, from {@code start} to {@code end}, are rendered in a new order: each
     * piece the text of an old argument range, {@code int[] {start, end}}, or a new argument's text.
     * The arguments of removed parameters, the {@code dropped} ranges, are left out with every
     * change within them.
     */
    private record ReorderedCall(int start, int end, List<Object> pieces, List<int[]> dropped) {
    }

    /**
     * A place the change edits in a file: a span its shared rewrite renders (a declaration's
     * signature, or another place), or an insertion rendered on its own, the edit {@code insert}.
     */
    private record Place(int start, int end, boolean declaration, Map<String, Object> insert) {

        boolean isSpan() {
            return insert == null;
        }

        boolean holds(Place other) {
            return start <= other.start && other.end <= end;
        }
    }

    /**
     * One file's part of the change: its syntax tree, the one rewrite every span of it is rendered
     * from, so a place nested in another changes within the enclosing place's edit, and its places
     * in edit order.
     */
    private static final class FileChange {
        final String path;
        final String source;
        final CompilationUnit ast;
        final ASTRewrite rewrite;
        final List<Place> places = new ArrayList<>();
        /** The nodes already updated, each updated once however many matches select it. */
        final Set<ASTNode> nodes = new HashSet<>();
        /** The calls of the method by its simple name or a qualified one, renamed when its name changes. */
        final List<MethodInvocation> invocations = new ArrayList<>();
        /** The names this change replaces. */
        final Set<SimpleName> renamedNames = new HashSet<>();
        /** The static imports the search reports naming one of the methods. */
        final Set<ImportDeclaration> reportedImports = new LinkedHashSet<>();
        /** Whether the file holds a changed declaration or lambda expression. */
        boolean declaring;
        /** The calls whose arguments are rendered in a new order when the edits are made. */
        final List<ReorderedCall> reorderedCalls = new ArrayList<>();
        /** The parameters this change renames in the file's declarations and lambda expressions. */
        final List<RenamedParameter> renamedParameters = new ArrayList<>();
        /** The file's text with every edit of the change applied, once the edits are made. */
        Rendered after;

        FileChange(String path, String source, CompilationUnit ast) {
            this.path = path;
            this.source = source;
            this.ast = ast;
            this.rewrite = ASTRewrite.create(ast.getAST());
        }

        String position(int offset) {
            return path + ":" + (ast.getLineNumber(offset) - 1) + ":" + ast.getColumnNumber(offset);
        }

        /** The file's text with every edit applied: its source when the change edits nothing in it. */
        Rendered after() {
            if (after == null) {
                after = new Rendered(source);
                after.keep(0, source.length());
            }
            return after;
        }
    }

    /**
     * A parameter renamed in the declaration or lambda expression whose edit starts at
     * {@code placeStart}: the parameter at {@code newIndex} of the new list, named {@code newName},
     * and the references to it in the body that the change renames.
     */
    private record RenamedParameter(int placeStart, boolean lambda, int newIndex, String newName,
                                    List<SimpleName> references) {
    }

    /** A compile error or note the change introduces, at {@code offset} of the file as it is now. */
    private record Problem(FileChange file, int offset, String message) {

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("filePath", file.path);
            map.put("line", file.ast.getLineNumber(offset) - 1);
            map.put("column", file.ast.getColumnNumber(offset));
            map.put("message", message);
            return map;
        }
    }

    /**
     * What the applied edits do beyond what the change asks for: {@code collisions}, the renames
     * that change what a name refers to, and {@code problems}, every compile error they introduce
     * and each removed parameter a body still uses, by file, then line and column, then message.
     */
    private record Analysis(RefactoringStatus collisions, List<Map<String, Object>> problems) {
    }

    /**
     * A piece of rendered text: the source text {@code [from, to)} kept as it is, or the text that
     * stands in its place, {@code length} characters at {@code at} of the rendered text.
     */
    private record Segment(int from, int to, int at, int length, boolean kept) {

        Segment shifted(int by) {
            return new Segment(from, to, at + by, length, kept);
        }
    }

    /**
     * Text rendered from a file's source, piece by piece, each piece naming the source text it
     * keeps or stands in place of, so a place in the text maps to the source and back.
     */
    private static final class Rendered implements TextEditConverter.Slices.Sink {
        final String source;
        final StringBuilder text = new StringBuilder();
        final List<Segment> segments = new ArrayList<>();

        Rendered(String source) {
            this.source = source;
        }

        @Override
        public void keep(int from, int to) {
            if (from < to) {
                segments.add(new Segment(from, to, text.length(), to - from, true));
                text.append(source, from, to);
            }
        }

        @Override
        public void replace(int from, int to, String replacement) {
            segments.add(new Segment(from, to, text.length(), replacement.length(), false));
            text.append(replacement);
        }

        /** Appends {@code other}, rendered from the same source. */
        void append(Rendered other) {
            int base = text.length();
            for (Segment segment : other.segments) {
                segments.add(segment.shifted(base));
            }
            text.append(other.text);
        }

        /**
         * The source offset that {@code offset} of this text stands for: the same character where
         * the source is kept, the start of the source text a replacement stands in place of.
         */
        int sourceOffset(int offset) {
            for (Segment segment : segments) {
                if (segment.at() <= offset && offset < segment.at() + segment.length()) {
                    return segment.kept() ? segment.from() + offset - segment.at() : segment.from();
                }
            }
            return segments.isEmpty() ? 0 : segments.get(segments.size() - 1).to();
        }

        /**
         * The offsets of this text where the source offset {@code offset} may begin, in increasing
         * order: the same character where the source is kept, or the start of a replacement of
         * source text starting there; none where the change leaves that source text out.
         */
        TreeSet<Integer> offsetsOf(int offset) {
            TreeSet<Integer> offsets = new TreeSet<>();
            for (Segment segment : segments) {
                if (segment.kept() ? segment.from() <= offset && offset < segment.to() : segment.from() == offset) {
                    offsets.add(segment.kept() ? segment.at() + offset - segment.from() : segment.at());
                }
            }
            return offsets;
        }
    }

    /** A sink that keeps nothing: the text of an argument the change leaves out. */
    private static final TextEditConverter.Slices.Sink DISCARD = new TextEditConverter.Slices.Sink() {
        @Override
        public void keep(int from, int to) {
        }

        @Override
        public void replace(int from, int to, String text) {
        }
    };

    /** The edits of one signature change, file by file, in the order of each file's first edit. */
    private final class Change {

        private final IJdtService service;
        private final Set<IMethod> ripple;
        private final boolean constructor;
        private final boolean varargs;
        private final String oldName;
        private final String newName;
        private final String oldReturnType;
        private final String newReturnType;
        private final String[] oldParamTypes;
        private final String[] oldParamNames;
        private final List<ParameterInfo> newParameters;
        private final int[] paramMapping;
        /** Whether a kept parameter moves before one it followed, so calls re-render their arguments. */
        private final boolean reordered;
        private final Map<String, FileChange> files = new LinkedHashMap<>();
        private final List<FileChange> edited = new ArrayList<>();
        final List<Map<String, Object>> methodReferences = new ArrayList<>();
        /** JDT's notes on the removed parameters bodies still use. */
        private final List<Problem> notes = new ArrayList<>();

        Change(IJdtService service, IMethod method, List<IMethod> ripple, String oldName, String newName,
               String oldReturnType, String newReturnType, String[] oldParamTypes, String[] oldParamNames,
               List<ParameterInfo> newParameters, int[] paramMapping) throws JavaModelException {
            this.service = service;
            this.ripple = new HashSet<>(ripple);
            this.constructor = method.isConstructor();
            this.varargs = Flags.isVarargs(method.getFlags());
            this.oldName = oldName;
            this.newName = newName;
            this.oldReturnType = oldReturnType;
            this.newReturnType = newReturnType;
            this.oldParamTypes = oldParamTypes;
            this.oldParamNames = oldParamNames;
            this.newParameters = newParameters;
            this.paramMapping = paramMapping;
            boolean moved = false;
            int lastKept = -1;
            for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                int oldIdx = oldIndexOf(paramMapping, newIdx);
                if (oldIdx >= 0) {
                    moved |= oldIdx < lastKept;
                    lastKept = oldIdx;
                }
            }
            this.reordered = moved;
        }

        /** Whether the parameter list changes: a parameter added, removed, moved or of another type. */
        boolean parametersChanged() {
            if (newParameters.size() != oldParamTypes.length) {
                return true;
            }
            for (int i = 0; i < newParameters.size(); i++) {
                if (oldIndexOf(paramMapping, i) != i || !newParameters.get(i).type.equals(oldType(i))) {
                    return true;
                }
            }
            return false;
        }

        /** The type of the old parameter at {@code oldIdx} as the method at position declares it. */
        private String oldType(int oldIdx) {
            return declaredType(oldParamTypes, oldIdx, varargs);
        }

        /** Whether a parameter is renamed: kept, under a new name. */
        boolean parametersRenamed() {
            for (int oldIdx = 0; oldIdx < oldParamNames.length; oldIdx++) {
                if (renamedTo(oldIdx) != null) {
                    return true;
                }
            }
            return false;
        }

        /** The new name of the parameter at {@code oldIdx} when it is renamed; else null. */
        private String renamedTo(int oldIdx) {
            int newIdx = oldIdx < paramMapping.length ? paramMapping[oldIdx] : -1;
            if (newIdx < 0) {
                return null;
            }
            String name = newParameters.get(newIdx).name;
            return name.equals(oldParamNames[oldIdx]) ? null : name;
        }

        /**
         * {@code parameter} as written, its name {@code name}: its modifiers, type and extra
         * dimensions kept.
         */
        private String withName(FileChange file, VariableDeclaration parameter, String name) {
            return file.source.substring(parameter.getStartPosition(), parameter.getName().getStartPosition())
                + name + file.source.substring(end(parameter.getName()), end(parameter));
        }

        /**
         * {@code parameter} as written, its type {@code type} and its name {@code name}: its
         * modifiers and annotations kept, varargs when {@code type} ends in an ellipsis (with the
         * annotations of its ellipsis when it was varargs already), and without the dimensions
         * written after its name, as ChangeSignatureProcessor.changeParamgumentType :2322-2330 sets
         * the varargs property, replaces the type with the ellipsis stripped and removes the
         * extra dimensions.
         */
        private String withTypeAndName(FileChange file, SingleVariableDeclaration parameter, String type, String name) {
            boolean newVarargs = type.endsWith("...");
            String typeText = newVarargs ? type.substring(0, type.length() - 3) : type;
            int typeEnd = end(parameter.getType());
            int beforeName = typeEnd;
            String ellipsis = newVarargs ? "..." : "";
            if (parameter.isVarargs()) {
                @SuppressWarnings("unchecked")
                List<ASTNode> annotations = parameter.varargsAnnotations();
                int from = annotations.isEmpty() ? typeEnd : end(annotations.get(annotations.size() - 1));
                beforeName = past(file.source, from, '.') + 2;
                if (!file.source.startsWith("...", beforeName - 3)) {
                    throw new IllegalStateException("No ellipsis after the type of the parameter " + name
                        + " at " + file.position(parameter.getStartPosition()));
                }
                if (newVarargs) {
                    ellipsis = file.source.substring(typeEnd, beforeName);
                }
            }
            return file.source.substring(parameter.getStartPosition(), parameter.getType().getStartPosition())
                + typeText + ellipsis + file.source.substring(beforeName, parameter.getName().getStartPosition())
                + name;
        }

        /**
         * Renames {@code parameter}, of the declaration or lambda expression whose body is
         * {@code body}, to {@code name} where it is referred to: the @param tag of {@code javadoc}
         * naming it (ChangeSignatureProcessor.changeJavadocTags :2648-2656), then each reference in
         * {@code body} (TempOccurrenceAnalyzer, as changeParamgumentName :2306-2318 finds them),
         * each a place of its own, in source order. Answers the references renamed in the body.
         */
        private List<SimpleName> renameReferences(FileChange file, VariableDeclaration parameter, ASTNode body,
                                                  Javadoc javadoc, String name) {
            String old = parameter.getName().getIdentifier();
            if (javadoc != null) {
                for (Object tag : javadoc.tags()) {
                    if (tag instanceof TagElement element && TagElement.TAG_PARAM.equals(element.getTagName())
                            && !element.fragments().isEmpty() && element.fragments().get(0) instanceof SimpleName named
                            && named.getIdentifier().equals(old)) {
                        renameName(file, named, name);
                    }
                }
            }
            List<SimpleName> references = references(file, parameter, body);
            for (SimpleName reference : references) {
                renameName(file, reference, name);
            }
            return references;
        }

        /** The references to {@code parameter} in {@code body}, in source order; none without a body. */
        private static List<SimpleName> references(FileChange file, VariableDeclaration parameter, ASTNode body) {
            List<SimpleName> references = new ArrayList<>();
            if (body == null) {
                return references;
            }
            String old = parameter.getName().getIdentifier();
            IVariableBinding binding = parameter.resolveBinding();
            if (binding == null) {
                throw new IllegalStateException("The compiler resolves no binding for the parameter " + old
                    + " at " + file.position(parameter.getStartPosition()));
            }
            body.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    if (node.getIdentifier().equals(old) && node.resolveBinding() instanceof IVariableBinding variable
                            && variable.isEqualTo(binding)) {
                        references.add(node);
                    }
                    return true;
                }
            });
            return references;
        }

        /**
         * JDT's note for each parameter of {@code declared} (a declaration or lambda expression)
         * that the change removes while its body still uses it, at its first use in source order
         * (ChangeSignatureProcessor.checkIfDeletedParametersUsed :2340-2357, which reports one of
         * the uses TempOccurrenceAnalyzer collects in a hash set).
         */
        private void removedParametersUsed(FileChange file, ASTNode declared, List<? extends VariableDeclaration> parameters,
                                           ASTNode body) {
            for (int oldIdx = 0; oldIdx < Math.min(parameters.size(), paramMapping.length); oldIdx++) {
                if (paramMapping[oldIdx] >= 0) {
                    continue;
                }
                VariableDeclaration parameter = parameters.get(oldIdx);
                List<SimpleName> uses = references(file, parameter, body);
                if (!uses.isEmpty()) {
                    notes.add(new Problem(file, uses.get(0).getStartPosition(), Messages.format(
                        RefactoringCoreMessages.ChangeSignatureRefactoring_parameter_used, new String[] {
                            BasicElementLabels.getJavaElementName(parameter.getName().getIdentifier()),
                            BasicElementLabels.getJavaElementName(oldName),
                            BasicElementLabels.getJavaElementName(fullTypeName(declared))})));
                }
            }
        }

        private void renameName(FileChange file, SimpleName node, String name) {
            file.rewrite.replace(node, placeholder(file, name, ASTNode.SIMPLE_NAME), null);
            add(file, new Place(node.getStartPosition(), end(node), false, null));
        }

        private FileChange file(ICompilationUnit unit) throws JavaModelException {
            String path = service.getPathUtils().formatPath(Path.of(unit.getResource().getLocation().toOSString()));
            FileChange file = files.get(path);
            if (file == null) {
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(unit);
                parser.setResolveBindings(true);
                file = new FileChange(path, unit.getSource(), (CompilationUnit) parser.createAST(Deadline.monitor()));
                files.put(path, file);
            }
            return file;
        }

        private void add(FileChange file, Place place) {
            if (file.places.isEmpty()) {
                edited.add(file);
            }
            file.places.add(place);
        }

        private ASTNode placeholder(FileChange file, String text, int nodeType) {
            return file.rewrite.createStringPlaceholder(text, nodeType);
        }

        /**
         * The declaration of {@code declared}: from its return type (its name, for a constructor)
         * to the end of its parameter list. The name and a changed return type are replaced, and
         * the parameter list is rebuilt through a ListRewrite, so separators and spacing render by
         * construction. Every declaration keeps each kept parameter as it declares it, modifiers
         * and annotations included, with its own type unless that type is changed (an overrider
         * declares {@code String} where the method it overrides declares {@code T}); the method at
         * position gives it the requested name, a method changed with it its own name unless it is
         * renamed. A new parameter is the requested one.
         */
        void declaration(IMethod declared, boolean atPosition) throws JavaModelException {
            FileChange file = file(declared.getCompilationUnit());
            file.declaring = true;
            MethodDeclaration methodDecl = findMethodDeclaration(file.ast, declared);
            if (methodDecl == null) {
                throw new IllegalStateException("No declaration of " + declared.getElementName() + " in " + file.path);
            }
            ASTRewrite rewrite = file.rewrite;
            AST factory = file.ast.getAST();
            if (!newName.equals(oldName)) {
                rewrite.replace(methodDecl.getName(), placeholder(file, newName, ASTNode.SIMPLE_NAME), null);
            }
            if (!constructor && methodDecl.getReturnType2() != null && !newReturnType.equals(oldReturnType)) {
                rewrite.replace(methodDecl.getReturnType2(), placeholder(file, newReturnType, ASTNode.SIMPLE_TYPE), null);
            }
            ListRewrite paramsRewrite = rewrite.getListRewrite(methodDecl, MethodDeclaration.PARAMETERS_PROPERTY);
            @SuppressWarnings("unchecked")
            List<SingleVariableDeclaration> declaredParams = methodDecl.parameters();
            for (SingleVariableDeclaration oldParam : declaredParams) {
                paramsRewrite.remove(oldParam, null);
            }
            // A renamed parameter takes its new name where this declaration names it as the method
            // at position does (ChangeSignatureProcessor.changeParamgumentName :2306-2310)
            Map<SingleVariableDeclaration, String> renamed = new LinkedHashMap<>();
            for (int oldIdx = 0; oldIdx < declaredParams.size(); oldIdx++) {
                String renamedTo = renamedTo(oldIdx);
                SingleVariableDeclaration own = declaredParams.get(oldIdx);
                if (renamedTo != null && own.getName().getIdentifier().equals(oldParamNames[oldIdx])) {
                    renamed.put(own, renamedTo);
                }
            }
            for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                ParameterInfo param = newParameters.get(newIdx);
                int oldIdx = oldIndexOf(paramMapping, newIdx);
                if (oldIdx >= 0 && oldIdx < declaredParams.size()) {
                    // A kept parameter keeps its modifiers and annotations as written; only its name
                    // (ChangeSignatureProcessor.changeParamgumentName :2317) and a changed type change.
                    // The method at position takes the requested name, another its own unless renamed.
                    SingleVariableDeclaration own = declaredParams.get(oldIdx);
                    String name = atPosition ? param.name : renamed.getOrDefault(own, own.getName().getIdentifier());
                    String text = param.type.equals(oldType(oldIdx))
                        ? withName(file, own, name) : withTypeAndName(file, own, param.type, name);
                    paramsRewrite.insertLast(placeholder(file, text, ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                    continue;
                }
                paramsRewrite.insertLast(newParameter(factory, rewrite, param.type, param.name), null);
            }

            // From the return type, or a constructor's name, to the parameter list's closing
            // parenthesis, wherever whitespace or comments put it
            int start = methodDecl.getReturnType2() != null ? methodDecl.getReturnType2().getStartPosition()
                : methodDecl.getName().getStartPosition();
            int parametersFrom = declaredParams.isEmpty()
                ? past(file.source, end(methodDecl.getName()), '(')
                : end(declaredParams.get(declaredParams.size() - 1));
            file.nodes.add(methodDecl);
            add(file, new Place(start, past(file.source, parametersFrom, ')'), true, null));
            for (Map.Entry<SingleVariableDeclaration, String> parameter : renamed.entrySet()) {
                List<SimpleName> references = renameReferences(file, parameter.getKey(), methodDecl.getBody(),
                    methodDecl.getJavadoc(), parameter.getValue());
                file.renamedParameters.add(new RenamedParameter(start, false,
                    paramMapping[declaredParams.indexOf(parameter.getKey())], parameter.getValue(), references));
            }
            removedParametersUsed(file, methodDecl, declaredParams, methodDecl.getBody());
        }

        /**
         * The place a reference match selects. An implicit super() call the search reports
         * (synthetic, or at the name of a class declaring no constructor, the call of its default
         * constructor) is updated by {@link #implicitSuperCalls}; a call, an enum constant and a
         * Javadoc reference are rewritten; a static import is updated by {@link #staticImports};
         * a method reference is reported, never rewritten. Any other node fails the change, as
         * JDT's refactoring reports an error for it (NullOccurrenceUpdate :2979-2993): leaving it
         * would leave the code not compiling.
         */
        void reference(SearchMatch match) throws JavaModelException {
            if (match instanceof MethodReferenceMatch reference && reference.isSynthetic()) {
                return;
            }
            ICompilationUnit unit = MatchResolver.resolveCu(match);
            if (unit == null) {
                throw new IllegalStateException("No compilation unit for the reference at " + placeOf(match));
            }
            FileChange file = file(unit);
            ASTNode node = occurrence(NodeFinder.perform(file.ast, match.getOffset(), match.getLength()));
            if (constructor && namesClassWithoutConstructor(node)) {
                return;
            }
            if (!file.nodes.add(node)) {
                return;
            }
            if (node instanceof MethodReference) {
                methodReference(file, node);
            } else if (isCall(node)) {
                call(file, node);
            } else if (node instanceof MethodRef || node instanceof MemberRef) {
                javadoc(file, node);
            } else if (enclosing(node, ImportDeclaration.class) instanceof ImportDeclaration imported) {
                file.reportedImports.add(imported);
            } else {
                throw new IllegalStateException("Cannot update the reference at " + file.position(match.getOffset())
                    + ": " + node.getClass().getSimpleName() + " " + node);
            }
        }

        /**
         * A method or constructor reference: the functional-interface signature it binds to
         * would no longer match, so it is reported for a manual rewrite.
         */
        private void methodReference(FileChange file, ASTNode found) {
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("filePath", file.path);
            ref.put("line", file.ast.getLineNumber(found.getStartPosition()) - 1);
            ref.put("column", file.ast.getColumnNumber(found.getStartPosition()));
            ref.put("startOffset", found.getStartPosition());
            ref.put("endOffset", end(found));
            ref.put("text", found.toString().trim());
            ref.put("reason", "Method reference cannot be automatically rewritten; the " +
                "functional-interface signature would no longer match. Replace with a " +
                "lambda or refactor manually.");
            methodReferences.add(ref);
        }

        /**
         * A call, an explicit constructor call or an enum constant: its arguments follow the new
         * parameter list (a new parameter passes its default value, or a TODO marker), and a
         * call by name takes the new name. While kept parameters keep their order, only the
         * arguments of removed parameters are removed and new ones inserted, so an argument
         * holding another changed place changes with it; a reordering renders the kept arguments
         * in their new order, each with the changes within it, and leaves out the arguments of
         * removed parameters with the changes within them. The edit spans the whole call (an enum
         * constant from its name to its arguments' closing parenthesis).
         */
        private void call(FileChange file, ASTNode node) {
            @SuppressWarnings("unchecked")
            List<Expression> oldArgs = (List<Expression>) callArguments(node);
            ListRewrite argsRewrite = file.rewrite.getListRewrite(node, argumentsProperty(node));
            int lastParam = oldParamTypes.length - 1;
            if (reordered && !oldArgs.isEmpty()) {
                // The arguments are rendered in their new order when the edits are made, each from
                // its own text with every change within it (a renamed parameter, a call of the
                // method itself) applied; a varargs parameter passes every argument from its
                // position on, as written. A new parameter passes its default value. The argument
                // of a removed parameter is left out, every change within it too.
                List<Object> pieces = new ArrayList<>();
                for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                    int oldIdx = oldIndexOf(paramMapping, newIdx);
                    if (oldIdx >= 0 && oldIdx < oldArgs.size()) {
                        pieces.add(argumentRange(oldArgs, oldIdx, lastParam));
                    } else if (oldIdx < 0) {
                        pieces.add(argumentFor(newParameters.get(newIdx)));
                    }
                }
                List<int[]> dropped = new ArrayList<>();
                for (int oldIdx = 0; oldIdx < Math.min(paramMapping.length, oldArgs.size()); oldIdx++) {
                    if (paramMapping[oldIdx] < 0) {
                        dropped.add(argumentRange(oldArgs, oldIdx, lastParam));
                    }
                }
                file.reorderedCalls.add(new ReorderedCall(oldArgs.get(0).getStartPosition(),
                    end(oldArgs.get(oldArgs.size() - 1)), pieces, dropped));
            } else if (reordered) {
                for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                    if (oldIndexOf(paramMapping, newIdx) < 0) {
                        argsRewrite.insertLast(placeholder(file, argumentFor(newParameters.get(newIdx)), ASTNode.SIMPLE_NAME), null);
                    }
                }
            } else {
                for (int i = 0; i < oldArgs.size(); i++) {
                    int oldIdx = varargs ? Math.min(i, lastParam) : i;
                    if (oldIdx < paramMapping.length && paramMapping[oldIdx] < 0) {
                        argsRewrite.remove(oldArgs.get(i), null);
                    }
                }
                ASTNode previous = null;
                for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                    int oldIdx = oldIndexOf(paramMapping, newIdx);
                    if (oldIdx >= 0) {
                        int lastArg = varargs && oldIdx == lastParam ? oldArgs.size() - 1 : oldIdx;
                        if (lastArg >= 0 && lastArg < oldArgs.size()) {
                            previous = oldArgs.get(lastArg);
                        }
                        continue;
                    }
                    ASTNode added = placeholder(file, argumentFor(newParameters.get(newIdx)), ASTNode.SIMPLE_NAME);
                    if (previous == null) {
                        argsRewrite.insertFirst(added, null);
                    } else {
                        argsRewrite.insertAfter(added, previous, null);
                    }
                    previous = added;
                }
            }
            SimpleName name = invocationName(node);
            if (name != null && !newName.equals(oldName)) {
                file.rewrite.replace(name, placeholder(file, newName, ASTNode.SIMPLE_NAME), null);
                file.renamedNames.add(name);
            }
            if (node instanceof MethodInvocation invocation) {
                file.invocations.add(invocation);
            }
            if (node instanceof EnumConstantDeclaration constant) {
                int nameEnd = end(constant.getName());
                int end = !oldArgs.isEmpty() ? past(file.source, end(oldArgs.get(oldArgs.size() - 1)), ')')
                    : reaches(file.source, nameEnd, '(') ? past(file.source, past(file.source, nameEnd, '('), ')')
                    : nameEnd;
                add(file, new Place(constant.getName().getStartPosition(), end, false, null));
            } else {
                add(file, new Place(node.getStartPosition(), end(node), false, null));
            }
        }

        /**
         * The source range of the argument a call passes for the old parameter at {@code oldIdx}:
         * for a varargs parameter, every argument from its position on.
         */
        private int[] argumentRange(List<Expression> oldArgs, int oldIdx, int lastParam) {
            Expression last = varargs && oldIdx == lastParam ? oldArgs.get(oldArgs.size() - 1) : oldArgs.get(oldIdx);
            return new int[] {oldArgs.get(oldIdx).getStartPosition(), end(last)};
        }

        /**
         * A Javadoc reference: a method reference written with parentheses takes the new
         * parameter list (types as Javadoc writes them, raw; a parameter name only where the
         * reference names its first parameter, as JDT's DocReferenceUpdate :2846-2944 writes
         * it), and either form takes the new name.
         */
        private void javadoc(FileChange file, ASTNode node) {
            SimpleName name;
            if (node instanceof MethodRef ref) {
                name = ref.getName();
                if (parametersChanged() || parametersRenamed()) {
                    @SuppressWarnings("unchecked")
                    List<MethodRefParameter> old = ref.parameters();
                    ListRewrite list = file.rewrite.getListRewrite(ref, MethodRef.PARAMETERS_PROPERTY);
                    for (MethodRefParameter parameter : old) {
                        list.remove(parameter, null);
                    }
                    boolean named = !old.isEmpty() && old.get(0).getName() != null;
                    for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                        ParameterInfo param = newParameters.get(newIdx);
                        int oldIdx = oldIndexOf(paramMapping, newIdx);
                        String text;
                        if (oldIdx >= 0 && oldIdx < old.size()) {
                            MethodRefParameter own = old.get(oldIdx);
                            // A name the reference gives the parameter takes its new name
                            // (DocReferenceUpdate.changeParamgumentName :2910-2918)
                            String renamedTo = own.getName() == null ? null : renamedTo(oldIdx);
                            boolean sameType = param.type.equals(oldType(oldIdx));
                            if (sameType && renamedTo == null) {
                                text = file.source.substring(own.getStartPosition(), end(own));
                            } else {
                                String type = sameType
                                    ? file.source.substring(own.getStartPosition(), end(own.getType()))
                                        + (own.isVarargs() ? "..." : "")
                                    : erasure(param.type);
                                String ownName = own.getName() == null ? null
                                    : renamedTo != null ? renamedTo : own.getName().getIdentifier();
                                text = type + (ownName != null ? " " + ownName : "");
                            }
                        } else {
                            text = erasure(param.type) + (named ? " " + param.name : "");
                        }
                        list.insertLast(placeholder(file, text, ASTNode.METHOD_REF_PARAMETER), null);
                    }
                }
            } else {
                name = ((MemberRef) node).getName();
            }
            if (!newName.equals(oldName)) {
                file.rewrite.replace(name, placeholder(file, newName, ASTNode.SIMPLE_NAME), null);
                file.renamedNames.add(name);
            }
            add(file, new Place(node.getStartPosition(), end(node), false, null));
        }

        /**
         * A lambda expression implementing one of the methods: its parameters follow the new
         * list, each kept one as written (a typed one with the new type when its type changes), a
         * new one typed when the lambda types its parameters, else by name alone; parentheses are
         * added when there come to be other than one untyped parameter. The edit spans the
         * parameters, from the lambda's start (ChangeSignatureProcessor.LambdaExpressionUpdate
         * :2388-2442).
         */
        void lambda(SearchMatch match) throws JavaModelException {
            ICompilationUnit unit = MatchResolver.resolveCu(match);
            if (unit == null) {
                throw new IllegalStateException("No compilation unit for the lambda expression at " + placeOf(match));
            }
            FileChange file = file(unit);
            file.declaring = true;
            LambdaExpression lambda = lambdaAt(file, match.getOffset());
            if (!file.nodes.add(lambda)) {
                return;
            }
            @SuppressWarnings("unchecked")
            List<VariableDeclaration> old = lambda.parameters();
            boolean typed = !old.isEmpty() && old.get(0) instanceof SingleVariableDeclaration;
            ListRewrite list = file.rewrite.getListRewrite(lambda, LambdaExpression.PARAMETERS_PROPERTY);
            for (VariableDeclaration parameter : old) {
                list.remove(parameter, null);
            }
            // A renamed parameter takes its new name where the lambda expression names it as the
            // method at position does (AbstractDeclarationUpdate.changeParamgumentName :2306-2310)
            Map<VariableDeclaration, String> renamed = new LinkedHashMap<>();
            for (int oldIdx = 0; oldIdx < old.size(); oldIdx++) {
                String renamedTo = renamedTo(oldIdx);
                if (renamedTo != null && old.get(oldIdx).getName().getIdentifier().equals(oldParamNames[oldIdx])) {
                    renamed.put(old.get(oldIdx), renamedTo);
                }
            }
            for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                ParameterInfo param = newParameters.get(newIdx);
                int oldIdx = oldIndexOf(paramMapping, newIdx);
                if (oldIdx >= 0 && oldIdx < old.size()) {
                    VariableDeclaration own = old.get(oldIdx);
                    String name = renamed.getOrDefault(own, own.getName().getIdentifier());
                    if (own instanceof SingleVariableDeclaration
                            && !param.type.equals(oldType(oldIdx))) {
                        list.insertLast(placeholder(file, param.type + " " + name,
                            ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                    } else {
                        list.insertLast(placeholder(file, withName(file, own, name), own.getNodeType()), null);
                    }
                } else if (typed) {
                    list.insertLast(placeholder(file, param.type + " " + param.name,
                        ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                } else {
                    list.insertLast(placeholder(file, param.name, ASTNode.VARIABLE_DECLARATION_FRAGMENT), null);
                }
            }
            int end;
            if (lambda.hasParentheses()) {
                int from = old.isEmpty() ? lambda.getStartPosition() + 1 : end(old.get(old.size() - 1));
                end = past(file.source, from, ')');
            } else {
                end = end(old.get(old.size() - 1));
            }
            add(file, new Place(lambda.getStartPosition(), end, false, null));
            for (Map.Entry<VariableDeclaration, String> parameter : renamed.entrySet()) {
                List<SimpleName> references = renameReferences(file, parameter.getKey(), lambda.getBody(), null,
                    parameter.getValue());
                file.renamedParameters.add(new RenamedParameter(lambda.getStartPosition(), true,
                    paramMapping[old.indexOf(parameter.getKey())], parameter.getValue(), references));
            }
            removedParametersUsed(file, lambda, old, lambda.getBody());
        }

        /** The lambda expression starting at {@code offset}, where JDT's search places its declaration. */
        private LambdaExpression lambdaAt(FileChange file, int offset) {
            LambdaExpression[] found = {null};
            file.ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(LambdaExpression node) {
                    if (found[0] == null && node.getStartPosition() == offset) {
                        found[0] = node;
                    }
                    return found[0] == null;
                }
            });
            if (found[0] == null) {
                throw new IllegalStateException("No lambda expression at " + file.position(offset));
            }
            return found[0];
        }

        /**
         * The implicit super() calls of {@code declaringType}'s constructor without parameters,
         * given some: each direct subclass with source and a name (an anonymous one is created by
         * a call, updated as one) gets, when it declares no constructor, a constructor calling
         * super(...) with the new arguments, with the class's access; otherwise each of its
         * constructors that calls no other constructor gets super(...) as its first statement
         * (JDT :1725-1790). A constructor body calling another constructor anywhere in it
         * (a flexible constructor body) calls super() explicitly or through it.
         */
        void implicitSuperCalls(IType declaringType) throws Exception {
            for (IType subclass : service.getSearchService().hierarchyOf(declaringType).subclasses()) {
                if (subclass.isAnonymous() || subclass.getCompilationUnit() == null) {
                    continue;
                }
                FileChange file = file(subclass.getCompilationUnit());
                ISourceRange nameRange = subclass.getNameRange();
                ASTNode named = NodeFinder.perform(file.ast, nameRange.getOffset(), nameRange.getLength());
                if (!(named.getParent() instanceof TypeDeclaration type)) {
                    throw new IllegalStateException("No class declaration of " + subclass.getElementName()
                        + " at " + file.position(nameRange.getOffset()));
                }
                List<MethodDeclaration> constructors = new ArrayList<>();
                for (Object body : type.bodyDeclarations()) {
                    if (body instanceof MethodDeclaration declaration && declaration.isConstructor()) {
                        constructors.add(declaration);
                    }
                }
                if (constructors.isEmpty()) {
                    add(file, insertion(file, rewrite -> {
                        AST ast = rewrite.getAST();
                        MethodDeclaration added = ast.newMethodDeclaration();
                        added.setConstructor(true);
                        added.setName(ast.newSimpleName(type.getName().getIdentifier()));
                        @SuppressWarnings("unchecked")
                        List<Modifier> modifiers = added.modifiers();
                        modifiers.addAll(ast.newModifiers(accessOf(type)));
                        Block body = ast.newBlock();
                        @SuppressWarnings("unchecked")
                        List<Statement> statements = body.statements();
                        statements.add(superCall(rewrite));
                        added.setBody(body);
                        rewrite.getListRewrite(type, TypeDeclaration.BODY_DECLARATIONS_PROPERTY).insertFirst(added, null);
                    }));
                    continue;
                }
                for (MethodDeclaration declaration : constructors) {
                    Block body = declaration.getBody();
                    if (body == null || callsConstructor(body)) {
                        continue;
                    }
                    add(file, insertion(file, rewrite ->
                        rewrite.getListRewrite(body, Block.STATEMENTS_PROPERTY).insertFirst(superCall(rewrite), null)));
                }
            }
        }

        /** super(...) passing each new parameter's default value, or a TODO marker. */
        private SuperConstructorInvocation superCall(ASTRewrite rewrite) {
            SuperConstructorInvocation call = rewrite.getAST().newSuperConstructorInvocation();
            @SuppressWarnings("unchecked")
            List<Expression> arguments = call.arguments();
            for (ParameterInfo param : newParameters) {
                arguments.add((Expression) rewrite.createStringPlaceholder(argumentFor(param), ASTNode.METHOD_INVOCATION));
            }
            return call;
        }

        /** Whether a statement of {@code body} calls a constructor, super(...) or this(...). */
        private static boolean callsConstructor(Block body) {
            for (Object statement : body.statements()) {
                if (statement instanceof SuperConstructorInvocation || statement instanceof ConstructorInvocation) {
                    return true;
                }
            }
            return false;
        }

        /** The access modifier of {@code type}, for a constructor added to it (JDT :1792-1802). */
        private static int accessOf(TypeDeclaration type) {
            int modifiers = type.getModifiers();
            if (Modifier.isPublic(modifiers)) return Modifier.PUBLIC;
            if (Modifier.isProtected(modifiers)) return Modifier.PROTECTED;
            if (Modifier.isPrivate(modifiers)) return Modifier.PRIVATE;
            return Modifier.NONE;
        }

        /**
         * The insertion {@code modification} makes in {@code file}, rendered by a rewrite of its
         * own: exactly one insertion is expected.
         */
        private Place insertion(FileChange file, Consumer<ASTRewrite> modification) throws JavaModelException {
            ASTRewrite rewrite = ASTRewrite.create(file.ast.getAST());
            modification.accept(rewrite);
            List<Map<String, Object>> edits = TextEditConverter.toEditMaps(rewrite.rewriteAST(), file.source, file.ast);
            if (edits.size() != 1 || !"insert".equals(edits.get(0).get("type"))) {
                throw new IllegalStateException("An insertion in " + file.path + " rendered as " + edits);
            }
            Map<String, Object> edit = edits.get(0);
            edit.put("isDeclaration", false);
            int offset = ((Number) edit.get("offset")).intValue();
            return new Place(offset, offset, false, edit);
        }

        /**
         * The single static imports of the renamed method, file by file. One that the search
         * reports as naming one of the methods, or through which a renamed call by simple name
         * resolves, names the new name: renamed, or, when a member it names keeps the old name,
         * followed by an import of the new name (as JDT's StaticImportUpdate :2946-2961 adds one
         * and keeps the old one while it is used, ImportRemover.getImportsToRemove :182-200). The
         * compiler binds a static import naming overloads to the last of them, so the search
         * reports it for that one only.
         */
        void staticImports() throws JavaModelException {
            for (FileChange file : new ArrayList<>(files.values())) {
                for (Object declaration : file.ast.imports()) {
                    ImportDeclaration imported = (ImportDeclaration) declaration;
                    if (!imported.isStatic() || imported.isOnDemand()
                            || !(imported.getName() instanceof QualifiedName name)
                            || !name.getName().getIdentifier().equals(oldName)) {
                        continue;
                    }
                    ITypeBinding qualifier = name.getQualifier().resolveTypeBinding();
                    boolean needed = file.reportedImports.contains(imported)
                        || (qualifier != null && file.invocations.stream()
                            .anyMatch(invocation -> throughStaticImport(invocation, qualifier)));
                    if (!needed) {
                        continue;
                    }
                    String newImport = name.getQualifier().getFullyQualifiedName() + "." + newName;
                    if (qualifier != null && stillUsed(file, qualifier)) {
                        if (!imports(file, newImport)) {
                            add(file, insertion(file, rewrite -> {
                                ImportDeclaration added = rewrite.getAST().newImportDeclaration();
                                added.setStatic(true);
                                added.setName(rewrite.getAST().newName(newImport));
                                rewrite.getListRewrite(file.ast, CompilationUnit.IMPORTS_PROPERTY)
                                    .insertAfter(added, imported, null);
                            }));
                        }
                    } else {
                        file.rewrite.replace(name, placeholder(file, newImport, ASTNode.QUALIFIED_NAME), null);
                        add(file, new Place(name.getStartPosition(), end(name), false, null));
                    }
                }
            }
        }

        /**
         * Whether {@code invocation}, a call of a static method by its simple name, resolves
         * through a static import of {@code qualifier}: no enclosing class has the method.
         */
        private boolean throughStaticImport(MethodInvocation invocation, ITypeBinding qualifier) {
            if (invocation.getExpression() != null) {
                return false;
            }
            IMethodBinding binding = invocation.resolveMethodBinding();
            if (binding == null || !Modifier.isStatic(binding.getModifiers())
                    || !qualifier.getErasure().isSubTypeCompatible(binding.getDeclaringClass().getErasure())) {
                return false;
            }
            ITypeBinding declaring = binding.getDeclaringClass().getErasure();
            for (ASTNode current = invocation.getParent(); current != null; current = current.getParent()) {
                ITypeBinding type = current instanceof AbstractTypeDeclaration typeDeclaration ? typeDeclaration.resolveBinding()
                    : current instanceof AnonymousClassDeclaration anonymous ? anonymous.resolveBinding() : null;
                if (type != null && type.getErasure().isSubTypeCompatible(declaring)) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Whether a name in {@code file}'s code, outside its imports and other than one this
         * change renames, denotes a static member of {@code qualifier} (or of a supertype of it)
         * that keeps the old name: a method none of the changed methods, a field or a member type.
         */
        private boolean stillUsed(FileChange file, ITypeBinding qualifier) {
            boolean[] used = {false};
            file.ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(ImportDeclaration node) {
                    return false;
                }

                @Override
                public boolean visit(SimpleName node) {
                    if (!used[0] && node.getIdentifier().equals(oldName) && !file.renamedNames.contains(node)
                            && keepsOldName(node.resolveBinding(), qualifier)) {
                        used[0] = true;
                    }
                    return false;
                }
            });
            return used[0];
        }

        private boolean keepsOldName(IBinding binding, ITypeBinding qualifier) {
            ITypeBinding declaring = switch (binding) {
                case IMethodBinding method when Modifier.isStatic(method.getModifiers())
                    && !ripple.contains(method.getMethodDeclaration().getJavaElement()) -> method.getDeclaringClass();
                case IVariableBinding variable when variable.isField() && Modifier.isStatic(variable.getModifiers()) ->
                    variable.getDeclaringClass();
                case ITypeBinding type when type.isMember() && Modifier.isStatic(type.getModifiers()) ->
                    type.getDeclaringClass();
                case null, default -> null;
            };
            return declaring != null && qualifier.getErasure().isSubTypeCompatible(declaring.getErasure());
        }

        /** Whether {@code file} has the single static import {@code name}. */
        private static boolean imports(FileChange file, String name) {
            for (Object declaration : file.ast.imports()) {
                ImportDeclaration imported = (ImportDeclaration) declaration;
                if (imported.isStatic() && !imported.isOnDemand()
                        && imported.getName().getFullyQualifiedName().equals(name)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * The edits, file by file. Each span takes the edits of its file's rewrite within it; a
         * place within another changes in that place's edit, and every edit of the rewrite must
         * belong to a span. Each edited file's text with its edits applied is kept, mapped piece by
         * piece to its source: the insertions and spans in source order, an insertion at the start
         * of a span before it.
         */
        Map<String, List<Map<String, Object>>> edits() throws JavaModelException {
            record Piece(Place place, Rendered text) {
            }
            Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
            for (FileChange file : edited) {
                TextEditConverter.Slices slices = new TextEditConverter.Slices(file.rewrite.rewriteAST());
                List<Map<String, Object>> edits = new ArrayList<>();
                List<Piece> pieces = new ArrayList<>();
                for (int i = 0; i < file.places.size(); i++) {
                    Place place = file.places.get(i);
                    Rendered text = new Rendered(file.source);
                    if (!place.isSpan()) {
                        for (Place span : file.places) {
                            if (span.isSpan() && span.start() < place.start() && place.start() < span.end()) {
                                throw new IllegalStateException("An insertion at " + file.position(place.start())
                                    + " falls within the place at " + file.position(span.start()));
                            }
                        }
                        edits.add(place.insert());
                        text.replace(place.start(), place.start(), (String) place.insert().get("newText"));
                        pieces.add(new Piece(place, text));
                        continue;
                    }
                    if (withinAnother(file.places, i)) {
                        continue;
                    }
                    render(file, slices, place.start(), place.end(), text);
                    edits.add(spanEdit(file, place, text.text.toString()));
                    pieces.add(new Piece(place, text));
                }
                slices.requireAllTaken();
                pieces.sort(Comparator.comparingInt((Piece piece) -> piece.place().start())
                    .thenComparing(piece -> piece.place().isSpan()));
                Rendered after = new Rendered(file.source);
                int cursor = 0;
                for (Piece piece : pieces) {
                    after.keep(cursor, piece.place().start());
                    after.append(piece.text());
                    cursor = piece.place().end();
                }
                after.keep(cursor, file.source.length());
                file.after = after;
                byFile.put(file.path, edits);
            }
            return byFile;
        }

        /**
         * What the applied edits do beyond what the change asks for. Every edited file holds its
         * changed text in a working copy, so each file the change edits or reports a place in
         * (calls, method references, potential matches) is compiled against the others as changed.
         * Each compile error a file then has that it has not now
         * (RefactoringAnalyzeUtil.getIntroducedCompileProblems, as
         * ChangeSignatureProcessor.checkCompilationofDeclaringCu :1285-1297 finds them in the
         * declaring unit) is a problem, at its place in the file as it is now, naming the member it
         * lies in; JDT's notes on removed parameters bodies still use are problems too. Each renamed
         * parameter is checked as JDT checks the rename of a local variable
         * (RenameAnalyzeUtil.analyzeLocalRenames :443-444 with ProblemNodeFinder :81-145): its
         * collisions refuse the change.
         */
        Analysis analyze(List<SearchMatch> potential) throws JavaModelException {
            for (SearchMatch match : potential) {
                ICompilationUnit unit = MatchResolver.resolveCu(match);
                if (unit != null) {
                    file(unit);
                }
            }
            RefactoringStatus collisions = new RefactoringStatus();
            List<Problem> problems = new ArrayList<>(notes);
            WorkingCopyOwner owner = new WorkingCopyOwner() { };
            Map<FileChange, ICompilationUnit> copies = new LinkedHashMap<>();
            Throwable primary = null;
            try {
                for (FileChange file : edited) {
                    ICompilationUnit copy = ((ICompilationUnit) file.ast.getJavaElement()).getWorkingCopy(owner, null);
                    copies.put(file, copy);
                    copy.getBuffer().setContents(file.after().text.toString());
                }
                for (FileChange file : files.values()) {
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setWorkingCopyOwner(owner);
                    parser.setSource(copies.getOrDefault(file, (ICompilationUnit) file.ast.getJavaElement()));
                    parser.setResolveBindings(true);
                    parser.setBindingsRecovery(true);
                    CompilationUnit after = (CompilationUnit) parser.createAST(Deadline.monitor());
                    for (IProblem problem : RefactoringAnalyzeUtil.getIntroducedCompileProblems(after, file.ast)) {
                        if (problem.isError()) {
                            int at = file.after().sourceOffset(problem.getSourceStart());
                            problems.add(new Problem(file, at, problem.getMessage() + in(label(file, at))));
                        }
                    }
                    for (RenamedParameter renamed : file.renamedParameters) {
                        collisions.merge(collisions(file, after, renamed));
                    }
                }
            } catch (Throwable failure) {
                primary = failure;
                throw failure;
            } finally {
                discardAll(copies.values(), primary);
            }
            problems.sort(Comparator.comparing((Problem problem) -> problem.file().path)
                .thenComparingInt(problem -> problem.file().ast.getLineNumber(problem.offset()))
                .thenComparingInt(problem -> problem.file().ast.getColumnNumber(problem.offset()))
                .thenComparing(Problem::message));
            return new Analysis(collisions, problems.stream().map(Problem::toMap).toList());
        }

        /**
         * The collisions of {@code renamed} in {@code after}, the changed text of {@code file}: each
         * name in its declaration or lambda expression (outside Javadoc, which ProblemNodeFinder's
         * visitor does not enter) that refers to a variable, where referring to the renamed
         * parameter and being a name the change renames differ: a renamed reference another variable
         * captures, or a name the parameter captures. Each is JDT's name collision error
         * (RefactoringAnalyzeUtil.reportProblemNodes), naming where it lies in the file as it is now.
         */
        private RefactoringStatus collisions(FileChange file, CompilationUnit after, RenamedParameter renamed)
                throws JavaModelException {
            ASTNode declaration = null;
            for (int at : file.after().offsetsOf(renamed.placeStart())) {
                declaration = renamed.lambda() ? lambdaStartingAt(after, at) : declarationStartingAt(after, at);
                if (declaration != null) {
                    break;
                }
            }
            if (declaration == null) {
                throw new IllegalStateException("No changed declaration of the parameter " + renamed.newName()
                    + " for the one at " + file.position(renamed.placeStart()));
            }
            List<?> parameters = declaration instanceof LambdaExpression lambda ? lambda.parameters()
                : ((MethodDeclaration) declaration).parameters();
            VariableDeclaration parameter = (VariableDeclaration) parameters.get(renamed.newIndex());
            IVariableBinding binding = parameter.resolveBinding();
            if (binding == null) {
                throw new IllegalStateException("The compiler resolves no binding for the renamed parameter "
                    + renamed.newName() + " of the declaration at " + file.position(renamed.placeStart()));
            }
            String key = binding.getKey();
            Set<Integer> renamedAt = new HashSet<>();
            renamedAt.add(parameter.getName().getStartPosition());
            for (SimpleName reference : renamed.references()) {
                Integer at = renamedOffset(file, reference, renamed.newName());
                if (at != null) {
                    renamedAt.add(at);
                }
            }
            List<SimpleName> found = new ArrayList<>();
            declaration.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IVariableBinding variable = variableOf(node);
                    boolean renamedHere = node.getIdentifier().equals(renamed.newName())
                        && renamedAt.contains(node.getStartPosition());
                    if (variable != null && key.equals(variable.getKey()) != renamedHere) {
                        found.add(node);
                    }
                    return true;
                }
            });
            RefactoringStatus result = new RefactoringStatus();
            for (SimpleName node : found) {
                int at = file.after().sourceOffset(node.getStartPosition());
                result.addError(Messages.format(RefactoringCoreMessages.RefactoringAnalyzeUtil_name_collision,
                        BasicElementLabels.getJavaElementName(node.getIdentifier())) + in(label(file, at))
                        + " at " + file.position(at),
                    JavaStatusContext.create((ICompilationUnit) file.ast.getJavaElement(),
                        new SourceRange(at, node.getLength())));
            }
            return result;
        }

        /**
         * Where {@code reference}, renamed to {@code name}, lies in the changed text of
         * {@code file}; null when the change leaves it out with the argument holding it.
         */
        private static Integer renamedOffset(FileChange file, SimpleName reference, String name) {
            TreeSet<Integer> offsets = file.after().offsetsOf(reference.getStartPosition());
            if (offsets.isEmpty()) {
                return null;
            }
            String text = file.after().text.toString();
            for (int at : offsets) {
                int end = at + name.length();
                if (text.startsWith(name, at) && (end == text.length() || !Character.isJavaIdentifierPart(text.charAt(end)))) {
                    return at;
                }
            }
            throw new IllegalStateException("The renamed reference at " + file.position(reference.getStartPosition())
                + " is not in the changed text");
        }

        /**
         * The variable {@code node} refers to or declares, as ProblemNodeFinder reads a name
         * (RenameAnalyzeUtil.getVariableDeclaration :450-460, by the binding itself); null for a name of anything else.
         */
        private static IVariableBinding variableOf(SimpleName node) {
            IBinding binding = node.resolveBinding();
            if (binding == null && node.getParent() instanceof VariableDeclaration declaration) {
                return declaration.resolveBinding();
            }
            return binding instanceof IVariableBinding variable ? variable : null;
        }

        /** The method or constructor declaration of {@code ast} whose return type, or name, starts at {@code offset}. */
        private static MethodDeclaration declarationStartingAt(CompilationUnit ast, int offset) {
            MethodDeclaration[] found = {null};
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    int start = node.getReturnType2() != null ? node.getReturnType2().getStartPosition()
                        : node.getName().getStartPosition();
                    if (found[0] == null && start == offset) {
                        found[0] = node;
                    }
                    return found[0] == null;
                }
            });
            return found[0];
        }

        /** The lambda expression of {@code ast} starting at {@code offset}, or null. */
        private static LambdaExpression lambdaStartingAt(CompilationUnit ast, int offset) {
            LambdaExpression[] found = {null};
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(LambdaExpression node) {
                    if (found[0] == null && node.getStartPosition() == offset) {
                        found[0] = node;
                    }
                    return found[0] == null;
                }
            });
            return found[0];
        }

        /** ", in " and {@code label}, or nothing without one. */
        private static String in(String label) {
            return label == null ? "" : ", in " + label;
        }

        /**
         * The member {@code offset} of {@code file} as it is now lies in: a method, field, enum
         * constant, annotation element or initializer as {@code Type.name}, the type named as
         * ElementNames names a type (an anonymous class by its number, {@code Outer$1}), or a type
         * alone; "a lambda expression in" it when the offset lies in a lambda expression. Null
         * outside every type.
         */
        private static String label(FileChange file, int offset) throws JavaModelException {
            boolean inLambda = false;
            for (ASTNode current = NodeFinder.perform(file.ast, offset, 0); current != null; current = current.getParent()) {
                if (current instanceof LambdaExpression) {
                    inLambda = true;
                    continue;
                }
                String member = memberLabel(current);
                if (member != null) {
                    return inLambda ? "a lambda expression in " + member : member;
                }
            }
            return null;
        }

        private static String memberLabel(ASTNode node) throws JavaModelException {
            if (node instanceof MethodDeclaration method) {
                return typeLabel(method.getParent()) + "." + method.getName().getIdentifier();
            }
            if (node instanceof VariableDeclarationFragment fragment && fragment.getParent() instanceof FieldDeclaration field) {
                return typeLabel(field.getParent()) + "." + fragment.getName().getIdentifier();
            }
            if (node instanceof EnumConstantDeclaration constant) {
                return typeLabel(constant.getParent()) + "." + constant.getName().getIdentifier();
            }
            if (node instanceof AnnotationTypeMemberDeclaration element) {
                return typeLabel(element.getParent()) + "." + element.getName().getIdentifier();
            }
            if (node instanceof Initializer initializer) {
                return typeLabel(initializer.getParent()) + "." + ElementNames.INITIALIZER;
            }
            if (node instanceof AbstractTypeDeclaration || node instanceof AnonymousClassDeclaration) {
                return typeLabel(node);
            }
            return null;
        }

        /** The name of the type {@code node} declares, as ElementNames names a type. */
        private static String typeLabel(ASTNode node) throws JavaModelException {
            ITypeBinding binding = node instanceof AbstractTypeDeclaration type ? type.resolveBinding()
                : node instanceof AnonymousClassDeclaration anonymous ? anonymous.resolveBinding() : null;
            if (binding != null && binding.getJavaElement() instanceof IType type) {
                return ElementNames.typeName(type);
            }
            if (node instanceof AbstractTypeDeclaration type) {
                return type.getName().getIdentifier();
            }
            throw new IllegalStateException("The compiler resolves no type for the anonymous class at offset "
                + node.getStartPosition());
        }

        /**
         * The type JDT's note on a removed parameter names for {@code declared}, a declaration or
         * lambda expression (ChangeSignatureProcessor.AbstractDeclarationUpdate.getFullTypeName
         * :2359-2377): its type's name, a lambda expression's as implemented in it; an anonymous
         * class's as a subclass of the type its creation names, or of its enum.
         */
        private static String fullTypeName(ASTNode declared) {
            for (ASTNode node = declared.getParent(); node != null; node = node.getParent()) {
                if (node instanceof AbstractTypeDeclaration type) {
                    String typeName = type.getName().getIdentifier();
                    return declared instanceof LambdaExpression
                        ? Messages.format(RefactoringCoreMessages.ChangeSignatureRefactoring_lambda_expression, typeName)
                        : typeName;
                }
                if (node instanceof ClassInstanceCreation creation) {
                    return Messages.format(RefactoringCoreMessages.ChangeSignatureRefactoring_anonymous_subclass,
                        BasicElementLabels.getJavaElementName(ASTNodes.asString(creation.getType())));
                }
                if (node instanceof EnumConstantDeclaration) {
                    return Messages.format(RefactoringCoreMessages.ChangeSignatureRefactoring_anonymous_subclass,
                        BasicElementLabels.getJavaElementName(ASTNodes.asString(((EnumDeclaration) node.getParent()).getName())));
                }
            }
            throw new IllegalStateException("No type encloses the declaration at offset " + declared.getStartPosition());
        }

        /**
         * Discards each of {@code copies}, each in its own try. A failure is added as suppressed to
         * {@code primary}, the failure the work with them ended in; without one, the first failure
         * is thrown once every copy is discarded, the others suppressed in it.
         */
        private static void discardAll(Collection<ICompilationUnit> copies, Throwable primary) throws JavaModelException {
            Exception failure = null;
            for (ICompilationUnit copy : copies) {
                try {
                    copy.discardWorkingCopy();
                } catch (JavaModelException | RuntimeException e) {
                    if (primary != null) {
                        primary.addSuppressed(e);
                    } else if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure instanceof JavaModelException modelFailure) {
                throw modelFailure;
            }
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
        }

        /**
         * Gives {@code out} the text {@code [start, end)} of {@code file} becomes: every edit within
         * it applied, and the arguments of each reordered call within it rendered in their new
         * order, each with the changes within it, the arguments left out taking theirs with them.
         * The separators and new arguments of a reordered call stand in place of the end of its
         * arguments.
         */
        private static void render(FileChange file, TextEditConverter.Slices slices, int start, int end, Rendered out) {
            int cursor = start;
            List<ReorderedCall> within = new ArrayList<>();
            for (ReorderedCall call : file.reorderedCalls) {
                if (start <= call.start() && call.end() <= end) {
                    within.add(call);
                }
            }
            within.sort(Comparator.comparingInt(ReorderedCall::start));
            for (ReorderedCall call : within) {
                if (call.start() < cursor) {
                    continue; // within a call rendered before, which renders it
                }
                slices.takeBefore(cursor, call.start(), out);
                for (int[] dropped : call.dropped()) {
                    slices.take(dropped[0], dropped[1], DISCARD);
                }
                boolean first = true;
                for (Object piece : call.pieces()) {
                    if (!first) {
                        out.replace(call.end(), call.end(), ", ");
                    }
                    first = false;
                    if (piece instanceof int[] range) {
                        render(file, slices, range[0], range[1], out);
                    } else {
                        out.replace(call.end(), call.end(), (String) piece);
                    }
                }
                cursor = call.end();
            }
            slices.take(cursor, end, out);
        }

        /** Whether the span at {@code index} lies within another span, or repeats an earlier one. */
        private static boolean withinAnother(List<Place> places, int index) {
            Place place = places.get(index);
            for (int i = 0; i < places.size(); i++) {
                Place other = places.get(i);
                if (i == index || !other.isSpan() || !other.holds(place)) {
                    continue;
                }
                if (!place.holds(other) || i < index) {
                    return true;
                }
            }
            return false;
        }

        private static Map<String, Object> spanEdit(FileChange file, Place place, String newText) {
            Map<String, Object> edit = new LinkedHashMap<>();
            edit.put("type", "replace");
            edit.put("startLine", file.ast.getLineNumber(place.start()) - 1);
            edit.put("startColumn", file.ast.getColumnNumber(place.start()));
            edit.put("endLine", file.ast.getLineNumber(place.end()) - 1);
            edit.put("endColumn", file.ast.getColumnNumber(place.end()));
            edit.put("startOffset", place.start());
            edit.put("endOffset", place.end());
            String oldText = file.source.substring(place.start(), place.end());
            if (place.declaration()) {
                edit.put("oldSignature", oldText);
                edit.put("newSignature", newText);
            } else {
                edit.put("oldText", oldText);
                edit.put("newText", newText);
            }
            edit.put("isDeclaration", place.declaration());
            return edit;
        }
    }

    private static SingleVariableDeclaration newParameter(AST factory, ASTRewrite rewrite, String type, String name) {
        SingleVariableDeclaration svd = factory.newSingleVariableDeclaration();
        svd.setType((Type) rewrite.createStringPlaceholder(type, ASTNode.SIMPLE_TYPE));
        svd.setName(factory.newSimpleName(name));
        return svd;
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }

    private static class ParameterInfo {
        final String name;
        final String type;
        final String defaultValue;
        /** The name the parameter has now, when it is renamed; else null. */
        final String oldName;

        ParameterInfo(String name, String type, String defaultValue, String oldName) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
            this.oldName = oldName;
        }

        /** The name of the old parameter this one is: its oldName, else its name. */
        String identity() {
            return oldName != null ? oldName : name;
        }
    }
}
