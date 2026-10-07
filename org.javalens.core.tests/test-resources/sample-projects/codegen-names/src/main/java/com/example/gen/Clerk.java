package com.example.gen;

import com.example.gen.model.Seal;
import com.example.gen.model.Stamp;
import com.example.gen.model.Stamp.Ink;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;

public class Clerk {

    public List<Stamp> stamps(Entry<String, Ink> key) throws IOException {
        return new ArrayList<>();
    }

    public Desk.Voucher issue(String note, Route route) {
        return new Desk.Voucher();
    }

    public <S extends Seal> S sealed(S item) {
        return item;
    }
}
