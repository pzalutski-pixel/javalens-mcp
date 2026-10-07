package org.capped.use;

/**
 * Names Widget without importing it, and twelve types named Widget can be imported.
 */
public class Assembly {

    Widget part;
}

/**
 * Never reads its private field.
 */
class Spare {

    private int spare;
}
