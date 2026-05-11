package com.example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD,
         ElementType.PARAMETER, ElementType.LOCAL_VARIABLE,
         ElementType.CONSTRUCTOR, ElementType.TYPE_USE})
public @interface Marker {
}
