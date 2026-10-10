package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ConstructorInvocation;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
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
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.MethodReferenceMatch;
import org.eclipse.jdt.core.search.SearchMatch;
import org.javalens.core.Deadline;
import org.javalens.core.IJdtService;
import org.javalens.core.search.SearchResult;
import org.javalens.core.search.SearchService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.rewrite.TextEditConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
            - Rename parameter
            - Reorder parameters (specify all parameters in new order)

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
                "description", "New parameter list. Each item: {name, type, defaultValue?}. Order matters.",
                "items", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "name", Map.of("type", "string", "description", "Parameter name"),
                        "type", Map.of("type", "string", "description", "Parameter type"),
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

                if (pName == null || pType == null) {
                    return ToolResponse.invalidParameter("newParameters",
                        "Each parameter must have 'name' and 'type'");
                }
                newParameters.add(new ParameterInfo(pName, pType, pDefault));
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
            if (newParameters == null) {
                newParameters = new ArrayList<>();
                for (int i = 0; i < oldParamTypes.length; i++) {
                    newParameters.add(new ParameterInfo(
                        oldParamNames[i],
                        Signature.toString(oldParamTypes[i]),
                        null
                    ));
                }
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

            Change change = new Change(service, method, ripple, oldName, newName, oldReturnType, newReturnType,
                oldParamTypes, newParameters, paramMapping);

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
            // with theirs (JDT's search reports each as a declaration of the method)
            if (!method.isConstructor() && change.parametersChanged()) {
                for (IMethod declared : ripple) {
                    SearchResult found = service.getSearchService().findDeclarationsInSources(
                        declared, SearchService.ALL_MATCHES);
                    for (SearchMatch match : found.matches()) {
                        if (isLambdaMethod(match)) {
                            change.lambda(match);
                        }
                    }
                    for (SearchMatch match : found.potentialMatches()) {
                        if (isLambdaMethod(match) && potentialPlaces.add(placeOf(match))) {
                            potential.add(match);
                        }
                    }
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

            // Method references cannot be automatically rewritten because the
            // functional-interface signature they bind to would no longer match.
            // Surface them as informational entries plus top-level warnings so the
            // AI consumer knows manual rewrite is required.
            if (!methodReferences.isEmpty()) {
                data.put("methodReferences", methodReferences);
                Set<String> affectedFiles = new LinkedHashSet<>();
                for (Map<String, Object> ref : methodReferences) {
                    Object filePathObj = ref.get("filePath");
                    if (filePathObj != null) affectedFiles.add(filePathObj.toString());
                }
                List<String> warnings = new ArrayList<>();
                for (String f : affectedFiles) {
                    warnings.add(f + " has method-reference call sites that cannot be " +
                        "automatically updated. Replace each with a lambda or refactor " +
                        "the functional-interface signature.");
                }
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

    private int[] buildParameterMapping(String[] oldNames, List<ParameterInfo> newParams) {
        int[] mapping = new int[oldNames.length];

        for (int oldIdx = 0; oldIdx < oldNames.length; oldIdx++) {
            mapping[oldIdx] = -1; // Default to removed

            // Find this param in new list
            for (int newIdx = 0; newIdx < newParams.size(); newIdx++) {
                if (oldNames[oldIdx].equals(newParams.get(newIdx).name)) {
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

    private MethodDeclaration findMethodDeclaration(CompilationUnit ast, IMethod method) {
        final MethodDeclaration[] result = {null};
        final String methodName = method.getElementName();

        try {
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
        } catch (JavaModelException e) {
            log.debug("Error finding method: {}", e.getMessage());
        }

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
        /** The arguments whose text a reordering call re-renders as written. */
        final List<ASTNode> rerendered = new ArrayList<>();

        FileChange(String path, String source, CompilationUnit ast) {
            this.path = path;
            this.source = source;
            this.ast = ast;
            this.rewrite = ASTRewrite.create(ast.getAST());
        }

        String position(int offset) {
            return path + ":" + (ast.getLineNumber(offset) - 1) + ":" + ast.getColumnNumber(offset);
        }
    }

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
        private final List<ParameterInfo> newParameters;
        private final int[] paramMapping;
        /** Whether a kept parameter moves before one it followed, so calls re-render their arguments. */
        private final boolean reordered;
        private final Map<String, FileChange> files = new LinkedHashMap<>();
        private final List<FileChange> edited = new ArrayList<>();
        final List<Map<String, Object>> methodReferences = new ArrayList<>();

        Change(IJdtService service, IMethod method, List<IMethod> ripple, String oldName, String newName,
               String oldReturnType, String newReturnType, String[] oldParamTypes,
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
                if (oldIndexOf(paramMapping, i) != i
                        || !newParameters.get(i).type.equals(Signature.toString(oldParamTypes[i]))) {
                    return true;
                }
            }
            return false;
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
         * construction. The method at position takes the requested parameters as given. A method
         * changed with it keeps each parameter kept from the method at position as it declares
         * it, with its own name and its own type unless that type is changed (an overrider
         * declares {@code String} where the method it overrides declares {@code T}); a new
         * parameter is the requested one.
         */
        void declaration(IMethod declared, boolean atPosition) throws JavaModelException {
            FileChange file = file(declared.getCompilationUnit());
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
            for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                ParameterInfo param = newParameters.get(newIdx);
                int oldIdx = oldIndexOf(paramMapping, newIdx);
                if (!atPosition && oldIdx >= 0 && oldIdx < declaredParams.size()) {
                    SingleVariableDeclaration own = declaredParams.get(oldIdx);
                    if (param.type.equals(Signature.toString(oldParamTypes[oldIdx]))) {
                        paramsRewrite.insertLast(placeholder(file, file.source.substring(own.getStartPosition(),
                            end(own)), ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                        continue;
                    }
                    paramsRewrite.insertLast(newParameter(factory, rewrite, param.type,
                        own.getName().getIdentifier()), null);
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
        }

        /**
         * The place a reference match selects. An implicit super() call the search reports
         * (synthetic) is updated by {@link #implicitSuperCalls}; a call, an enum constant and a
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
         * holding another changed place changes with it; a reordering re-renders every argument
         * as written. The edit spans the whole call (an enum constant from its name to its
         * arguments' closing parenthesis).
         */
        private void call(FileChange file, ASTNode node) {
            @SuppressWarnings("unchecked")
            List<Expression> oldArgs = (List<Expression>) callArguments(node);
            ListRewrite argsRewrite = file.rewrite.getListRewrite(node, argumentsProperty(node));
            int lastParam = oldParamTypes.length - 1;
            if (reordered) {
                for (Expression oldArg : oldArgs) {
                    argsRewrite.remove(oldArg, null);
                }
                for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                    int oldIdx = oldIndexOf(paramMapping, newIdx);
                    String text;
                    if (oldIdx >= 0 && oldIdx < oldArgs.size()) {
                        // A varargs parameter passes every argument from its position on
                        Expression last = varargs && oldIdx == lastParam ? oldArgs.get(oldArgs.size() - 1) : oldArgs.get(oldIdx);
                        text = file.source.substring(oldArgs.get(oldIdx).getStartPosition(), end(last));
                    } else if (oldIdx >= 0) {
                        continue;
                    } else {
                        text = argumentFor(newParameters.get(newIdx));
                    }
                    argsRewrite.insertLast(placeholder(file, text, ASTNode.SIMPLE_NAME), null);
                }
                file.rerendered.addAll(oldArgs);
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
         * A Javadoc reference: a method reference written with parentheses takes the new
         * parameter list (types as Javadoc writes them, raw; a parameter name only where the
         * reference names its first parameter, as JDT's DocReferenceUpdate :2846-2944 writes
         * it), and either form takes the new name.
         */
        private void javadoc(FileChange file, ASTNode node) {
            SimpleName name;
            if (node instanceof MethodRef ref) {
                name = ref.getName();
                if (parametersChanged()) {
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
                            text = param.type.equals(Signature.toString(oldParamTypes[oldIdx]))
                                ? file.source.substring(own.getStartPosition(), end(own))
                                : erasure(param.type) + (own.getName() != null ? " " + own.getName().getIdentifier() : "");
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
            for (int newIdx = 0; newIdx < newParameters.size(); newIdx++) {
                ParameterInfo param = newParameters.get(newIdx);
                int oldIdx = oldIndexOf(paramMapping, newIdx);
                if (oldIdx >= 0 && oldIdx < old.size()) {
                    VariableDeclaration own = old.get(oldIdx);
                    if (own instanceof SingleVariableDeclaration
                            && !param.type.equals(Signature.toString(oldParamTypes[oldIdx]))) {
                        list.insertLast(placeholder(file, param.type + " " + own.getName().getIdentifier(),
                            ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                    } else {
                        list.insertLast(placeholder(file, file.source.substring(own.getStartPosition(), end(own)),
                            own.getNodeType()), null);
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
         * belong to a span. A place within an argument a reordering re-renders as written cannot
         * change there, and fails the change.
         */
        Map<String, List<Map<String, Object>>> edits() throws JavaModelException {
            Map<String, List<Map<String, Object>>> byFile = new LinkedHashMap<>();
            for (FileChange file : edited) {
                TextEditConverter.Slices slices = new TextEditConverter.Slices(file.rewrite.rewriteAST(), file.source);
                List<Map<String, Object>> edits = new ArrayList<>();
                for (int i = 0; i < file.places.size(); i++) {
                    Place place = file.places.get(i);
                    for (ASTNode argument : file.rerendered) {
                        if (argument.getStartPosition() <= place.start() && place.end() <= end(argument)) {
                            throw new IllegalStateException("Cannot reorder the arguments holding the place at "
                                + file.position(place.start()) + ": that place would not change");
                        }
                    }
                    if (!place.isSpan()) {
                        for (Place span : file.places) {
                            if (span.isSpan() && span.start() < place.start() && place.start() < span.end()) {
                                throw new IllegalStateException("An insertion at " + file.position(place.start())
                                    + " falls within the place at " + file.position(span.start()));
                            }
                        }
                        edits.add(place.insert());
                        continue;
                    }
                    if (withinAnother(file.places, i)) {
                        continue;
                    }
                    edits.add(spanEdit(file, place, slices.take(place.start(), place.end())));
                }
                slices.requireAllTaken();
                byFile.put(file.path, edits);
            }
            return byFile;
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

        ParameterInfo(String name, String type, String defaultValue) {
            this.name = name;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }
}
