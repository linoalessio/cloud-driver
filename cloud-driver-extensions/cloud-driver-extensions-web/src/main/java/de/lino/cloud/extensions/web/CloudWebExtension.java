package de.lino.cloud.extensions.web;

import de.lino.cloud.api.extension.Extension;

/**
 * Placeholder extension for {@code cloud-driver-extensions-web}, which otherwise has no source
 * of its own yet - every lifecycle hook here is either empty or a diagnostic {@code
 * System.out.println}. Not wired into any real REST/web behavior - see {@code RestFactory}
 * ({@code cloud-driver-api}/{@code cloud-driver-plugin}) for where that actually lives today.
 */
public class CloudWebExtension extends Extension {
    @Override
    public void onLoading() {
        System.out.println("CloudWebExtension.onLoading");
    }

    @Override
    public void onEnding() {

    }

    @Override
    public void onRunning(String[] args) {
        System.out.println("CloudWebExtension.onRunning");
    }

    @Override
    public void onException(RuntimeException reason) {

    }
}
