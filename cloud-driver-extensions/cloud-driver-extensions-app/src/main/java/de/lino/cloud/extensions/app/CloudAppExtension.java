package de.lino.cloud.extensions.app;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;

/**
 * Placeholder extension for {@code cloud-driver-extensions-app}, which otherwise has no source
 * of its own yet - every lifecycle hook here is either empty or a diagnostic {@code
 * System.out.println}. Not wired into any real desktop-app behavior.
 */
public class CloudAppExtension extends Extension {

    private CloudAppExtension() {
    }

    @Override
    public void onLoading() {
        System.out.println("CloudAppExtension.onLoading()");
    }

    @Override
    public void onEnding() {

    }

    @Override
    public void onRunning(String[] args) {

        System.out.println("CloudAppExtension.onRunning()");
    }

    @Override
    public void onException(RuntimeException reason) {

    }

}
