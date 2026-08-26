package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Lists every registered {@link de.lino.cloud.api.extension.Extension} and its status. */
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
        return "List all registered extensions";
    }

    /**
     * Prints every registered extension's name, version, status, and description.
     *
     * @param args unused
     */
    @Override
    public void execute(@NotNull String[] args) {

        final Terminal terminal = this.terminal();
        final ExtensionFactory extensionFactory = CloudDriver.getInstance().getExtensionFactory();

        terminal.emptyLine();
        terminal.displayApproved(String.format("Registered extensions (&b%s&7): ", extensionFactory.getExtensions().size()));
        extensionFactory.getExtensions().forEach(extension -> {
            final ExtensionProperties properties = extension.getExtensionProperties();
            terminal.displayApproved(
                    String.format("- &c%s &7(v%s) (%s) | &7%s"
                            , properties.getExtensionName()
                            , properties.getExtensionVersion()
                            , properties.getExtensionStatus()
                            , properties.getDescription()
                    )
            );
        });
        terminal.emptyLine();

    }

}
