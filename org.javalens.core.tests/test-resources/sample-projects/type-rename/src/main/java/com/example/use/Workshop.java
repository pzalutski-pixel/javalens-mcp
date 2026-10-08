package com.example.use;

import com.example.shapes.Tag;
import com.example.shapes.Widget;
import java.util.function.Function;

import static com.example.shapes.Widget.of;

/**
 * Builds a {@link com.example.shapes.Widget} and its {@link Widget.Part}.
 */
@Tag("workshop")
public class Workshop {

    Widget first = of("first");
    com.example.shapes.Widget second = new com.example.shapes.Widget("second");
    Widget.Part part = new Widget.Part();
    com.example.shapes.Widget.Part spare = new com.example.shapes.Widget.Part();
    Function<String, Widget> maker = Widget::new;
    Bench bench = new Bench();

    @Tag(value = "kind")
    String kind() {
        return Widget.KIND + com.example.shapes.Widget.KIND + second.name();
    }
}

class Bench {

    Widget held = Widget.of("held");
}
