package org.javalens.core;

import org.eclipse.jdt.core.Flags;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a JDT modifier-flag bitmask as a list of human-readable keywords:
 * {@code public}, {@code protected}, {@code private}, {@code static},
 * {@code final}, {@code abstract}, {@code synchronized}, {@code native},
 * {@code default}, {@code strictfp}, {@code transient}, {@code volatile}.
 *
 * <p>Replaces 12 near-identical {@code getModifiers(int)} helpers that lived
 * inside individual tools (AnalyzeTypeTool, GetTypeMembersTool, etc.) and
 * varied subtly in which flags they reported. Single source of truth.
 *
 * <p>Flags not applicable to the element being inspected simply remain
 * absent (JDT's {@link Flags#isXxx} returns false when the bit isn't set),
 * so the same caller is safe for types, methods, fields, and parameters.
 */
public final class ModifierFormatter {

    private ModifierFormatter() {}

    public static List<String> format(int flags) {
        List<String> modifiers = new ArrayList<>();
        if (Flags.isPublic(flags))       modifiers.add("public");
        if (Flags.isProtected(flags))    modifiers.add("protected");
        if (Flags.isPrivate(flags))      modifiers.add("private");
        if (Flags.isStatic(flags))       modifiers.add("static");
        if (Flags.isFinal(flags))        modifiers.add("final");
        if (Flags.isAbstract(flags))     modifiers.add("abstract");
        if (Flags.isSynchronized(flags)) modifiers.add("synchronized");
        if (Flags.isNative(flags))       modifiers.add("native");
        if (Flags.isDefaultMethod(flags))modifiers.add("default");
        if (Flags.isStrictfp(flags))     modifiers.add("strictfp");
        if (Flags.isTransient(flags))    modifiers.add("transient");
        if (Flags.isVolatile(flags))     modifiers.add("volatile");
        return modifiers;
    }
}
