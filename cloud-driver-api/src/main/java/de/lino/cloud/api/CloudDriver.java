package de.lino.cloud.api;

import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.security.connectivity.ConnectivityChecker;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.logging.TerminalLogFormatter;
import de.lino.cloud.api.utility.Asserts;
import de.lino.cloud.api.utility.Constraints;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.util.List;
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
     * Wipes this deployment's stored data back to an empty state, without stopping any running
     * facet or terminating the process. There is no undo.
     *
     * <p>Clears every entity section the implementation can name, every object-storage object a
     * file row references, and the data owned by each <em>published</em> optional service - file
     * versions, thumbnails, webhook subscriptions and their recorded delivery history, and the
     * keyword search index. An optional service that is not published simply has nothing to clear,
     * and a failure inside one of them is logged rather than propagated, so one dead subsystem
     * cannot leave the rest of the wipe undone. A failure to clear an entity section, by contrast,
     * is propagated once every section has been attempted - a half-finished wipe must never report
     * success.
     *
     * <p>Deliberately out of reach: the raw {@code kek} section holding key-encryption-key
     * material (it protects nothing once every entity above is gone), the semantic vector store
     * owned by the external {@code cloud-driver-intelligence} service, Redis coordination state
     * (rate-limit windows, scheduler locks), the off-site database backup bucket, and the
     * configuration files on disk. Decommissioning a deployment means clearing those separately.
     *
     * @see #resetScope()
     */
    public abstract void reset();

    /**
     * The human-readable name of everything {@link #reset()} would clear if it ran right now -
     * one entry per entity section, plus one per published optional service and one for object
     * storage when this deployment has it, in the order {@link #reset()} clears them.
     *
     * <p>Names targets, never rows: it reads nothing from the database, so it is safe to call on
     * a deployment of any size. Exists so an operator can be shown the real scope before
     * confirming a wipe, from the same list the wipe itself walks.
     *
     * @return every target {@link #reset()} would clear, in the order it clears them
     */
    @NotNull
    public abstract List<String> resetScope();

    /**
     * Loads this deployment's local configuration file (e.g. {@code "rest-server-port"},
     * {@code "jwt-signing-key"}), re-reading it from disk on every call rather than caching it.
     *
     * @return the parsed {@code configuration.json} document, resolved against {@link
     * Constraints#CONFIGURATION_PATH}
     */
    public JsonDocument getConfiguration() {
        return JsonDocument.load(Constraints.CONFIGURATION_PATH.resolve("configuration.json"));
    }

}
