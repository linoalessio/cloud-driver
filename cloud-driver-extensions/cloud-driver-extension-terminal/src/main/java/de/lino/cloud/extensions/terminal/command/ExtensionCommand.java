package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.extension.ExtensionRegisterEvent;
import de.lino.cloud.api.event.extension.ExtensionUnregisterEvent;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.extension.info.ExtensionStatus;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/** Lists every registered {@link Extension} and its status. */
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

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Get a list of information about the extensions";
    }

    /**
     * Prints every registered extension's name, version, status, and description.
     *
     * @param args unused
     */
    @Override
    public void execute(@NotNull String[] args) {

        if (args.length == 0) {
            this.sendHelp();
            return;
        }

        final Terminal terminal = this.terminal();
        final ExtensionFactory extensionFactory = CloudDriver.getInstance().getExtensionFactory();

        if (args[0].equalsIgnoreCase("list")) {

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

        if (args[0].equalsIgnoreCase("info")) {

            final String extensionName = args[1];
            final Optional<Extension> extension = extensionFactory.findByName(extensionName);

            if (extension.isEmpty()) {
                terminal.displayApproved("Extension '&b%s&7' not found", extensionName);
                return;
            }

            final ExtensionProperties properties = extension.get().getExtensionProperties();

            terminal.emptyLine();
            terminal.displayApproved("Information about '&b&l%s&7': ", properties.getExtensionName());
            terminal.displayApproved("Version: &f%s", properties.getExtensionVersion());
            terminal.displayApproved("Description: &7%s", properties.getDescription().isEmpty() ? "EMPTY" : properties.getDescription());
            terminal.displayApproved("Status: %s", extensionStatusOf(properties.getExtensionStatus()));
            terminal.displayApproved("Authors: &e%s", String.join("&7, &e", properties.getAuthors()));
            terminal.displayApproved("Dependencies: &c%s", properties.getDependencies().isEmpty() ? "EMPTY" : String.join("&7, &c", properties.getDependencies()));
            terminal.emptyLine();

            return;
        }

        if (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("stop")) {

            final String action = args[0];
            final String extensionName = args[1];
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
                    CloudDriver.getInstance().getEventFactory().dispatch(ExtensionRegisterEvent.class, new JsonDocument().append("extensionName", extensionName));
                }
                case "stop" -> {

                    if (extension.get().getExtensionProperties().getExtensionStatus().equals(ExtensionStatus.ENDING)) {
                        terminal.displayApproved("Extension '&b%s&7' already stopped", extensionName);
                        return;
                    }

                    extensionFactory.stop(extension.get());
                    CloudDriver.getInstance().getEventFactory().dispatch(ExtensionUnregisterEvent.class, new JsonDocument().append("extensionName", extensionName));
                }
                default -> {
                    this.sendHelp();
                }
            }

            return;
        }

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
