package de.lino.cloud.bootstrap;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.ExtensionFactory;

/**
 * No-op placeholder representing {@code cloud-driver-bootstrap} itself as an {@link Extension},
 * so other extensions can declare a dependency on the host bootstrap under its {@code
 * "cloud-driver-bootstrap"} name via {@link ExtensionFactory}'s ordinary dependency-ordering
 * mechanism.
 */
public class CloudBootstrapExtension extends Extension {

    /** No-op. */
    @Override
    public void onLoading() {

    }

    /**
     * No-op.
     *
     * @param args unused
     */
    @Override
    public void onRunning(String[] args) {

    }

    /** No-op. */
    @Override
    public void onEnding() {

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
