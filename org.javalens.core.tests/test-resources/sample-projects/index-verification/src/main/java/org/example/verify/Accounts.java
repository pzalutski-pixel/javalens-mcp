package org.example.verify;

/**
 * A file whose only top-level type is not named after the file.
 */
final class AccountBook {

    Ledger.Entry opening(String account) {
        return new Ledger.Entry(account, 0L);
    }
}
