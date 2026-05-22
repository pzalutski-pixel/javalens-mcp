package com.example;

/**
 * Fixture for extract_method's generic-method dimension. The body of
 * processGeneric uses U (the method-level type parameter) in a local
 * variable. Extracting any block touching U requires the new method to
 * declare {@code <U>} too.
 */
public class GenericExtractTarget {

    public <U> U processGeneric(U input) {
        U result = input;
        System.out.println(result);
        return result;
    }
}
