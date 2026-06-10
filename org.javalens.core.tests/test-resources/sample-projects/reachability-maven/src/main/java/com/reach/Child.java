package com.reach;

/**
 * Declares an explicit constructor, so instantiation edges target the
 * constructor node (unlike App/EnglishGreeter/TestedOnly whose implicit
 * constructors make instantiation edges target the type node).
 */
public class Child extends Base {

    public Child() {
    }

    @Override
    public String hook() {
        return "child";
    }

    public static Base create() {
        return new Child();
    }
}
