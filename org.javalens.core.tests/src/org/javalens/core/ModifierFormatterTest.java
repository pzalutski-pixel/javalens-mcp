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
}
