package de.lino.cloud.api.extension.info;

import de.lino.cloud.api.extension.Extension;

/**
 * An {@link Extension}'s current lifecycle phase,
 * as tracked by {@link ExtensionProperties#getExtensionStatus()}.
 */
public enum ExtensionStatus {

    /**
     * The extension is loading, i.e. {@code onLoading()} is running or about to run.
     */
    LOADING,

    /**
     * The extension has finished loading and is running normally.
     */
    RUNNING,

    /**
     * The extension is shutting down, i.e. {@code onEnding()} is running or about to run.
     */
    ENDING,

    /**
     * The extension encountered an unrecoverable error.
     */
    ERROR

}
