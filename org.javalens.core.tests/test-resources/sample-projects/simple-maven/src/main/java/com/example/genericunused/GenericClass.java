package com.example.genericunused;

/**
 * Generic class. The private field's type IS the class's type parameter T.
 * The accessor returns T. JDT models the field-declaration binding and the
 * SimpleName-in-method-body binding as two distinct IBinding instances
 * even though both refer to the same field — issue #17's minimal repro.
 */
public class GenericClass<T> {
    private T value;

    public T read() {
        return value;
    }

    public void set(T v) {
        this.value = v;
    }
}
