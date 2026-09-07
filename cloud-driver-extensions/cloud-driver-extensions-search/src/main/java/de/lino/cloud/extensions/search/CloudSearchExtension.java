package de.lino.cloud.extensions.search;

import de.lino.cloud.api.extension.Extension;

/**
 * Section 5 (Search/Indexing, {@code architecture/MICRO.md}) - publishes an {@link
 * InMemorySearchIndexService} into {@code IServiceContainer#setSearchIndexService}. See {@code
 * SearchIndexService}'s own Javadoc for the full design, in particular why indexing is driven
 * synchronously from {@code de.lino.cloud.auth.CloudUserService}'s own mutation methods rather
 * than the async {@code FileChangeListener} mechanism section 1 introduced.
 *
 * <p>Built as an in-process extension, not a genuinely standalone deployable service, for the
 * same reason {@code cloud-driver-extensions-thumbnails}/{@code -versioning} were - see {@code
 * architecture/MICRO.md}'s "Reconciliation Notes". No background thread of its own - unlike {@code
 * CloudVersioningExtension}'s purge scheduler, there is nothing here to tick: {@link
 * InMemorySearchIndexService} is a plain in-memory structure, updated only in direct response to a
 * real file mutation.
 */
public class CloudSearchExtension extends Extension {

    /** Publishes a fresh {@link InMemorySearchIndexService}. */
    @Override
    public void onLoading() {
        this.cloudDriver().getServiceContainer().setSearchIndexService(new InMemorySearchIndexService());
    }

    /** Prints a confirmation once {@link #onLoading()} has published the search index. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&dSearch index &bready &7- indexing filenames and text-file content on upload");
    }

    /** Nothing to shut down - see this class's own Javadoc. */
    @Override
    public void onEnding() {
        // No background resources to release.
    }

    /**
     * Nothing to shut down (see {@link #onEnding()}) - only logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        this.getLogger().log(java.util.logging.Level.SEVERE, "An error occurred while running the search extension.", reason);
    }

}
