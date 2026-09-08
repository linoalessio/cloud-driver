package de.lino.cloud.extensions.thumbnails;

import de.lino.cloud.api.event.database.FileChangeListener;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.thumbnail.ThumbnailSize;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Thumbnail/Preview Service - generates a preview
 * thumbnail for a newly uploaded JPEG/PNG image or PDF, so a client can render a grid view
 * without downloading a full file (see {@code
 * ThumbnailService}'s own Javadoc for the client-facing contract).
 *
 * <p><b>Built as an in-process extension, not a genuinely standalone deployable service</b> -
 * matching this repo's actual
 * single-server deployment reality and the requirement that every optional capability degrade
 * gracefully when disabled rather than being required infrastructure.
 *
 * <p><b>Reacts to uploads via {@link FileChangeListener}, not a synchronous hook on the upload
 * path</b> - registered against {@link de.lino.cloud.api.event.database.FileChangeListenerRegistry}
 * (itself fed by {@link de.lino.cloud.api.event.database.DatabaseWatchEvent}, driven by
 * {@code cloud-driver-watcher}'s Postgres {@code LISTEN}/{@code NOTIFY}). Since {@code
 * cloud-driver-watcher} is only a soft dependency (not declared in this module's own {@code
 * extension.json}, so this extension still comes up without it), thumbnail generation simply
 * never fires on a deployment that doesn't run the watcher extension - a graceful no-op, not a
 * failure, matching every other optional extension's degradation contract.
 *
 * <p>Only reacts to {@code "INSERT"} notifications (a brand-new file) - an {@code "UPDATE"} (a
 * rename, a trash/restore, a move to S3, ...) never changes a file's actual bytes, so there is
 * nothing to regenerate.
 *
 * <p>Generation itself runs on {@link #executor} - a small, bounded, daemon-threaded pool -
 * never on the Postgres notification thread this is ultimately triggered from. Deliberately kept
 * simple for v1 - start in-process with a bounded executor, document how to swap in a real queue
 * later: a follow-up wanting to
 * survive a process restart mid-generation, or to distribute generation across more than one
 * process, would replace this executor with a real persisted queue (e.g. a new {@code
 * PendingThumbnailCache}, mirroring {@code PendingUploadCache}'s own shape) - not attempted here.
 */
public class CloudThumbnailsExtension extends Extension {

    /** The only Postgres trigger operation this extension reacts to - a file's bytes never change on an {@code "UPDATE"}. */
    private static final String INSERT_OPERATION = "INSERT";

    /** Bounded pool size for {@link #executor} - small and fixed, deliberately not over-engineered for v1. */
    private static final int THUMBNAIL_EXECUTOR_THREADS = 2;

    /** How long {@link #onEnding()}/{@link #onException(RuntimeException)} wait for an in-flight generation to finish before forcing shutdown. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    private static final List<ThumbnailGenerator> GENERATORS = List.of(new ImageThumbnailGenerator(), new PdfThumbnailGenerator());

    private DataFactory dataFactory;
    private FileFactory fileFactory;
    private ExecutorService executor;
    private FileChangeListener listener;

    /**
     * Resolves {@link #dataFactory}/{@link #fileFactory}, builds {@link #executor}, publishes a
     * {@link DefaultThumbnailService} into {@code IServiceContainer#setThumbnailService}, and
     * registers {@link #listener} against {@code FileChangeListenerRegistry}.
     */
    @Override
    public void onLoading() {

        this.dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        this.fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();
        this.executor = Executors.newFixedThreadPool(THUMBNAIL_EXECUTOR_THREADS, daemonThreadFactory());

        this.cloudDriver().getServiceContainer().setThumbnailService(
                new DefaultThumbnailService(this.dataFactory, this.fileFactory, this.getLogger()));

        this.listener = (storedFileId, operation) -> {
            if (!INSERT_OPERATION.equalsIgnoreCase(operation)) return;
            this.executor.submit(() -> this.generateThumbnail(storedFileId));
        };
        this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry().register(this.listener);

    }

    /** Prints a confirmation once {@link #onLoading()} has registered {@link #listener}. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&dThumbnail generation &bready &7- watching for new image/PDF uploads");
    }

    /** Unregisters {@link #listener} and shuts {@link #executor} down. */
    @Override
    public void onEnding() {
        this.shutdown();
    }

    /**
     * Unregisters {@link #listener} and shuts {@link #executor} down, then logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        this.shutdown();
        this.getLogger().log(Level.SEVERE, "An error occurred while running the thumbnails extension.", reason);
    }

    private void shutdown() {
        if (this.listener != null) {
            this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry().unregister(this.listener);
        }
        if (this.executor == null) return;
        this.executor.shutdown();
        try {
            if (!this.executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                this.executor.shutdownNow();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.executor.shutdownNow();
        }
    }

    /**
     * Generates and persists a thumbnail for {@code storedFileId}, if a supporting {@link
     * ThumbnailGenerator} exists for its content type and no thumbnail already exists. Every
     * failure (a fetch/persist error, an undecodable/corrupt file, an unsupported content type) is
     * logged and swallowed - a thumbnail is a best-effort convenience, never something a failure
     * here should surface anywhere the original upload would see.
     *
     * @param storedFileId the newly-inserted {@link StoredFile}'s id
     */
    private void generateThumbnail(final String storedFileId) {
        try {

            final String key = FileThumbnail.compositeKey(storedFileId, ThumbnailSize.SMALL.name());
            if (this.dataFactory.findById(key, FileThumbnail.class).isPresent()) return;

            final Optional<StoredFile> source = this.fileFactory.findById(storedFileId);
            if (source.isEmpty()) return;
            final StoredFile file = source.get();

            final Optional<ThumbnailGenerator> generator = GENERATORS.stream()
                    .filter(candidate -> candidate.supports(file.contentType()))
                    .findFirst();
            if (generator.isEmpty()) return;

            final byte[] thumbnailBytes = generator.get().generate(file.content(), ThumbnailSize.SMALL.maxDimensionPixels());

            final String thumbnailFileId = UUID.randomUUID().toString();
            this.fileFactory.upload(new StoredFile(thumbnailFileId, thumbnailFileName(file.fileName()), thumbnailBytes));
            this.dataFactory.register(new FileThumbnail(storedFileId, ThumbnailSize.SMALL.name(), thumbnailFileId, System.currentTimeMillis()));

        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException | IOException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.generateThumbnail: failed for file '" + storedFileId + "'", e);
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.generateThumbnail: unexpected failure for file '" + storedFileId + "'", e);
        }
    }

    /** @return {@code "thumb_<sourceFileName>.jpg"} - the generated thumbnail's own {@link StoredFile#fileName()} */
    private static String thumbnailFileName(final String sourceFileName) {
        return "thumb_" + sourceFileName + ".jpg";
    }

    /** @return a {@link ThreadFactory} producing named, daemon threads for {@link #executor} - never keeps the JVM alive on its own. */
    private static ThreadFactory daemonThreadFactory() {
        final AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            final Thread thread = new Thread(runnable, "thumbnail-generator-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

}
