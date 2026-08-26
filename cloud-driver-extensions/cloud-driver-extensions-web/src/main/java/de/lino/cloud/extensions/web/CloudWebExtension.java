package de.lino.cloud.extensions.web;

import de.lino.cloud.api.extension.Extension;

/**
 * Placeholder extension for {@code cloud-driver-extensions-web}, which otherwise has no source
 * of its own yet. Not wired into any real REST/web behavior - see {@code RestFactory}
 * ({@code cloud-driver-api}/{@code cloud-driver-plugin}) for where that actually lives today.
 */
public class CloudWebExtension extends Extension {

    /** Prints a diagnostic message; no real loading behavior yet. */
    @Override
    public void onLoading() {
        System.out.println("CloudWebExtension.onLoading");
    }

    /** No-op. */
    @Override
    public void onEnding() {

    }

    /**
     * Prints a diagnostic message; no real running behavior yet.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {
        System.out.println("CloudWebExtension.onRunning");
    }

    /**
     * No-op.
     *
     * @param reason unused
     */
    @Override
    public void onException(RuntimeException reason) {

    }
}
