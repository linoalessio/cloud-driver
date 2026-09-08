package de.lino.cloud.extensions.versioning;

import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;

import java.time.Duration;
import java.util.logging.Level;

/**
 * Versioning - publishes a {@link
 * DefaultFileVersioningService} into {@code IServiceContainer#setFileVersioningService} and
 * starts a {@link FileVersionPurgeScheduler} enforcing the configured retention policy. See
 * {@code FileVersioningService}'s own Javadoc for the full design, including why {@code
 * de.lino.cloud.auth.CloudUserService} gained a new {@code replaceFileContent} primitive - this
 * app had no content-overwrite operation for versions to attach to before.
 *
 * <p>Built as an in-process extension, not a genuinely standalone deployable service, for the
 * same reason {@code cloud-driver-extensions-thumbnails} was.
 */
public class CloudVersioningExtension extends Extension {

    /** How often {@link #purgeScheduler} sweeps for versions past either retention cap - daily, matching {@code TrashPurgeScheduler}'s own cadence choice for a similarly slow-moving retention window. */
    private static final Duration PURGE_TICK_PERIOD = Duration.ofDays(1);

    private FileVersionPurgeScheduler purgeScheduler;

    /**
     * Publishes a {@link DefaultFileVersioningService} and builds+starts {@link
     * #purgeScheduler} with this deployment's configured (or default) retention window - see
     * {@link FileVersionPurgeScheduler}'s own Javadoc for why this one, unlike {@code
     * TrashPurgeScheduler}, starts automatically rather than waiting for an operator to opt in.
     */
    @Override
    public void onLoading() {

        final DataFactory dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        final FileFactory fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();

        this.cloudDriver().getServiceContainer().setFileVersioningService(
                new DefaultFileVersioningService(dataFactory, fileFactory, this.getLogger()));

        this.purgeScheduler = FileVersionPurgeScheduler.withConfiguredRetention(dataFactory, fileFactory, this.getLogger());
        this.purgeScheduler.start(PURGE_TICK_PERIOD);

    }

    /** Prints a confirmation once {@link #onLoading()} has published the versioning service and started the purge scheduler. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&dFile versioning &bready &7- capturing a version on every content replacement");
    }

    /** Shuts {@link #purgeScheduler} down, if it was ever built. */
    @Override
    public void onEnding() {
        if (this.purgeScheduler != null) this.purgeScheduler.shutdown();
    }

    /**
     * Shuts {@link #purgeScheduler} down, if it was ever built, and logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        if (this.purgeScheduler != null) this.purgeScheduler.shutdown();
        this.getLogger().log(Level.SEVERE, "An error occurred while running the versioning extension.", reason);
    }

}
