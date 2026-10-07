package com.example.stray;

import java.util.List;
import java.util.function.IntConsumer;
import org.absent.Ledger;

@Marker
public class Stray implements Probe {

    public int probe() {
        return 2;
    }

    void use(Ledger ledger, Object item) throws Fault {
        ledger.fill(3);
        ledger.count = 4;
        ledger.self().count = 7;
        IntConsumer filler = ledger::fill;
        Store made = new Store();
        Store cast = (Store) item;
        boolean is = item instanceof Store;
        List<Store> all = List.of(made, cast);
        try {
            ledger.risky();
        } catch (Fault e) {
            ledger.get(item);
        }
    }
}
