package com.flow;

import java.util.ArrayList;
import java.util.List;

public class TaintFlow {

    private final List<String> log = new ArrayList<>();

    public void handle(String request) {
        String query = "SELECT " + request;
        run(query);
    }

    void run(String sql) {
        log.add(sql);
    }
}
