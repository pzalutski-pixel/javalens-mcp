package org.example.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Public type with a nested record, a lambda and method references.
 */
public final class Ledger {

    public record Entry(String account, long cents) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public void add(Entry entry) {
        entries.add(entry);
    }

    public long total(String account) {
        return entries.stream()
            .filter(entry -> entry.account().equals(account))
            .mapToLong(Entry::cents)
            .sum();
    }

    public String accounts() {
        return entries.stream().map(Entry::account).distinct().collect(Collectors.joining(","));
    }
}
