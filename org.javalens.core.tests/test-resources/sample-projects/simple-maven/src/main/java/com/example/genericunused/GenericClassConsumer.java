package com.example.genericunused;

/**
 * Cross-file consumer of {@link GenericClass}. find_references on
 * GenericClass.read() must surface this caller — the generic-class
 * member-reference dimension must work the same as the non-generic
 * one. (find_references uses JDT's index-driven SearchEngine, which
 * is unaffected by the IBinding equality quirk that B1-1 fixed in
 * find_unused_code.)
 */
public class GenericClassConsumer {
    public String consumeString(GenericClass<String> container) {
        return container.read();
    }
}
