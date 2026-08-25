package de.lino.cloud.bootstrap.event;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.event.Event;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.extension.info.ExtensionProperties;
import de.lino.cloud.api.utility.Asserts;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

public class ExtensionRegisterEvent extends Event {

    @Override
    public void handle(@NonNull JsonDocument properties) {

        Asserts.assertNotNull(CloudAPI.getInstance());

        final Extension extension = CloudAPI.getInstance().getExtensionFactory().findByName(properties.getString("extensionName")).orElseThrow();
        final ExtensionProperties extensionProperties = extension.getExtensionProperties();
        CloudAPI.getInstance().getLogger().info(String.format("Extension '%s' (v%s) successfully loaded.", extensionProperties.getExtensionName(), extensionProperties.getExtensionVersion()));

    }

}
