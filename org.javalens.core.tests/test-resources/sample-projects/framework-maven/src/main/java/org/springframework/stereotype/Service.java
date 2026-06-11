package org.springframework.stereotype;

public @interface Service {
    String value() default "";
}
