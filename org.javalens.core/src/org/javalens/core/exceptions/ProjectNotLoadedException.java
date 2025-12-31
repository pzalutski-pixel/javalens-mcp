package org.javalens.core.exceptions;

/**
 * Thrown when an operation requires a loaded project but none has been loaded.
 * User should call load_project first before using other tools.
 */
public class ProjectNotLoadedException extends RuntimeException {

    public ProjectNotLoadedException() {
        super("No project loaded. Call load_project first.");
    }

    public ProjectNotLoadedException(String message) {
        super(message);
    }

    public ProjectNotLoadedException(String message, Throwable cause) {
        super(message, cause);
    }
}
