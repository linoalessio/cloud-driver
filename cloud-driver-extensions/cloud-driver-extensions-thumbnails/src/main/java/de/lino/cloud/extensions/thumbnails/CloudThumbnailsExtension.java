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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * <p><b>A generated thumbnail is itself a {@link StoredFile} INSERT, so it fires this very
 * listener back at itself</b> - and a thumbnail is a JPEG, which {@link ImageThumbnailGenerator}
 * happily supports. Without a guard this is an unbounded feedback loop (thumbnail of a thumbnail
 * of a thumbnail, forever): a real incident (2026-09-09) found ~21,600 chained
 * {@code thumb_thumb_..._x.jpg.jpg...} files totalling ~3 GB, enough to OOM-crash boot. Two
 * defenses exist: {@link #generateThumbnail} skips any file that is some {@link FileThumbnail}'s
 * target (and the linking row is registered <i>before</i> the thumbnail file is uploaded, so the
 * guard row is already visible when the thumbnail's own INSERT notification arrives), and
 * {@link #pruneThumbnailChains()} sweeps previously-accumulated chain garbage on every start.
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

        this.executor.submit(this::pruneThumbnailChains);

        this.listener = (storedFileId, operation) -> {
            if (!INSERT_OPERATION.equalsIgnoreCase(operation)) return;
            this.executor.submit(() -> this.generateThumbnail(storedFileId));
        };
        this.cloudDriver().getFactoryContainer().getFileChangeListenerRegistry().register(this.listener);

    }

    /** Prints a confirmation once {@link #onLoading()} has registered {@link #listener}. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&3Thumbnail generation &bready &7- watching for new image/PDF uploads");
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
            if (this.isThumbnailFile(storedFileId)) return;

            final Optional<StoredFile> source = this.fileFactory.findById(storedFileId);
            if (source.isEmpty()) return;
            final StoredFile file = source.get();

            final Optional<ThumbnailGenerator> generator = GENERATORS.stream()
                    .filter(candidate -> candidate.supports(file.contentType()))
                    .findFirst();
            if (generator.isEmpty()) return;

            final byte[] thumbnailBytes = generator.get().generate(file.content(), ThumbnailSize.SMALL.maxDimensionPixels());

            // The linking row goes in BEFORE the thumbnail's own bytes: the upload below fires
            // this extension's own listener for the thumbnail file, and isThumbnailFile only
            // stops that recursion if the row pointing at the thumbnail is already visible.
            final String thumbnailFileId = UUID.randomUUID().toString();
            this.dataFactory.register(new FileThumbnail(storedFileId, ThumbnailSize.SMALL.name(), thumbnailFileId, System.currentTimeMillis()));

            boolean uploaded = false;
            try {
                this.fileFactory.upload(new StoredFile(thumbnailFileId, thumbnailFileName(file.fileName()), thumbnailBytes));
                uploaded = true;
            } finally {
                if (!uploaded) this.deleteThumbnailRowQuietly(key);
            }

        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException | IOException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.generateThumbnail: failed for file '" + storedFileId + "'", e);
        } catch (final RuntimeException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.generateThumbnail: unexpected failure for file '" + storedFileId + "'", e);
        }
    }

    /**
     * Whether {@code storedFileId} is itself some {@link FileThumbnail}'s generated thumbnail -
     * the recursion guard that keeps a thumbnail's own INSERT notification from spawning a
     * thumbnail-of-a-thumbnail chain (see the class Javadoc for the incident this prevents).
     * A linear scan over every {@link FileThumbnail} row, which is fine at this table's real
     * size (a handful of rows - one per previewable file) but would need a reverse-lookup key
     * if that ever changed.
     *
     * @param storedFileId the file id the INSERT notification was for
     * @return {@code true} if a {@link FileThumbnail} row names {@code storedFileId} as its
     *         {@link FileThumbnail#getThumbnailFileId() thumbnail target}
     * @throws DatabaseClientException if the {@link FileThumbnail} rows can't be read
     * @throws KeyWrapException if a row's key can't be unwrapped
     * @throws AuthenticationFailedException if a row fails authenticated decryption
     */
    private boolean isThumbnailFile(final String storedFileId)
            throws DatabaseClientException, KeyWrapException, AuthenticationFailedException {
        return this.dataFactory.getEntities(FileThumbnail.class).stream()
                .anyMatch(row -> storedFileId.equals(row.getThumbnailFileId()));
    }

    /**
     * Best-effort rollback of a just-registered {@link FileThumbnail} row after its thumbnail
     * file failed to upload - a failure here is only logged, since the orphaned row is exactly
     * what {@link #pruneThumbnailChains()} cleans up on the next start anyway.
     *
     * @param compositeKey the row's primary key, from {@link FileThumbnail#compositeKey}
     */
    private void deleteThumbnailRowQuietly(final String compositeKey) {
        try {
            this.dataFactory.delete(compositeKey, FileThumbnail.class);
        } catch (final DatabaseClientException | RuntimeException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.deleteThumbnailRowQuietly: could not roll back row '" + compositeKey + "'", e);
        }
    }

    /**
     * Deletes every {@link FileThumbnail} row (and the thumbnail {@link StoredFile} it points
     * at) whose source file is itself some thumbnail's target - i.e. every link of a
     * thumbnail-of-a-thumbnail chain beyond the first - plus rows whose source file no longer
     * exists at all (the source was deleted, or the row was orphaned by a crash between
     * register and upload). Legitimate rows (source is a real, existing, non-thumbnail file)
     * are untouched. Runs on {@link #executor} at every start, so the accumulated garbage of
     * the pre-guard feedback-loop incident - and any future orphans - are swept without
     * operator involvement; per-row failures are counted and logged, never fatal to the sweep.
     */
    private void pruneThumbnailChains() {
        try {

            final List<FileThumbnail> rows = this.dataFactory.getEntities(FileThumbnail.class);
            final Set<String> thumbnailFileIds = new HashSet<>();
            for (final FileThumbnail row : rows) thumbnailFileIds.add(row.getThumbnailFileId());

            int prunedRows = 0;
            int prunedFiles = 0;
            int failures = 0;

            for (final FileThumbnail row : rows) {
                try {

                    final boolean sourceIsThumbnail = thumbnailFileIds.contains(row.getSourceFileId());
                    if (!sourceIsThumbnail && this.fileFactory.findById(row.getSourceFileId()).isPresent()) continue;

                    this.dataFactory.delete(FileThumbnail.compositeKey(row.getSourceFileId(), row.getSize()), FileThumbnail.class);
                    prunedRows++;

                    try {
                        this.fileFactory.delete(row.getThumbnailFileId());
                        prunedFiles++;
                    } catch (final DatabaseClientException alreadyGone) {
                        // The row existed but its file didn't - nothing left to remove.
                    }

                } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException | RuntimeException e) {
                    failures++;
                    if (failures <= 3) this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.pruneThumbnailChains: failed pruning row for source '" + row.getSourceFileId() + "'", e);
                }
            }

            if (prunedRows > 0 || failures > 0) {
                this.getLogger().log(Level.INFO, "Thumbnail prune: removed " + prunedRows + " chained/orphaned linking row(s) and "
                        + prunedFiles + " thumbnail file(s), " + failures + " failure(s), " + (rows.size() - prunedRows) + " legitimate row(s) kept.");
            }

        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException e) {
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.pruneThumbnailChains: sweep failed", e);
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
