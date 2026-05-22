package com.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

/**
 * Sample test class for testing FindTestsTool.
 * Contains JUnit 5 annotations.
 */
public class SampleTest {

    private Calculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new Calculator();
    }

    @AfterEach
    void tearDown() {
        calculator = null;
    }

    @Test
    void testAddition() {
        int result = calculator.add(2, 3);
        assert result == 5;
    }

    @Test
    void testSubtraction() {
        int result = calculator.subtract(5, 3);
        assert result == 2;
    }

    @Test
    @DisplayName("Test multiplication of two numbers")
    void testMultiplication() {
        int result = calculator.multiply(4, 3);
        assert result == 12;
    }

    @Test
    @Disabled("Not implemented yet")
    void testDivision() {
        // This test is disabled
    }

    @Test
    @Disabled
    void anotherDisabledTest() {
        // Another disabled test without reason
    }

    @Test
    @DisplayName("Custom display name for this test")
    void testWithCustomDisplayName() {
        assert true;
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    void testParameterized(int value) {
        assert value > 0;
    }

    @TestFactory
    Stream<DynamicTest> dynamicTestsFromFactory() {
        return Stream.of(
            DynamicTest.dynamicTest("first", () -> { assert true; }),
            DynamicTest.dynamicTest("second", () -> { assert true; })
        );
    }

    /**
     * Not a test method - no @Test annotation.
     */
    void helperMethod() {
        // This should not be detected as a test
    }

    /**
     * Private method - not a test.
     */
    private void privateHelper() {
        // This should not be detected as a test
    }

    /**
     * Nested test group — JUnit 5 @Nested style. The tool's visitor descends into
     * TypeDeclaration nodes, so this inner class is reported as its own test class.
     */
    @Nested
    class NestedGroup {

        @Test
        void nestedTestOne() {
        }

        @Test
        void nestedTestTwo() {
        }
    }
}
