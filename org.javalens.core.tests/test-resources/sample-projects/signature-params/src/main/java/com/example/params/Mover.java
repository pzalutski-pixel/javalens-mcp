package com.example.params;

public class Mover implements Shifter {
    /**
     * Shifts.
     *
     * @param offset how far
     * @return the shifted value
     */
    @Override
    public int shift(int offset) {
        Runnable report = () -> System.out.println(offset);
        Object probe = new Object() {
            int at() {
                return offset;
            }
        };
        report.run();
        return offset + probe.hashCode();
    }
}
