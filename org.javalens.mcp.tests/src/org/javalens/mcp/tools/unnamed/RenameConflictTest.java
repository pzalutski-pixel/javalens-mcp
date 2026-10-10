package org.javalens.mcp.tools.unnamed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.EnvelopeHarness;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.function.Executable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.javalens.mcp.tools.unnamed.UnnamedElements.assertPayload;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.invalidParameter;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.map;
import static org.javalens.mcp.tools.unnamed.UnnamedElements.success;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rename_symbol refuses a rename that JDT's rename refactoring finds a conflict for, where JDT
 * marks the conflict FATAL or ERROR: INVALID_PARAMETER for newName carrying JDT's messages, in
 * the order of the places they name, a file named as every tool names it. JDT's warnings are
 * listed in {@code warnings}, in the same order, on a rename that goes ahead.
 *
 * <p>The conflicts of a type, a field, a local variable, a type parameter and a method that is not
 * virtual are those of JDT's processors: for a type, RenameTypeProcessor.checkTypesInPackage,
 * checkTypesImportedInCu, checkEnclosingTypes, checkEnclosedTypes with analyseEnclosedTypes,
 * checkTypesInCompilationUnit, checkConflictingTypes and checkImportedTypes; for a field,
 * RenameFieldProcessor.checkNewElementName and the shadowing analysis of
 * RenameAnalyzeUtil.analyzeRenameChanges2; for a private method, RenameNonVirtualMethodProcessor's
 * hierarchy check; for a local variable, RenameLocalVariableProcessor's introduced compile errors
 * (RenameAnalyzeUtil.analyzeLocalRenames); for a type parameter,
 * RenameTypeParameterProcessor.checkNewElementName. Those of a virtual method are the conditions
 * of RenameMethodProcessor and RenameVirtualMethodProcessor, checked on the Java model's own
 * hierarchy: a related method that is native, a subclass method that is native, a method of the
 * hierarchy with the new name and the same parameter type names (with other parameter type names,
 * a warning), a related type declaring a method with the new name, the special case of JLS 9.2, a
 * static import of the new name, a unit with syntax errors, a reference another method would take
 * over or a reference to another method the renamed method would take over; a discouraged name and
 * a constructor's name are warnings. A rename with no conflict is answered as before.
 *
 * <p>The fixture is rename-conflicts, one package per conflict kind (zero-based positions): in
 * com.example.types, Alpha (Alpha.java 2:13), Holder with the secondary type Spare (Holder.java),
 * Beta importing java.util.List (Beta.java 4:13), Gamma (Gamma.java 2:13) with the member type
 * Inner (4:17), Delta with the member types First (Delta.java 4:17) and Second; in
 * com.example.other, Epsilon; in com.example.shadowing, Zeta (Zeta.java 2:13) and UsesBoth, which
 * imports com.example.other.* and declares a Zeta and an Epsilon field; in com.example.importing,
 * Theta importing com.example.other.* (Theta.java 4:13); in com.example.fields, Fields with the
 * fields count (Fields.java 3:8) and total, and Shadow with the field size (Shadow.java 3:8) that
 * twice() reads beside its local limit; in com.example.methods, Methods with run() (Methods.java
 * 3:9) and go(), Parent with start() (Parent.java 3:9) and its subclass Child with begin(),
 * StaticUser importing java.lang.Math.max statically with pick(int, int) (StaticUser.java 5:8),
 * the interface Shape with draw() (Shape.java 3:9) implemented by Circle (draw() at Circle.java
 * 3:16), which also declares paint(), Privates with the private alpha() (Privates.java 3:17) and
 * beta(); in com.example.locals, Locals.sum() with the locals a (Locals.java 4:12) and b, and Pair
 * with the type parameters K (Pair.java 2:18) and V; in com.example.virtuals, Widen with
 * put(int) (Widen.java 3:9), called in use() (10:8), and set(String), NativeBase with work()
 * (NativeBase.java 3:9) and its subclass NativeChild with the native work() (NativeChild.java
 * 3:16), the interface Described with int describe() (Described.java 3:8); in
 * com.example.shadowmethods, Outer with m() and its member class Inner, a subclass of Base, whose
 * f() calls m(), Base with k() (Base.java 3:9), Host with k() (Host.java 3:9) and its member class
 * Nested, a subclass of Other, whose f() calls k(), Other with m(); in com.example.broken, Fine
 * with call() (Fine.java 3:9) and Broken, with a syntax error in oops(), with fix() (Broken.java
 * 3:9) and uses(), which calls Fine.call(); in com.example.lambdas, Hosts with the interface Mark
 * declaring tag() (Hosts.java 4:15), the class Base declaring a() (8:13) and marks(), whose lambda
 * body creates an anonymous Mark declaring tag() (15:30) and label(), calls its tag() (23:17)
 * and declares the local class Sub (24:18), a subclass of Base declaring the field count (25:20),
 * b() and the private twice() (30:28), which reads count (31:27), then assigns sub.twice() to
 * sub.count (line 35, count at 16, twice at 28) of a Sub it creates (line 34, Sub at 12 and 26).
 *
 * <p>Further: in com.example.annotations, the annotation type Tag with the member text()
 * (Tag.java 3:11) and Noted with note() and a method annotated @Tag(text = "x") (text at Noted.java
 * 7:9); in com.example.shadowmethods, Callee with k() (Callee.java 3:9) and Caller with m() and its
 * member class Inner, a subclass of Callee, whose k() calls m(); in com.example.methods, Typed with
 * the private put(int) (Typed.java 3:17) and set(String), Upper with the private hidden() and its
 * subclass Lower with shown() (Lower.java 3:9); in com.example.broken, Fixer, a subclass of Broken,
 * overriding fix() (Fixer.java 3:9), Lone with solo() (Lone.java 3:9) and LoneUser, with a syntax
 * error in bad(), calling solo() on a parameter of the missing type Missing. Beta.java's package
 * declaration starts at 0:0, its last segment, types, at 0:20, and its import java.util.List at 2:0. The fixture
 * modular-maven declares the module com.example.modular (module-info.java, the keyword module at
 * 0:0, the name at 0:7).
 */
class RenameConflictTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String FIXTURE = "rename-conflicts";
    private static final String ROOT = "src/main/java/com/example/";
    private static final List<String> NEXT_TOOLS = List.of(
        "Apply the text edits to complete the rename",
        "get_diagnostics to verify no errors after rename");

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private EnvelopeHarness envelope;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject(FIXTURE);
        envelope = new EnvelopeHarness(service);
    }

    // ========== types ==========

    @Test
    @DisplayName("a type renamed to the name of a secondary type of its package is refused")
    void type_existsInPackage() {
        assertRefused("types/Alpha.java", 2, 13, "Spare",
            "Type named 'Spare' already exists in package 'com.example.types'");
    }

    @Test
    @DisplayName("a type renamed to the name its compilation unit imports is refused, naming the file")
    void type_importedInItsUnit() {
        assertRefused("types/Beta.java", 4, 13, "List",
            "Type named 'List' is imported (single-type-import) in '" + ROOT + "types/Beta.java'"
                + " (a compilation unit must not import and declare a type with the same name)");
    }

    @Test
    @DisplayName("a member type renamed to the name of its enclosing type is refused")
    void type_enclosedInTypeOfNewName() {
        assertRefused("types/Gamma.java", 4, 17, "Gamma",
            "Type 'com.example.types.Gamma.Inner' is enclosed in a type named 'Gamma'; "
                + "Type named 'Gamma' already exists in package 'com.example.types'");
    }

    @Test
    @DisplayName("a type renamed to the name of a type it encloses is refused")
    void type_enclosesTypeOfNewName() {
        assertRefused("types/Gamma.java", 2, 13, "Inner",
            "Type 'com.example.types.Gamma' encloses a type named 'Inner'; "
                + "Member Type declared inside 'com.example.types.Gamma' is named Inner");
    }

    @Test
    @DisplayName("a member type renamed to the name of a sibling member type is refused")
    void type_siblingMemberType() {
        assertRefused("types/Delta.java", 4, 17, "Second",
            "Another member type named 'Second' already exists in 'com.example.types.Delta'");
    }

    @Test
    @DisplayName("a type renamed to the name of another type a unit referencing it also references is refused")
    void type_conflictingTypeReferenced() {
        assertRefused("shadowing/Zeta.java", 2, 13, "Epsilon",
            "Another type named 'Epsilon' is referenced in 'UsesBoth.java'. Semantics may not be preserved if you proceed.");
    }

    @Test
    @DisplayName("a type renamed to the name of a public type its unit imports on demand is refused, naming the file")
    void type_onDemandImportedName() {
        assertRefused("importing/Theta.java", 4, 13, "Epsilon",
            "Name conflict with type 'com.example.other.Epsilon' in '" + ROOT + "importing/Theta.java'");
    }

    // ========== fields ==========

    @Test
    @DisplayName("a field renamed to the name of another field of its type is refused")
    void field_alreadyDefined() {
        assertRefused("fields/Fields.java", 3, 8, "total", "A field with this name is already defined.");
    }

    @Test
    @DisplayName("a field renamed to the name of a local that would shadow its reference is refused")
    void field_shadowedReference() {
        assertRefused("fields/Shadow.java", 3, 8, "limit",
            "Problem in 'Shadow.java'. Another name will shadow access to the renamed element");
    }

    @Test
    @DisplayName("a field renamed without a conflict is answered with its edit")
    void field_withoutConflict() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("fields/Fields.java", 3, 8, "amount"),
            success(map(
                "oldName", "count",
                "newName", "amount",
                "symbolKind", "field",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "fields/Fields.java", List.of(map(
                    "line", 3, "column", 8, "endColumn", 13, "oldText", "count", "newText", "amount")))),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("Fields.count renamed to amount, its edit applied, compiles")
    void field_withoutConflictCompiles() throws Exception {
        assertCompiles(applied("fields/Fields.java", 3, 8, "amount"), ROOT + "fields/Fields.java");
    }

    // ========== methods ==========

    @Test
    @DisplayName("a method renamed to the name and parameters of another method of its class is refused")
    void method_sameSignatureInClass() {
        assertRefused("methods/Methods.java", 3, 9, "go",
            "Hierarchy declares a method 'go' with the same number of parameters and the same parameter type names.");
    }

    @Test
    @DisplayName("a method renamed to the name and parameters of a method of a subclass is refused")
    void method_sameSignatureInSubclass() {
        assertRefused("methods/Parent.java", 3, 9, "begin",
            "Hierarchy declares a method 'begin' with the same number of parameters and the same parameter type names.");
    }

    @Test
    @DisplayName("a method renamed to the name of a method its unit imports statically is refused")
    void method_staticImportName() {
        assertRefused("methods/StaticUser.java", 5, 8, "max", "This name collides with a static import name.");
    }

    @Test
    @DisplayName("an interface method renamed to the name of a method of an implementing class is refused")
    void method_relatedTypeDeclaresName() {
        assertRefused("methods/Shape.java", 3, 9, "paint",
            "A related type declares a method with the new name (and same number of parameters)");
    }

    @Test
    @DisplayName("an implementation renamed to the name of a method of its class is checked from the interface method it implements, and refused")
    void method_implementationCheckedFromInterfaceMethod() {
        assertRefused("methods/Circle.java", 3, 16, "paint",
            "A related type declares a method with the new name (and same number of parameters)");
    }

    @Test
    @DisplayName("a private method renamed to the name and parameters of another method of its class is refused")
    void method_privateSameSignature() {
        assertRefused("methods/Privates.java", 3, 17, "beta",
            "'com.example.methods.Privates' or a type in its hierarchy defines a method 'beta' with the same"
                + " number of parameters and the same parameter type names.");
    }

    @Test
    @DisplayName("a method renamed to the name of its class goes ahead with the warnings of a discouraged name and of a constructor's name")
    void method_constructorNameWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("methods/Methods.java", 3, 9, "Methods"),
            success(map(
                "oldName", "run",
                "newName", "Methods",
                "symbolKind", "method",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "methods/Methods.java", List.of(map(
                    "line", 3, "column", 9, "endColumn", 12, "oldText", "run", "newText", "Methods"))),
                "warnings", List.of(
                    "This name is discouraged. According to convention, names of methods should start with a lowercase letter.",
                    "If you proceed, the method 'void run()' in 'com.example.methods.Methods' will have a constructor name.")),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("Methods.run renamed to Methods, its edits applied, compiles")
    void method_constructorNameCompiles() throws Exception {
        assertCompiles(applied("methods/Methods.java", 3, 9, "Methods"), ROOT + "methods/Methods.java");
    }

    // ========== virtual methods ==========

    @Test
    @DisplayName("a method renamed to the name of a method of its class with other parameter types goes ahead with a warning")
    void virtual_otherParameterTypesWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("virtuals/Widen.java", 3, 9, "set"),
            success(map(
                "oldName", "put",
                "newName", "set",
                "symbolKind", "method",
                "totalEdits", 2,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "virtuals/Widen.java", List.of(
                    map("line", 10, "column", 8, "endColumn", 11, "oldText", "put", "newText", "set"),
                    map("line", 3, "column", 9, "endColumn", 12, "oldText", "put", "newText", "set"))),
                "warnings", List.of(
                    "Hierarchy declares a method 'set' with the same number of parameters, but different parameter type names.")),
                map("totalCount", 2, "returnedCount", 2, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("Widen.put renamed to set, its edits applied, compiles")
    void virtual_otherParameterTypesCompiles() throws Exception {
        assertCompiles(applied("virtuals/Widen.java", 3, 9, "set"), ROOT + "virtuals/Widen.java");
    }

    @Test
    @DisplayName("a method a native method of a subclass overrides is refused, naming the native related method and the native subclass method")
    void virtual_nativeOverrider() {
        assertRefused("virtuals/NativeBase.java", 3, 9, "toil",
            "Related method 'work' (declared in 'com.example.virtuals.NativeChild') is native."
                + " Renaming will cause an UnsatisfiedLinkError on runtime.; "
                + "Renaming 'work' requires renaming a native method. Renaming will cause UnsatisfiedLinkError on runtime.");
    }

    @Test
    @DisplayName("a native method is refused as native, then checked from the method it overrides")
    void virtual_nativeMethod() {
        assertRefused("virtuals/NativeChild.java", 3, 16, "toil",
            "Renaming native methods will cause an unsatisfied link error on runtime.; "
                + "Related method 'work' (declared in 'com.example.virtuals.NativeChild') is native."
                + " Renaming will cause an UnsatisfiedLinkError on runtime.; "
                + "Renaming 'work' requires renaming a native method. Renaming will cause UnsatisfiedLinkError on runtime.");
    }

    @Test
    @DisplayName("an interface method renamed to toString with another return type is refused as the special case of JLS 9.2 and for java.lang.Object's toString")
    void virtual_specialCase() {
        assertRefused("virtuals/Described.java", 3, 8, "toString",
            "Cannot rename this method because it is a special case (see the language specification section 9.2 for details); "
                + "A related type declares a method with the new name (and same number of parameters)");
    }

    @Test
    @DisplayName("a method renamed to the name a nested subclass's call of an enclosing method would then reach is refused")
    void virtual_referenceTakenOver() {
        assertRefused("shadowmethods/Base.java", 3, 9, "m",
            "Problem in 'Outer.java'. The reference to 'm' will be shadowed by a renamed declaration");
    }

    @Test
    @DisplayName("a method renamed to the name of a method its nested subclass's call would then reach instead is refused")
    void virtual_referenceLost() {
        assertRefused("shadowmethods/Host.java", 3, 9, "m",
            "Problem in 'Host.java'. Another name will shadow access to the renamed element");
    }

    @Test
    @DisplayName("a method referenced in a unit with syntax errors is refused, naming the unit")
    void virtual_referenceInBrokenUnit() {
        assertRefused("broken/Fine.java", 3, 9, "ring",
            "'" + ROOT + "broken/Broken.java' has syntax errors. Content of that file will not be updated.");
    }

    @Test
    @DisplayName("a method of a unit with syntax errors is refused")
    void virtual_methodOfBrokenUnit() {
        assertRefused("broken/Broken.java", 3, 9, "mend",
            "This refactoring cannot be performed correctly due to syntax errors in the compilation unit."
                + " To perform this operation you will need to fix the errors.");
    }

    @Test
    @DisplayName("an interface method renamed to the name of a method of its anonymous implementor in a lambda body is refused")
    void virtual_anonymousImplementorInLambda() {
        assertRefused("lambdas/Hosts.java", 4, 15, "label",
            "A related type declares a method with the new name (and same number of parameters)");
    }

    @Test
    @DisplayName("a method renamed to the name of a method of a local subclass in a lambda body is refused")
    void virtual_localSubclassInLambda() {
        assertRefused("lambdas/Hosts.java", 8, 13, "b",
            "Hierarchy declares a method 'b' with the same number of parameters and the same parameter type names.");
    }

    @Test
    @DisplayName("an interface method renamed to the name of another method of the project goes ahead, its call in a lambda body still reaching it")
    void virtual_callInLambdaStillReachesRenamed() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("lambdas/Hosts.java", 4, 15, "a"),
            success(map(
                "oldName", "tag",
                "newName", "a",
                "symbolKind", "method",
                "totalEdits", 3,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "lambdas/Hosts.java", List.of(
                    map("line", 23, "column", 17, "endColumn", 20, "oldText", "tag", "newText", "a"),
                    map("line", 15, "column", 30, "endColumn", 33, "oldText", "tag", "newText", "a"),
                    map("line", 4, "column", 15, "endColumn", 18, "oldText", "tag", "newText", "a")))),
                map("totalCount", 3, "returnedCount", 3, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("Mark.tag renamed to a, its edits applied, compiles")
    void virtual_callInLambdaCompiles() throws Exception {
        assertCompiles(applied("lambdas/Hosts.java", 4, 15, "a"), ROOT + "lambdas/Hosts.java");
    }

    @Test
    @DisplayName("an annotation member renamed to the name of another method goes ahead, its use in an annotation still reaching it")
    void virtual_annotationMemberStillReached() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("annotations/Tag.java", 3, 11, "note"),
            success(map(
                "oldName", "text",
                "newName", "note",
                "symbolKind", "method",
                "totalEdits", 2,
                "filesAffected", 2,
                "editsByFile", map(
                    ROOT + "annotations/Tag.java", List.of(
                        map("line", 3, "column", 11, "endColumn", 15, "oldText", "text", "newText", "note")),
                    ROOT + "annotations/Noted.java", List.of(
                        map("line", 7, "column", 9, "endColumn", 13, "oldText", "text", "newText", "note")))),
                map("totalCount", 2, "returnedCount", 2, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("Tag.text renamed to note, its edits applied, compiles")
    void virtual_annotationMemberCompiles() throws Exception {
        assertCompiles(applied("annotations/Tag.java", 3, 11, "note"),
            ROOT + "annotations/Tag.java", ROOT + "annotations/Noted.java");
    }

    @Test
    @DisplayName("a method whose renamed overrider would call itself in place of an enclosing method is refused, naming no place")
    void virtual_referenceTakenOverInsideRenamedMethod() {
        assertRefused("shadowmethods/Callee.java", 3, 9, "m",
            "Problem in 'Caller.java'. The reference to 'm' will be shadowed by a renamed declaration");
    }

    @Test
    @DisplayName("a method renamed to the name of a superclass's private method goes ahead: the private method is not inherited")
    void virtual_superclassPrivateMethodNotReported() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("methods/Lower.java", 3, 9, "hidden"),
            success(map(
                "oldName", "shown",
                "newName", "hidden",
                "symbolKind", "method",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "methods/Lower.java", List.of(
                    map("line", 3, "column", 9, "endColumn", 14, "oldText", "shown", "newText", "hidden")))),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("a method overriding a method of a unit with syntax errors is refused, checked from the method it overrides")
    void virtual_overriddenMethodInBrokenUnit() {
        assertRefused("broken/Fixer.java", 3, 9, "mend",
            "This refactoring cannot be performed correctly due to syntax errors in the compilation unit."
                + " To perform this operation you will need to fix the errors.");
    }

    @Test
    @DisplayName("a method whose only potential match lies in a unit with syntax errors is refused, naming the unit")
    void virtual_potentialMatchInBrokenUnit() {
        assertRefused("broken/Lone.java", 3, 9, "alone",
            "'" + ROOT + "broken/LoneUser.java' has syntax errors. Content of that file will not be updated.");
    }

    @Test
    @DisplayName("a private method renamed to the name of a method of its class with other parameter types goes ahead with JDT's warning")
    void method_privateOtherParameterTypesWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("methods/Typed.java", 3, 17, "set"),
            success(map(
                "oldName", "put",
                "newName", "set",
                "symbolKind", "method",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "methods/Typed.java", List.of(
                    map("line", 3, "column", 17, "endColumn", 20, "oldText", "put", "newText", "set"))),
                "warnings", List.of("'com.example.methods.Typed' or a type in its hierarchy defines a method 'set'"
                    + " with the same number of parameters, but different parameter type names.")),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    // ========== warnings of the other kinds ==========

    @Test
    @DisplayName("a member type renamed to a lowercase name goes ahead with JDT's naming warning")
    void type_lowercaseNameWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("types/Delta.java", 4, 17, "first"),
            success(map(
                "oldName", "First",
                "newName", "first",
                "symbolKind", "class",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "types/Delta.java", List.of(
                    map("line", 4, "column", 17, "endColumn", 22, "oldText", "First", "newText", "first"))),
                "warnings", List.of("By convention, Java type names usually start with an uppercase letter")),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("a field renamed to an uppercase name goes ahead with JDT's naming warning")
    void field_uppercaseNameWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("fields/Fields.java", 3, 8, "Count"),
            success(map(
                "oldName", "count",
                "newName", "Count",
                "symbolKind", "field",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "fields/Fields.java", List.of(
                    map("line", 3, "column", 8, "endColumn", 13, "oldText", "count", "newText", "Count"))),
                "warnings", List.of("The field name is discouraged. According to convention, field names should"
                    + " start with a lowercase letter.")),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    @Test
    @DisplayName("a local renamed to an uppercase name goes ahead with JDT's naming warning")
    void local_uppercaseNameWarned() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("locals/Locals.java", 4, 12, "A"),
            success(map(
                "oldName", "a",
                "newName", "A",
                "symbolKind", "variable",
                "totalEdits", 2,
                "filesAffected", 1,
                "editsByFile", map(ROOT + "locals/Locals.java", List.of(
                    map("line", 6, "column", 15, "endColumn", 16, "oldText", "a", "newText", "A"),
                    map("line", 4, "column", 12, "endColumn", 13, "oldText", "a", "newText", "A"))),
                "warnings", List.of("This name is discouraged. According to convention, names of local variables"
                    + " should start with a lowercase letter.")),
                map("totalCount", 2, "returnedCount", 2, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    // ========== elements rename_symbol does not rename, and modules ==========

    @Test
    @DisplayName("a package name, at its last segment, is refused, naming the package")
    void package_refused() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("types/Beta.java", 0, 20, "kinds"),
            invalidParameter("Invalid parameter 'position': Symbol at position is the package com.example.types,"
                + " which rename_symbol does not rename"));
    }

    @Test
    @DisplayName("a package declaration is refused, naming it")
    void packageDeclaration_refused() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("types/Beta.java", 0, 0, "kinds"),
            invalidParameter("Invalid parameter 'position': Symbol at position is the package declaration"
                + " com.example.types, which rename_symbol does not rename"));
    }

    @Test
    @DisplayName("an import declaration is refused, naming it")
    void importDeclaration_refused() {
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at("types/Beta.java", 2, 0, "Lists"),
            invalidParameter("Invalid parameter 'position': Symbol at position is the import declaration"
                + " java.util.List, which rename_symbol does not rename"));
    }

    @Test
    @DisplayName("a module, at its declaration's keyword, is renamed in its declaration, as JDT's module rename finds no conflict")
    void module_renamed() throws Exception {
        JdtServiceImpl modular = helper.loadProject("modular-maven");
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", helper.getFixturePath("modular-maven").resolve("src/main/java/module-info.java").toString());
        args.put("line", 0);
        args.put("column", 0);
        args.put("newName", "renamed");
        assertPayload(mapper, new RenameSymbolTool(() -> modular), new EnvelopeHarness(modular), args,
            success(map(
                "oldName", "com.example.modular",
                "newName", "renamed",
                "symbolKind", "module",
                "totalEdits", 1,
                "filesAffected", 1,
                "editsByFile", map("src/main/java/module-info.java", List.of(
                    map("line", 0, "column", 7, "endColumn", 26, "oldText", "com.example.modular", "newText", "renamed")))),
                map("totalCount", 1, "returnedCount", 1, "suggestedNextTools", NEXT_TOOLS)),
            NestedTypes.unixFiles());
    }

    // ========== locals and type parameters ==========

    @Test
    @DisplayName("a local renamed to the name of another local in its scope is refused")
    void local_duplicate() {
        assertRefused("locals/Locals.java", 4, 12, "b", "Duplicate local variable b");
    }

    @Test
    @DisplayName("a type parameter renamed to the name of another type parameter of its type is refused")
    void typeParameter_duplicate() {
        assertRefused("locals/Pair.java", 2, 18, "V",
            "A type variable with this name is already defined in the declaring type.");
    }

    private void assertRefused(String file, int line, int column, String newName, String messages) {
        Map<String, Object> expected = invalidParameter("Invalid parameter 'newName': " + messages);
        assertPayload(mapper, new RenameSymbolTool(() -> service), envelope, at(file, line, column, newName), expected);
    }

    private ObjectNode at(String file, int line, int column, String newName) {
        return at(helper.getFixturePath(FIXTURE), file, line, column, newName);
    }

    private ObjectNode at(Path root, String file, int line, int column, String newName) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", root.resolve(ROOT + file).toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        return args;
    }

    /** The fixture copied, with the edits of renaming the symbol at the position applied to it. */
    @SuppressWarnings("unchecked")
    private Path applied(String file, int line, int column, String newName) throws Exception {
        JdtServiceImpl copy = helper.loadProjectCopy(FIXTURE);
        Path root = helper.getTempDirectory().resolve(FIXTURE);
        ToolResponse response = new RenameSymbolTool(() -> copy).execute(at(root, file, line, column, newName));
        assertTrue(response.isSuccess(), () -> "rename_symbol failed: " + response.getError());
        Map<String, List<Map<String, Object>>> edits = (Map<String, List<Map<String, Object>>>)
            ((Map<String, Object>) response.getData()).get("editsByFile");
        for (Map.Entry<String, List<Map<String, Object>>> edited : edits.entrySet()) {
            Path path = root.resolve(edited.getKey());
            Files.writeString(path, NestedTypes.applied(Files.readString(path, StandardCharsets.UTF_8), edited.getValue()),
                StandardCharsets.UTF_8);
        }
        return root;
    }

    /** {@code files} of the project at {@code root}, loaded afresh, have no compile error. */
    private static void assertCompiles(Path root, String... files) throws Exception {
        JdtServiceImpl reloaded = new JdtServiceImpl();
        reloaded.loadProject(root);
        assertAll(List.of(files).stream().map(file -> (Executable) () ->
            assertEquals(List.of(), NestedTypes.errors(reloaded, root.resolve(file)), "errors in " + file)));
    }
}
