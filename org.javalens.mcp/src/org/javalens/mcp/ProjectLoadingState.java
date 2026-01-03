package org.javalens.mcp;

/**
 * Represents the state of project loading.
 * Used to provide feedback to AI agents about whether a project
 * is still loading, loaded successfully, or failed to load.
 */
public enum ProjectLoadingState {
    /**
     * No project configured or requested.
     */
    NOT_LOADED,

    /**
     * Project is currently being loaded (async operation in progress).
     */
    LOADING,

    /**
     * Project loaded successfully and is ready for use.
     */
    LOADED,

    /**
     * Project loading failed with an error.
     */
    FAILED
}
