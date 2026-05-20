package com.example;

import com.nonexistent.Banana;
import java.io.FileInputStream;

/**
 * Fixture for get_quick_fixes / apply_quick_fix problem-trigger coverage.
 *
 * <p>Each method intentionally triggers a different IProblem:
 * <ul>
 *   <li>{@link #usesUndefinedType()} references {@code Date} without an import — JDT
 *       reports IProblem.UndefinedType, and {@code get_quick_fixes} must propose an
 *       {@code add_import:java.util.Date} fix.</li>
 *   <li>{@link #hasUnhandledException()} calls a {@code FileInputStream(String)}
 *       constructor that declares {@code FileNotFoundException} without surrounding
 *       try/catch or a {@code throws} on the enclosing method — JDT reports
 *       IProblem.UnhandledException, and the tool must propose both
 *       {@code add_throws:*} and {@code surround_try_catch:*} fixes.</li>
 *   <li>The {@code import com.nonexistent.Banana;} statement at the top of the file
 *       triggers IProblem.ImportNotFound and the tool must propose a
 *       {@code remove_import:*} fix at that line.</li>
 * </ul>
 *
 * <p>Co-located in its own fixture project so the deliberate compile errors do not
 * leak into project-wide diagnostic counts in {@code simple-maven} tests.
 */
public class BrokenSymbols {

    public void usesUndefinedType() {
        Date d = null;
        System.out.println(d);
    }

    public void hasUnhandledException() {
        new FileInputStream("missing.txt");
    }

    public Banana returnsUnknownImport() {
        return null;
    }
}
