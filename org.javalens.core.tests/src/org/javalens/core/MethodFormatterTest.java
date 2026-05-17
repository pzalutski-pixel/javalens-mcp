package org.javalens.core;

import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.javalens.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    @DisplayName("constructor omits the `: ReturnType` suffix")
    void constructor() throws Exception {
        IType target = service.findType("com.example.ConstructorTarget");
        assertNotNull(target, "ConstructorTarget fixture must resolve");
        IMethod ctor = findConstructor(target);
        assertNotNull(ctor, "ConstructorTarget should have an explicit constructor");
        String sig = MethodFormatter.signature(ctor);
        assertEquals(false, sig.contains(": "),
            "constructor signature must not include return-type suffix; got: " + sig);
        assertEquals(true, sig.startsWith("ConstructorTarget("),
            "constructor signature must start with the type name; got: " + sig);
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
}
