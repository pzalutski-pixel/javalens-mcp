package com.example;

import com.example.AnnotationsFixture.Tag;

import java.util.List;

@Marker
public class AnnotationUsages {

    @Marker
    private int markedField;

    @Marker
    public AnnotationUsages() {
    }

    @Marker
    public void markedMethod() {
    }

    @Tag("alpha")
    @Tag("beta")
    public void repeatedTags() {
    }

    @Label("one")
    @Label("two")
    public void repeatedLabels() {
    }

    public void markedParameter(@Marker int p) {
        @Marker int local = p;
        System.out.println(local);
    }

    public List<@Marker String> typeUseUsage() {
        return List.of("typed");
    }
}
