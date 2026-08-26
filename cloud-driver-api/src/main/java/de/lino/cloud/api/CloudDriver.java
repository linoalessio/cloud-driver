package de.lino.cloud.api;

import de.lino.cloud.api.factory.*;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.logging.TerminalLogFormatter;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.database.entity.Serialized;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Logger;

/**
 * Facade over persistence, files, extensions, connectivity, events, and REST
 * exposure. Deliberately thin - implementations supply the real logic.
 */
public abstract class CloudDriver {

    /** Shared singleton instance, assigned by a concrete implementation. */
    protected static volatile CloudDriver INSTANCE;

    /**
     * Returns the installed {@link CloudDriver} instance.
     *
     * @return the shared instance
     * @throws NullPointerException if no implementation has been installed yet
     */
    public synchronized static CloudDriver getInstance() {
        return Asserts.requireNonNull(INSTANCE);
    }

    /** Lazily-initialized, process-wide logger. */
    private static volatile Logger LOGGER;

    /**
     * Returns the process-wide logger, creating it on first access.
     *
     * @return the shared logger
     */
    public final Logger getLogger() {
        Logger logger = LOGGER;

        if (logger == null) {

            synchronized (CloudDriver.class) {
                logger = LOGGER;
                if (logger == null) {
                    logger = Logger.getLogger(CloudDriver.class.getName());
                    logger.setUseParentHandlers(false);
                    ConsoleHandler handler = new ConsoleHandler();
                    handler.setFormatter(new TerminalLogFormatter());
                    logger.addHandler(handler);
                    LOGGER = logger;
                }
            }
        }

        return logger;
    }

    /**
     * Returns the entity-persistence facet.
     *
     * @return the {@link DataFactory}
     */
    public abstract DataFactory getDataFactory();

    /**
     * Returns the file-persistence facet.
     *
     * @return the {@link FileFactory}
     */
    public abstract FileFactory getFileFactory();

    /**
     * Returns the extension-lifecycle facet.
     *
     * @return the {@link ExtensionFactory}
     */
    public abstract ExtensionFactory getExtensionFactory();

    /**
     * Returns the connectivity-reporting facet.
     *
     * @return the {@link ConnectivityChecker}
     */
    public abstract ConnectivityChecker getConnectivityChecker();

    /**
     * Returns the event facet.
     *
     * @return the {@link EventFactory}
     */
    public abstract EventFactory getEventFactory();

    /**
     * Returns the REST-exposure facet. Unauthenticated by default.
     *
     * @return the {@link RestFactory}
     */
    public abstract RestFactory getRestFactory();

    /**
     * Returns the interactive terminal.
     *
     * @return the {@link Terminal}
     */
    public abstract Terminal getTerminal();

    /** Shuts down every facet owned by this instance. */
    public abstract void shutdown();

}
