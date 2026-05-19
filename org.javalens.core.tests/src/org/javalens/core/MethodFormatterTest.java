package org.javalens.core;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MethodFormatterTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    private IMethod findMethod(String typeName, String methodName) throws Exception {
        IType type = service.findType(typeName);
        assertNotNull(type, "fixture type must resolve: " + typeName);
        for (IMethod m : type.getMethods()) {
            if (m.getElementName().equals(methodName)) return m;
        }
        throw new AssertionError("method not found: " + typeName + "." + methodName);
    }

    private IMethod findConstructor(IType type) throws Exception {
        for (IMethod m : type.getMethods()) {
            if (m.isConstructor()) return m;
        }
        return null;
    }

    @Test
    @DisplayName("two-arg method renders as `name(Type p, Type p): ReturnType`")
    void twoArgMethod() throws Exception {
        IMethod add = findMethod("com.example.Calculator", "add");
        assertEquals("add(int a, int b): int", MethodFormatter.signature(add));
    }

    @Test
    @DisplayName("zero-arg method renders as `name(): ReturnType`")
    void zeroArgMethod() throws Exception {
        IMethod m = findMethod("com.example.Calculator", "getLastResult");
        assertEquals("getLastResult(): int", MethodFormatter.signature(m));
    }

    @Test
    @DisplayName("constructor omits `: ReturnType` and renders the parameter list")
    void constructor() throws Exception {
        IType target = service.findType("com.example.ConstructorTarget");
        assertNotNull(target, "ConstructorTarget fixture must resolve");
        IMethod ctor = findConstructor(target);
        assertNotNull(ctor, "ConstructorTarget should have an explicit constructor");
        // findConstructor returns the first IMethod where isConstructor() is true, which by
        // JDT's source order is `ConstructorTarget(String name)`. Pin the exact signature
        // — the previous test only checked the absence of `: ` and the type-name prefix,
        // which would have passed even if the parameter list was wrong.
        assertEquals("ConstructorTarget(String name)", MethodFormatter.signature(ctor),
            "constructor signature must include the parameter list; got: "
                + MethodFormatter.signature(ctor));
        assertFalse(MethodFormatter.signature(ctor).contains(": "),
            "constructor signature must not include return-type suffix");
        assertTrue(MethodFormatter.signature(ctor).startsWith("ConstructorTarget("),
            "constructor signature must start with the type name");
    }

    @Test
    @DisplayName("returnTypeSimpleName returns simple name for methods")
    void returnTypeSimpleName_method() throws Exception {
        IMethod add = findMethod("com.example.Calculator", "add");
        assertEquals("int", MethodFormatter.returnTypeSimpleName(add));
    }

    @Test
    @DisplayName("returnTypeSimpleName returns null for constructors")
    void returnTypeSimpleName_constructor() throws Exception {
        IType target = service.findType("com.example.ConstructorTarget");
        IMethod ctor = findConstructor(target);
        assertNotNull(ctor, "ConstructorTarget should have a constructor");
        assertNull(MethodFormatter.returnTypeSimpleName(ctor));
    }

    @Test
    @DisplayName("generic return type preserves type arguments (List<String>)")
    void genericReturnType_preservesTypeArguments() throws Exception {
        // UserService.getUsers() returns List<String>. Signature.getSimpleName drops the
        // PACKAGE prefix (java.util) but PRESERVES type arguments. This is the desired
        // behavior — type-arg information is load-bearing for AI consumers reasoning
        // about generic methods. Pinning so a future regression that strips type args
        // (e.g. switching to a different Signature API call) fails loudly.
        IMethod getUsers = findMethod("com.example.service.UserService", "getUsers");
        assertEquals("getUsers(): List<String>", MethodFormatter.signature(getUsers),
            "Generic return type must keep type arguments but drop the package prefix; got: "
                + MethodFormatter.signature(getUsers));
        assertEquals("List<String>", MethodFormatter.returnTypeSimpleName(getUsers));
    }

    @Test
    @DisplayName("reference-typed parameter rendered as simple name (String, not java.lang.String)")
    void referenceTypedParameter_renderedAsSimpleName() throws Exception {
        IMethod addUser = findMethod("com.example.service.UserService", "addUser");
        // Reference type "String" in the parameter list — must render as `String`, not
        // `java.lang.String`. The simple-name behaviour is what JavaLens tools depend on
        // to display compact informational signatures.
        assertEquals("addUser(String username): boolean", MethodFormatter.signature(addUser),
            "String-typed parameter must render as `String`, not the qualified name; got: "
                + MethodFormatter.signature(addUser));
    }

    @Test
    @DisplayName("boolean return type rendered correctly")
    void booleanReturnType() throws Exception {
        IMethod removeUser = findMethod("com.example.service.UserService", "removeUser");
        assertEquals("removeUser(String username): boolean", MethodFormatter.signature(removeUser));
        assertEquals("boolean", MethodFormatter.returnTypeSimpleName(removeUser));
    }

    @Test
    @DisplayName("void return type renders as `: void`")
    void voidReturnType() throws Exception {
        // Animal.speak() returns void with zero args. Previously not tested — the
        // void path in signature() builds `: void` via Signature.getSimpleName which
        // returns the literal "void" for the void signature `V`. A regression that
        // emitted "Void" or "" or null would slip through prior tests since they
        // covered only int/boolean/String/List<String>.
        IMethod speak = findMethod("com.example.Animal", "speak");
        assertEquals("speak(): void", MethodFormatter.signature(speak));
        assertEquals("void", MethodFormatter.returnTypeSimpleName(speak));
    }

    @Test
    @DisplayName("primitive array parameter renders with `[]` suffix")
    void primitiveArrayParameter() throws Exception {
        // ControlFlowPatterns.enhancedForArray(int[] values) — primitive array param.
        // Signature.getSimpleName on `[I` (`int[]`) returns `int[]`. Tested separately
        // from reference-typed arrays because the JDT signature encoding differs.
        IMethod enhancedFor = findMethod("com.example.ControlFlowPatterns", "enhancedForArray");
        assertEquals("enhancedForArray(int[] values): int", MethodFormatter.signature(enhancedFor),
            "int[] param must render as `int[]`, not `int` or `[I`; got: "
                + MethodFormatter.signature(enhancedFor));
    }

    @Test
    @DisplayName("reference-typed array parameter renders with simple name + `[]` (String[], not java.lang.String[])")
    void referenceArrayParameter() throws Exception {
        // HelloWorld.main(String[] args) — reference array param. Signature.getSimpleName
        // on `[Ljava.lang.String;` must produce `String[]`, dropping the package prefix.
        IMethod main = findMethod("com.example.HelloWorld", "main");
        assertEquals("main(String[] args): void", MethodFormatter.signature(main),
            "String[] param must render as `String[]`, not `java.lang.String[]`; got: "
                + MethodFormatter.signature(main));
    }
}
