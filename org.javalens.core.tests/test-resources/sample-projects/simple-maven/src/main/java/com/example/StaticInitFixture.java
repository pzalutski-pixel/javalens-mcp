package com.example;

/**
 * Minimal fixture for exercising the {@code IJavaElement.INITIALIZER} branch in
 * {@code ElementKindResolver.kindOf}. A static initializer block produces an
 * {@code IInitializer} handle accessible via {@code IType.getInitializer(1)};
 * no production tool uses this class for any other purpose.
 */
public class StaticInitFixture {

    public static int counter;

    static {
        counter = 42;
    }
}
