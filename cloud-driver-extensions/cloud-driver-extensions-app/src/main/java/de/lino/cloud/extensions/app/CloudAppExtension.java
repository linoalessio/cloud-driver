package de.lino.cloud.extensions.app;

import de.lino.cloud.api.CloudAPI;
import de.lino.cloud.api.extension.Extension;

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
