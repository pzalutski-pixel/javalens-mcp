package org.javalens.core.fixtures;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.util.Arrays;
import java.util.List;

/** The compile problems JDT reports for a compilation unit of a loaded project. */
public final class CompilationProblems {

    private CompilationProblems() {
    }

    /** The IDs of every problem JDT reports for {@code unit}, parsed with bindings, in report order. */
    public static List<Integer> ids(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).map(IProblem::getID).toList();
    }
}
