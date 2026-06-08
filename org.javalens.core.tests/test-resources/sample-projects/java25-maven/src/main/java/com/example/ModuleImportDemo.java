package com.example;

import module java.base;
import java.util.Map;

/// Uses a Java 25 module import (JEP 511): {@code import module java.base;}
/// brings in java.base's exported packages, so {@code List} resolves unqualified.
/// The single-type {@code import java.util.Map;} sits alongside it so tools can
/// be checked for handling the module import without dropping ordinary imports.
public class ModuleImportDemo {

    public List<String> names() {
        return List.of("x", "y");
    }

    public Map<String, Integer> counts() {
        return Map.of("x", 1);
    }
}
