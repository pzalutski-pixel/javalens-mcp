package org.fidelity.app;

import org.fidelity.core.Core;
import org.fidelity.lib.Lib;

/** Lives in the source directory app's build declares (src/java). */
public class App {
    public String run() {
        return new Lib().name();
    }

    public Core core() {
        return new Core();
    }
}
