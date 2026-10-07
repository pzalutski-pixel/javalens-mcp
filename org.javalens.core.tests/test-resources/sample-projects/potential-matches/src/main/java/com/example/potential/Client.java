package com.example.potential;

import java.lang.reflect.Field;
import java.util.List;
import java.util.function.IntConsumer;

@Marker
public class Client implements Probe {

    public int probe() {
        return 1;
    }

    void use(Store store, Object item) throws Fault {
        store.fill(1);
        store.count = 2;
        IntConsumer filler = store::fill;
        filler.accept(5);
        Store made = new Store();
        Store cast = (Store) item;
        boolean is = item instanceof Store;
        List<Store> all = List.of(made, cast);
        try {
            risky();
        } catch (Fault e) {
            all.get(0).fill(6);
        }
    }

    void risky() throws Fault {
    }

    Object read(Field field, Object target) throws IllegalAccessException {
        return field.get(target);
    }

    int measure(String text) {
        return text.length();
    }
}
