package de.lino.cloud.api.event;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

/**
 * Fired once per extension {@code CloudBootstrap#startExtensionsBootstrapScheduler} registers -
 * in registration order, before {@code ExtensionFactory#startAll} runs - purely to log a
 * confirmation line per extension via {@link CloudAPI#getLogger()}. Carries no other side effect.
 */
public class ExtensionRegisterEvent extends Event {

    /**
     * Looks the just-registered extension back up by name and logs its name/version.
     *
     * @param properties the payload, carrying the registered extension's {@code "extensionName"}
     * @throws java.util.NoSuchElementException if no extension is registered under that name
     */
    @Override
    public void handle(@NonNull JsonDocument properties) {

        final Extension extension = this.cloudAPI().getExtensionFactory().findByName(properties.getString("extensionName")).orElseThrow();
        final ExtensionProperties extensionProperties = extension.getExtensionProperties();
        this.cloudAPI().getTerminal().display(String.format("Extension '&c%s&7' (v%s) successfully loaded.", extensionProperties.getExtensionName(), extensionProperties.getExtensionVersion()));

    }

}
