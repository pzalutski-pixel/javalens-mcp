package com.example;

/**
 * Fixture for analyze_type's bounded multi-parameter coverage.
 * Declares two type parameters with non-trivial bounds:
 *   T extends Number  — simple upper bound
 *   U extends Comparable<U>  — F-bounded (self-referential) parameter
 * AnalyzeTypeTool should report both names AND their bounds.
 */
public class BoundedMultiParam<T extends Number, U extends Comparable<U>> {

    private T number;
    private U key;

    public T getNumber() { return number; }
    public U getKey() { return key; }
}
