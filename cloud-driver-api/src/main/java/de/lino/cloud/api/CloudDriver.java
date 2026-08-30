package de.lino.cloud.api;

import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.logging.TerminalLogFormatter;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.json.JsonDocument;

import java.util.logging.ConsoleHandler;
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
     * Returns the connectivity-reporting facet.
     *
     * @return the {@link ConnectivityChecker}
     */
    public abstract ConnectivityChecker getConnectivityChecker();

    /**
     * Returns the container bundling every persistence/extension/event/REST facet
     * ({@link de.lino.cloud.api.factory.DataFactory}, {@link de.lino.cloud.api.factory.FileFactory},
     * {@link de.lino.cloud.api.factory.ExtensionFactory}, {@link de.lino.cloud.api.factory.EventFactory},
     * {@link de.lino.cloud.api.factory.RestFactory}) this instance was constructed with.
     *
     * @return the {@link IFactoryContainer}
     */
    public abstract IFactoryContainer getFactoryContainer();

    /**
     * Returns the container bundling higher-level services built on top of
     * {@link #getFactoryContainer()}'s raw facets - {@link
     * de.lino.cloud.api.user.ICloudUserService} and {@link
     * de.lino.cloud.api.jwt.auth.IAuthService}. Unlike {@link #getFactoryContainer()},
     * this container may start out with both services unset - see {@link
     * IServiceContainer}'s Javadoc for when/how they get published.
     *
     * @return the {@link IServiceContainer}
     */
    public abstract IServiceContainer getServiceContainer();

    /**
     * Returns the interactive terminal.
     *
     * @return the {@link Terminal}
     */
    public abstract Terminal getTerminal();

    /** Shuts down every facet owned by this instance. */
    public abstract void shutdown();

    /**
     * Loads this deployment's local configuration file (e.g. {@code "rest-api-port"},
     * {@code "jwt-signing-key"}), re-reading it from disk on every call rather than caching it.
     *
     * @return the parsed {@code configuration.json} document, resolved against {@link
     * Constraints#CONFIGURATION_PATH}
     */
    public JsonDocument getConfiguration() {
        return JsonDocument.load(Constraints.CONFIGURATION_PATH.resolve("configuration.json"));
    }

}
