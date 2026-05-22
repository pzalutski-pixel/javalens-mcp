package com.example;

/**
 * Top-level types whose names violate PascalCase. Used to verify that
 * find_naming_violations covers record and annotation declarations,
 * not only classes/interfaces/enums.
 */
@interface bad_annotation {
}

record bad_record(int x) {
}
