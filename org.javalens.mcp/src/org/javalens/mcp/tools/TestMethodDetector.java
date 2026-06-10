package org.javalens.mcp.tools;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.javalens.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test-method detection shared by find_tests, find_unreachable_code, and
 * find_affected_tests. Detection is annotation-name based (JUnit 4/5, TestNG)
 * and works with unresolved test-framework imports.
 */
public final class TestMethodDetector {

    private static final Logger log = LoggerFactory.getLogger(TestMethodDetector.class);

    private TestMethodDetector() {
    }

    /**
     * A detected test method. {@code element} is null when the method's
     * binding could not be resolved (detection itself is AST-only).
     */
    public record TestMethod(IMethod element, String className, String methodName,
                             boolean disabled, String framework, Path file, int line) {
    }

    /** True for @Test, @ParameterizedTest, @RepeatedTest, @TestFactory, @TestTemplate. */
    public static boolean isTestMethod(MethodDeclaration method) {
        for (Object mod : method.modifiers()) {
            if (mod instanceof Annotation ann) {
                String name = annotationName(ann);
                if (name.equals("Test") || name.equals("ParameterizedTest")
                    || name.equals("RepeatedTest") || name.equals("TestFactory")
                    || name.equals("TestTemplate")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the method carries @Disabled (JUnit 5) or @Ignore (JUnit 4). */
    public static boolean isDisabled(MethodDeclaration method) {
        return hasDisablingAnnotation(method.modifiers());
    }

    /** True when the type carries @Disabled or @Ignore. */
    public static boolean isDisabled(TypeDeclaration type) {
        return hasDisablingAnnotation(type.modifiers());
    }

    private static boolean hasDisablingAnnotation(List<?> modifiers) {
        for (Object mod : modifiers) {
            if (mod instanceof Annotation ann) {
                String name = annotationName(ann);
                if (name.equals("Disabled") || name.equals("Ignore")) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The @DisplayName string value, or null. */
    public static String displayName(MethodDeclaration method) {
        for (Object mod : method.modifiers()) {
            if (mod instanceof Annotation ann && annotationName(ann).equals("DisplayName")
                && ann instanceof SingleMemberAnnotation sma
                && sma.getValue() instanceof StringLiteral sl) {
                return sl.getLiteralValue();
            }
        }
        return null;
    }

    /** Simple name of an annotation regardless of qualification. */
    public static String annotationName(Annotation ann) {
        if (ann.getTypeName() instanceof SimpleName sn) {
            return sn.getIdentifier();
        } else if (ann.getTypeName() instanceof QualifiedName qn) {
            return qn.getName().getIdentifier();
        }
        return ann.getTypeName().toString();
    }

    /**
     * Framework attribution: fully qualified annotation names first
     * (jupiter/junit4/testng), then the lifecycle-annotation heuristic
     * (@BeforeEach/@AfterEach vs @Before/@After).
     */
    public static String detectFramework(TypeDeclaration type) {
        for (MethodDeclaration method : type.getMethods()) {
            for (Object mod : method.modifiers()) {
                if (mod instanceof Annotation ann) {
                    String fullName = ann.getTypeName().toString();
                    if (fullName.contains("jupiter") || fullName.contains("junit5")) {
                        return "JUnit5";
                    } else if (fullName.contains("junit4") || fullName.equals("org.junit.Test")) {
                        return "JUnit4";
                    } else if (fullName.contains("testng")) {
                        return "TestNG";
                    }
                }
            }
        }
        for (MethodDeclaration method : type.getMethods()) {
            for (Object mod : method.modifiers()) {
                if (mod instanceof Annotation ann) {
                    String name = annotationName(ann);
                    if (name.equals("BeforeEach") || name.equals("AfterEach")) {
                        return "JUnit5";
                    } else if (name.equals("Before") || name.equals("After")) {
                        return "JUnit4";
                    }
                }
            }
        }
        return null;
    }

    /**
     * Sweep every source file and return all detected test methods (including
     * disabled ones — a disabled test still references the code it exercises).
     * Bindings are resolved so callers can map methods into the project graph.
     */
    public static List<TestMethod> collectTestMethods(IJdtService service) {
        List<TestMethod> result = new ArrayList<>();
        for (Path file : service.getAllJavaFiles()) {
            try {
                ICompilationUnit cu = service.getCompilationUnit(file);
                if (cu == null) {
                    continue;
                }
                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                if (ast == null) {
                    continue;
                }
                ast.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(TypeDeclaration node) {
                        boolean classDisabled = isDisabled(node);
                        String framework = detectFramework(node);
                        for (MethodDeclaration method : node.getMethods()) {
                            if (!isTestMethod(method)) {
                                continue;
                            }
                            IMethodBinding binding = method.resolveBinding();
                            IMethod element = binding != null && binding.getJavaElement() instanceof IMethod m
                                ? m : null;
                            result.add(new TestMethod(
                                element,
                                node.getName().getIdentifier(),
                                method.getName().getIdentifier(),
                                classDisabled || isDisabled(method),
                                framework,
                                file,
                                ast.getLineNumber(method.getName().getStartPosition()) - 1));
                        }
                        return true;
                    }
                });
            } catch (Exception e) {
                log.debug("Error collecting test methods in {}: {}", file, e.getMessage());
            }
        }
        return result;
    }
}
