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

    /**
     * Dispatches to one of {@code list}/{@code info}/{@code start}/{@code stop} based on {@code
     * arguments}' first token, printing a usage message if it is empty or unrecognized: {@code
     * list} prints every registered extension's name, version, status, and description; {@code
     * info <name>} prints one extension's full detail; {@code start <name>}/{@code stop <name>}
     * drive that extension through {@link ExtensionFactory#start}/{@link ExtensionFactory#stop}
     * and dispatch the matching {@link ExtensionRegisterEvent}/{@link ExtensionUnregisterEvent}
     * (the host bootstrap extension itself, {@code "cloud-driver-bootstrap"}, is excluded from
     * {@code start}/{@code stop} to avoid tearing down the process that hosts this very command).
     *
     * @param arguments the sub-command and its own arguments, split on whitespace
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        if (arguments.length() == 0) {
            this.sendHelp();
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

        if (arguments.hasCommand(0, "info")) {

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

        if (arguments.hasCommand(0, "start") || arguments.hasCommand(0, "stop")) {

            final String action = arguments.command(0);
            final String extensionName = arguments.command(1);
            final Optional<Extension> extension = extensionFactory.findByName(extensionName);

            if (extension.isEmpty()) {
                terminal.displayApproved("Extension '&b%s&7' not found", extensionName);
                return;
            }

            if (extensionName.equalsIgnoreCase("cloud-driver-bootstrap")) {
                terminal.displayApproved("Extension '&b%s&7' cannot be modified.", extensionName);
                return;
            }

            switch (action) {
                case "start" -> {
                    extensionFactory.stop(extension.get());
                    extensionFactory.start(extension.get(), new String[0]);
                    CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(ExtensionRegisterEvent.class, new JsonDocument().append("extensionName", extensionName));
                }
                case "stop" -> {

                    if (extension.get().getExtensionProperties().getExtensionStatus().equals(ExtensionStatus.ENDING)) {
                        terminal.displayApproved("Extension '&b%s&7' already stopped", extensionName);
                        return;
                    }

                    extensionFactory.stop(extension.get());
                    CloudDriver.getInstance().getFactoryContainer().getEventFactory().dispatch(ExtensionUnregisterEvent.class, new JsonDocument().append("extensionName", extensionName));
                }
                default -> {
                    this.sendHelp();
                }
            }

            return;
        }

        this.sendHelp();

    }

    private static String extensionStatusOf(@NonNull ExtensionStatus extensionStatus) {
        return switch (extensionStatus) {
            case LOADING -> "&eLoading&7";
            case RUNNING -> "&aRunning&7";
            case ERROR -> "&c&lError&7";
            case ENDING -> "&cENDING&7";
        };
    }

    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&fextension list");
        terminal.displayApproved("&fextension <info> <name>");
        terminal.displayApproved("&fextension <start:stop> <name>");
    }

}
