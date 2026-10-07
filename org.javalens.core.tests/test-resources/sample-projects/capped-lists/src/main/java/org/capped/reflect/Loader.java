package org.capped.reflect;

/**
 * One call to each public overload of Class.forName.
 */
public class Loader {

    Class<?> byName(String name) throws ClassNotFoundException {
        return Class.forName(name);
    }

    Class<?> byLoader(String name, ClassLoader loader) throws ClassNotFoundException {
        return Class.forName(name, true, loader);
    }

    Class<?> byModule(Module module, String name) {
        return Class.forName(module, name);
    }
}
