package jakarta.persistence;

public @interface ManyToMany {
    String mappedBy() default "";
}
