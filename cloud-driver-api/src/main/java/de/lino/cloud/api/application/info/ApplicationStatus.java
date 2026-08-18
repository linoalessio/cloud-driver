package de.lino.cloud.api.application.info;

import de.lino.cloud.api.application.Application;

/**
 * An {@link Application}'s current lifecycle phase,
 * as tracked by {@link ApplicationProperties#getApplicationStatus()}.
 */
public enum ApplicationStatus {

    /**
     * The application is loading, i.e. {@code onLoading()} is running or about to run.
     */
    LOADING,

    /**
     * The application has finished loading and is running normally.
     */
    RUNNING,

    /**
     * The application is shutting down, i.e. {@code onEnding()} is running or about to run.
     */
    ENDING,

    /**
     * The application encountered an unrecoverable error.
     */
    ERROR

}
