package jakarta.persistence;

public @interface OneToOne {
    String mappedBy() default "";
}
