package com.example;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * Test fixture for DI scanning, reflection detection, control flow,
 * data flow, large class detection, and naming convention checks.
 */
public class DiAndReflectionPatterns {

    // Naming violation: field should be camelCase
    private String Bad_Field_Name = "test";

    // Naming violation: constant should be UPPER_SNAKE_CASE
    private static final int badConstant = 42;

    // Correct constant naming
    private static final int GOOD_CONSTANT = 100;

    private String name;
    private int count;
    private boolean active;

    // Reflection usage patterns
    public Object createByReflection(String className) throws Exception {
        Class<?> clazz = Class.forName(className);
        return clazz.getDeclaredConstructor().newInstance();
    }

    public Object invokeByReflection(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        return method.invoke(target);
    }

    public Object getFieldByReflection(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    // Control flow patterns
    public String controlFlowExample(int value, boolean flag) {
        if (value < 0) {
            throw new IllegalArgumentException("Negative value");
        }

        if (value == 0) {
            return "zero";
        }

        String result;
        if (flag) {
            for (int i = 0; i < value; i++) {
                if (i % 2 == 0) {
                    result = "even-" + i;
                }
            }
            result = "flagged";
        } else {
            try {
                result = String.valueOf(value);
            } catch (Exception e) {
                result = "error";
            }
        }

        while (value > 100) {
            value = value / 2;
        }

        return result;
    }

    // Data flow patterns
    public int dataFlowExample(int input) {
        int x = input;
        int y = 0;
        int z;

        x = x + 1;
        y = x * 2;
        z = x + y;

        if (input > 0) {
            z = z + input;
        }

        return z;
    }

    // Naming violation: method should be camelCase
    public void Bad_Method_Name() {
        // intentionally empty
    }

    // Many methods for large class detection
    public void method1() { count++; }
    public void method2() { count++; }
    public void method3() { count++; }
    public void method4() { count++; }
    public void method5() { count++; }
    public void method6() { count++; }
    public void method7() { count++; }
    public void method8() { count++; }
    public void method9() { count++; }
    public void method10() { count++; }
    public void method11() { count++; }
    public void method12() { count++; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public int getCount() { return count; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    // A second reflective Method.invoke call site, so Method.invoke has two
    // call sites. With maxResults=1 the per-reflection-method cap drops the
    // second one, exercising find_reflection_usage's truncation branch.
    public Object secondInvoke(Method method, Object target) throws Exception {
        return method.invoke(target);
    }
}
