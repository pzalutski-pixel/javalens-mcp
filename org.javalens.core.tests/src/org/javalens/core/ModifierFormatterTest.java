package org.javalens.core;

import org.eclipse.jdt.core.Flags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModifierFormatterTest {

    @Test
    @DisplayName("zero flags returns an empty list")
    void zeroFlags_emptyList() {
        assertTrue(ModifierFormatter.format(0).isEmpty());
    }

    @Test
    @DisplayName("public static final emits in declaration order")
    void publicStaticFinal_inOrder() {
        int flags = Flags.AccPublic | Flags.AccStatic | Flags.AccFinal;
        assertEquals(List.of("public", "static", "final"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("private synchronized native default surfaces method-only modifiers")
    void methodOnlyFlags() {
        int flags = Flags.AccPrivate | Flags.AccSynchronized | Flags.AccNative | Flags.AccDefaultMethod;
        assertEquals(
            List.of("private", "synchronized", "native", "default"),
            ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("transient volatile surfaces field-only modifiers")
    void fieldOnlyFlags() {
        int flags = Flags.AccTransient | Flags.AccVolatile;
        assertEquals(List.of("transient", "volatile"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("protected abstract surfaces type/method modifiers in correct order")
    void protectedAbstract_inOrder() {
        int flags = Flags.AccProtected | Flags.AccAbstract;
        assertEquals(List.of("protected", "abstract"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("strictfp emitted in its documented position (after default, before transient)")
    void strictfp_inOrder() {
        // The only one of the 12 modifiers not covered by the existing tests. Source
        // orders: ... default, strictfp, transient, volatile. Combine strictfp with
        // surrounding-position modifiers to pin the ordering.
        int flags = Flags.AccPublic | Flags.AccStrictfp | Flags.AccFinal;
        assertEquals(List.of("public", "final", "strictfp"), ModifierFormatter.format(flags));
    }

    @Test
    @DisplayName("all 12 modifiers together emit in documented declaration order")
    void allTwelveModifiers_inDocumentedOrder() {
        // Combining every modifier the source recognizes catches any ordering regression
        // in a single assertion. JDT's Flags constants are independent bits, so the OR
        // is well-defined even though no real Java element would carry all 12.
        int flags = Flags.AccPublic | Flags.AccProtected | Flags.AccPrivate
            | Flags.AccStatic | Flags.AccFinal | Flags.AccAbstract
            | Flags.AccSynchronized | Flags.AccNative | Flags.AccDefaultMethod
            | Flags.AccStrictfp | Flags.AccTransient | Flags.AccVolatile;
        assertEquals(
            List.of("public", "protected", "private", "static", "final", "abstract",
                "synchronized", "native", "default", "strictfp", "transient", "volatile"),
            ModifierFormatter.format(flags));
    }
}
