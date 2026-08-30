package de.lino.cloud.api.event.extension;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

/**
 * Fired once per extension registered, purely to print a confirmation line to the terminal.
 * Dispatched from {@code CloudBootstrap#startExtensionsBootstrapScheduler} for every extension
 * registered at bootstrap, and from {@code ExtensionCommand}'s {@code start} subcommand for one
 * registered interactively from the terminal.
 */
public class ExtensionRegisterEvent extends Event {

    /**
     * Looks the just-registered extension back up by name and prints its name/version via
     * {@link CloudDriver#getTerminal()}'s {@code displayApproved}.
     *
     * @param properties the payload, carrying the registered extension's {@code "extensionName"}
     * @throws java.util.NoSuchElementException if no extension is registered under that name
     */
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final Extension extension = this.cloudDriver().getFactoryContainer().getExtensionFactory().findByName(properties.getString("extensionName")).orElseThrow();
        final ExtensionProperties extensionProperties = extension.getExtensionProperties();
        this.cloudDriver().getTerminal().displayApproved("&7Extension '&b%s&7' (v%s) successfully &aloaded&7.", extensionProperties.getExtensionName(), extensionProperties.getExtensionVersion());

    }

}
