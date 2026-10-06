package org.example.verify;

/**
 * Package-private type without lambdas that posts entries to a ledger.
 */
final class Posting {

    private final Ledger ledger;

    Posting(Ledger ledger) {
        this.ledger = ledger;
    }

    void post(String account, long cents) {
        ledger.add(new Ledger.Entry(account, cents));
    }
}
