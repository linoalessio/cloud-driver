package de.lino.cloud.bootstrap;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.ExtensionFactory;

/**
 * A no-op placeholder representing {@code cloud-driver-bootstrap} itself as an {@link Extension}
 * - it does nothing in any lifecycle hook. Its sole purpose is to exist under the {@code
 * "cloud-driver-bootstrap"} name (see its {@code extension.json}) so other extensions can declare
 * a dependency on the host bootstrap being present/started, the same way they would declare a
 * dependency on any other extension, via {@link ExtensionFactory}'s ordinary dependency-ordering
 * mechanism - e.g. {@code cloud-driver-extensions-watcher}'s {@code CloudWatcherExtension}.
 * Discovered and registered the same way any other extension jar is, by {@code
 * ExtensionFolderScanner} scanning {@code user.dir} (see {@code CloudBootstrap#
 * startExtensionsBootstrapScheduler}) - nothing in this repo constructs it directly.
 */
public class CloudBootstrapExtension extends Extension {

    @Override
    public void onLoading() {

    }

    @Override
    public void onRunning(String[] args) {

    }

    @Override
    public void onEnding() {

    }

    @Override
    public void onException(RuntimeException reason) {

    }

}
