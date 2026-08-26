package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.command.Command;
import de.lino.cloud.api.terminal.command.CommandService;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class ExtensionCommand implements Command {

    @Override
    public @NotNull String name() {
        return "extensions";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("extension", "ext");
    }

    @Override
    public @NotNull String description() {
        return "List all registered extensions";
    }

    @Override
    public void execute(@NotNull String[] args) {

        final Terminal terminal = this.terminal();
        final ExtensionFactory extensionFactory = CloudAPI.getInstance().getExtensionFactory();

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
