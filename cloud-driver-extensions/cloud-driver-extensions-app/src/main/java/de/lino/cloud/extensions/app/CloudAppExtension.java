package de.lino.cloud.extensions.app;

import de.lino.cloud.api.extension.Extension;

/**
 * Placeholder extension for {@code cloud-driver-extensions-app}, which otherwise has no source
 * of its own yet. Not wired into any real desktop-app behavior.
 */
public class CloudAppExtension extends Extension {

    private CloudAppExtension() {
    }

    /** Prints a diagnostic message; no real loading behavior yet. */
    @Override
    public void onLoading() {
        System.out.println("CloudAppExtension.onLoading()");
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

        System.out.println("CloudAppExtension.onRunning()");
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
