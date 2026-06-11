package org.springframework.stereotype;

public @interface Component {
    String value() default "";
}
