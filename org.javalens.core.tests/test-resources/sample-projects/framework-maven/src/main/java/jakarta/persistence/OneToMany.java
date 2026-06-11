package jakarta.persistence;

public @interface OneToMany {
    String mappedBy() default "";
}
