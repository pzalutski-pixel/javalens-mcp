package jakarta.persistence;

public @interface Table {
    String name() default "";
}
