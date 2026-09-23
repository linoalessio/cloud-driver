package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.extension.ExtensionRegisterEvent;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Lists, inspects, starts, and stops registered {@link Extension}s from the terminal - the
 * interactive counterpart of {@link ExtensionFactory}'s own programmatic {@code start}/{@code
 * stop}/{@code getExtensions} surface.
 */
public class ExtensionCommand implements Command {

    /** The host extension, excluded from {@code start}/{@code stop} so this process is never torn down by its own console. */
    private static final String BOOTSTRAP_EXTENSION_NAME = "cloud-driver-bootstrap";

    /** The extension owning every command, including this one - restartable, but never stoppable on its own. */
    private static final String TERMINAL_EXTENSION_NAME = "cloud-driver-terminal";

    /** How long {@code start} waits for the restarted extension to leave {@link ExtensionStatus#LOADING} before reporting its state. */
    private static final long START_REPORT_TIMEOUT_MILLIS = 3_000L;

    /** Poll interval while waiting in {@link #awaitSettledStatus(Extension)}. */
    private static final long START_REPORT_POLL_MILLIS = 25L;

    /** @return {@code "extensions"} */
    @Override
    public @NotNull String name() {
        return "extensions";
    }

    /** @return {@code "extension"}, {@code "ext"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("extension", "ext");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Get a list of information about the extensions";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("extensions list", "Every loaded extension and its state"),
                CommandUsage.of("extensions info <name>", "Details about one extension"),
                CommandUsage.of("extensions start <name>", "Start one extension, restarting it if it is already running"),
                CommandUsage.of("extensions stop <name>", "Stop one extension at runtime")
        );
    }

    /**
     * Dispatches to one of {@code list}/{@code info}/{@code start}/{@code stop} based on {@code
     * arguments}' first token, printing a usage message if it is empty or unrecognized: {@code
     * list} prints every registered extension's name, version, status, and description; {@code
     * info <name>} prints one extension's full detail; {@code start <name>}/{@code stop <name>}
     * drive that extension through {@link ExtensionFactory#start}/{@link ExtensionFactory#stop}
     * and dispatch the matching {@link ExtensionRegisterEvent}/{@link ExtensionUnregisterEvent}
     * (the host bootstrap extension itself, {@code "cloud-driver-bootstrap"}, is refused for both
     * {@code start} and {@code stop} to avoid tearing down the process that hosts this very
     * command; {@code "cloud-driver-terminal"} is refused for {@code stop} alone, because it owns
     * every command including this one, while {@code start} restarts it and works). Sub-command
     * tokens are matched case-insensitively, like every other command's, and {@code start}/{@code
     * stop} report the state the extension actually settled in rather than asserting success.
     *
     * @param arguments the sub-command and its own arguments, split on whitespace
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        if (arguments.isEmpty()) {
            this.sendUsage();
            return;
        }

        final Terminal terminal = this.terminal();
        final ExtensionFactory extensionFactory = CloudDriver.getInstance().getFactoryContainer().getExtensionFactory();

        if (arguments.hasCommand(0, "list")) {

            terminal.emptyLine();
            terminal.displayApproved("Registered extensions (&b%s&7): ", extensionFactory.getExtensions().size());
            extensionFactory.getExtensions().forEach(extension -> {
                final ExtensionProperties properties = extension.getExtensionProperties();
                terminal.displayApproved(
                        "- &b%s &7(v%s) (%s) | &7%s"
                                , properties.getExtensionName()
                                , properties.getExtensionVersion()
                                , extensionStatusOf(properties.getExtensionStatus())
                                , properties.getDescription().isEmpty() ? "EMPTY" : properties.getDescription()
                );
            });
            terminal.emptyLine();

            return;
        }

        if (arguments.hasCommand(0, "info") && arguments.hasLength(1)) {

            final String extensionName = arguments.command(1);
            final Optional<Extension> extension = extensionFactory.findByName(extensionName);

            if (extension.isEmpty()) {
                terminal.displayApproved("Extension '&b%s&7' not found", extensionName);
                return;
            }

            final ExtensionProperties properties = extension.get().getExtensionProperties();

            terminal.emptyLine();
            terminal.displayApproved("Information about '&b&l%s&7': ", properties.getExtensionName());
            terminal.displayApproved("Version: &f%s", properties.getExtensionVersion());
            terminal.displayApproved("Description: &f%s", properties.getDescription().isEmpty() ? "EMPTY" : properties.getDescription());
            terminal.displayApproved("Status: %s", extensionStatusOf(properties.getExtensionStatus()));
            terminal.displayApproved("Build: &e%s", extension.get().getProjectBuildType().getName());
            terminal.displayApproved("Authors: &3%s", String.join("&7, &e", properties.getAuthors()));
            terminal.displayApproved("Dependencies: &c%s", properties.getDependencies().isEmpty() ? "EMPTY" : String.join("&7, &c", properties.getDependencies()));
            terminal.emptyLine();

            return;
        }

        if ((arguments.hasCommand(0, "start") || arguments.hasCommand(0, "stop")) && arguments.hasLength(1)) {

            // Read as a boolean, never as the raw token: hasCommand matches case-insensitively and
            // nothing lowercases an argument on the way in, so switching on the typed text made
            // 'extensions START <name>' print the usage block - indistinguishable from a typo -
            // while neither starting nor stopping anything.
            final boolean starting = arguments.hasCommand(0, "start");
            final String extensionName = arguments.command(1);
            final Optional<Extension> extension = extensionFactory.findByName(extensionName);

            if (extension.isEmpty()) {
                terminal.displayApproved("Extension '&b%s&7' not found", extensionName);
                return;
            }

            // Resolved from the extension itself, never the typed token: the guards below must
            // hold however the operator spelled the name.
            final String resolvedName = extension.get().getExtensionProperties().getExtensionName();

            if (BOOTSTRAP_EXTENSION_NAME.equalsIgnoreCase(resolvedName)) {
                terminal.displayApproved("Extension '&b%s&7' cannot be modified.", resolvedName);
                return;
            }

            if (!starting && TERMINAL_EXTENSION_NAME.equalsIgnoreCase(resolvedName)) {
                terminal.displayApproved("Extension '&b%s&7' cannot be stopped - it owns every command, this one included.", resolvedName);
                terminal.displayApproved("&7Use '&bextensions start %s&7' to restart it.", resolvedName);
                return;
            }

            if (starting) {
                extensionFactory.stop(extension.get());
                extensionFactory.start(extension.get(), new String[0]);
                final ExtensionStatus settled = this.awaitSettledStatus(extension.get());
                if (settled == ExtensionStatus.RUNNING) {
                    CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(ExtensionRegisterEvent.class, new JsonDocument().append("extensionName", extensionName));
                    terminal.displayApproved("Extension '&b%s&7' is now &arunning", resolvedName);
                    return;
                }
                terminal.displayApproved("Extension '&b%s&7' &cfailed to start &7- see the log (status: %s)",
                        resolvedName, extensionStatusOf(settled));
                return;
            }

            if (extension.get().getExtensionProperties().getExtensionStatus().equals(ExtensionStatus.ENDING)) {
                terminal.displayApproved("Extension '&b%s&7' already stopped", extensionName);
                return;
            }

            extensionFactory.stop(extension.get());
            CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(ExtensionUnregisterEvent.class, new JsonDocument().append("extensionName", extensionName));
            terminal.displayApproved("Extension '&b%s&7' is now &cstopped", extensionName);

            return;
        }

        this.sendUsage();

    }

    /**
     * Polls {@code extension}'s status until it leaves {@link ExtensionStatus#LOADING} or {@link
     * #START_REPORT_TIMEOUT_MILLIS} elapses - {@link ExtensionFactory#start} returns as soon as
     * the worker thread exists, so the status right after it is not yet the outcome.
     *
     * @param extension the extension just started
     * @return the status it settled on, or its current status if it did not settle in time
     */
    private ExtensionStatus awaitSettledStatus(@NonNull final Extension extension) {
        final long deadline = System.currentTimeMillis() + START_REPORT_TIMEOUT_MILLIS;
        ExtensionStatus status = extension.getExtensionProperties().getExtensionStatus();
        while (status == ExtensionStatus.LOADING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(START_REPORT_POLL_MILLIS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            status = extension.getExtensionProperties().getExtensionStatus();
        }
        return status;
    }

    /**
     * Renders an {@link ExtensionStatus} as a colored, human-readable label for terminal output.
     *
     * @param extensionStatus the status to render
     * @return the {@code &}-color-coded label for {@code extensionStatus}
     */
    private static String extensionStatusOf(@NonNull ExtensionStatus extensionStatus) {
        return switch (extensionStatus) {
            case LOADING -> "&eLoading&7";
            case RUNNING -> "&aRunning&7";
            case ERROR -> "&c&lError&7";
            case ENDING -> "&cENDING&7";
        };
    }

}
