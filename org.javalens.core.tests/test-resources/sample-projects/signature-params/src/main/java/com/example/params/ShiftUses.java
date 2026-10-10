package com.example.params;

/** Uses {@link Shifter#shift(int x)}. */
public class ShiftUses {
    Shifter typed = (int offset) -> offset + 1;
    Shifter block = offset -> {
        int doubled = offset * 2;
        return doubled;
    };
}
