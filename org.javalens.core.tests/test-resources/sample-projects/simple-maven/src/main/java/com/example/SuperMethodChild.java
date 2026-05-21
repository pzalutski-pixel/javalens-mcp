package com.example;

import java.util.function.Function;

/**
 * Subclass that invokes super.greet(name) — the SuperMethodInvocation AST node
 * form, distinct from MethodInvocation. Also captures super::greet as a
 * SuperMethodReference (a fourth method-reference AST node form, distinct
 * from ExpressionMethodReference / TypeMethodReference / CreationReference).
 * Does NOT override greet, so changing the parent's signature affects only
 * the super-call/reference sites (not an override decl). Used by:
 *   - change_method_signature propagation tests (A-4, A-7)
 *   - find_method_references SuperMethodReference coverage (A-9)
 */
public class SuperMethodChild extends SuperMethodParent {

    public String enthusiasticGreet(String name) {
        return super.greet(name) + "!";
    }

    public Function<String, String> greetReference() {
        return super::greet;
    }
}
