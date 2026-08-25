package de.lino.cloud.extensions.web;

import de.lino.cloud.api.extension.Extension;

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
