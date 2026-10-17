package org.fidelity.app;

import org.fidelity.core.Core;

public class App {
    public String run() {
        return new Core().name();
    }
}
