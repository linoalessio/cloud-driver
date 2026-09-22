package de.lino.cloud.extensions.thumbnails;

import de.lino.cloud.api.event.database.FileChangeListener;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.ScanStatus;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.thumbnail.ThumbnailSize;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /**
     * The Postgres trigger operations that can mean a file now wants a preview it does not have.
     *
     * <p>An {@code INSERT} is a new file. An {@code UPDATE} covers the two cases an insert cannot:
     * a content change, which deletes the stale preview so a fresh one is wanted, and the scan
     * verdict landing, which is what first makes a file eligible to be decoded at all. Reacting to
     * both is cheap because {@link #generateThumbnail} returns on metadata alone whenever a
     * preview already exists, the file is not clean, or it is too large - so a rename or a move
     * costs two indexed reads and nothing else.
     */
    private static final Set<String> THUMBNAIL_TRIGGERING_OPERATIONS = Set.of("INSERT", "UPDATE");

    /** Bounded pool size for {@link #executor} - small and fixed, deliberately not over-engineered for v1. */
    private static final int THUMBNAIL_EXECUTOR_THREADS = 2;

    /** {@code configuration.json} key for the largest source file a preview is generated from - defaults to {@link #DEFAULT_MAX_SOURCE_BYTES} if unset. */
    private static final String MAX_SOURCE_BYTES_CONFIG_KEY = "thumbnail-max-source-bytes";

    /**
     * Default largest source file this extension will decode, in bytes.
     *
     * <p>A thumbnail source has no reason to be large, and every byte above this is risk without
     * benefit: the decoders allocate a full raster before producing anything, so an unbounded
     * source is an unbounded allocation on a worker with no request behind it.
     */
    private static final long DEFAULT_MAX_SOURCE_BYTES = 64L * 1024 * 1024;

    /** {@code configuration.json} key for the shared raster budget both generators refuse to exceed - defaults to {@link #DEFAULT_MAX_DECODED_PIXELS} if unset. */
    private static final String MAX_DECODED_PIXELS_CONFIG_KEY = "thumbnail-max-decoded-pixels";

    /**
     * Default raster budget, in pixels, shared by the image decoder and the PDF renderer.
     *
     * <p>Checked against the image's own header and against the PDF's crop box <em>before</em> any
     * raster is allocated, which is what defuses a small file declaring an enormous image. Far
     * beyond any real photograph or page a preview is wanted for, and far below what a
     * deliberately crafted header can claim.
     */
    private static final long DEFAULT_MAX_DECODED_PIXELS = 50_000_000L;

    /** {@code configuration.json} key for how long a single decode/render may run - defaults to {@link #DEFAULT_RENDER_TIMEOUT_SECONDS} if unset. */
    private static final String RENDER_TIMEOUT_SECONDS_CONFIG_KEY = "thumbnail-render-timeout-seconds";

    /** Default ceiling on how long one decode/render may run before it is abandoned - a preview is best-effort, and a thread is not. */
    private static final long DEFAULT_RENDER_TIMEOUT_SECONDS = 20L;

    /** How long an idle {@link #renderExecutor} thread is kept before it is reclaimed. */
    private static final long RENDER_THREAD_KEEPALIVE_SECONDS = 60L;

    /** How long {@link #onEnding()}/{@link #onException(RuntimeException)} wait for an in-flight generation to finish before forcing shutdown. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5L;

    /**
     * The PDFBox/FontBox library loggers {@link #onLoading()} raises to {@link Level#SEVERE}.
     * PDFBox's lenient parser emits one {@code java.util.logging} WARNING per damaged object it
     * recovers from ("Invalid dictionary, found: ... but expected: '/'", "The end of the stream
     * is out of range", ...) - a single corrupt-but-renderable uploaded PDF floods the operator
     * terminal with hundreds of such lines (observed 2026-09-10) while PDFBox still produces a
     * perfectly usable thumbnail. Raising these loggers to SEVERE drops only that internal
     * chatter; it deliberately does <b>not</b> hide real failures, because a PDF that genuinely
     * can't be rendered still surfaces as {@link #generateThumbnail}'s own single per-file
     * WARNING (the one signal that matters - the lesson of the 2026-09-01 silenced-Javalin
     * incident is "never suppress the only signal", not "never quiet a library").
     *
     * <p>Held in a {@code static final} field, not set-and-forgotten inline: JUL only keeps
     * <em>weak</em> references to named loggers, so a level configured on an unreferenced logger
     * can be garbage-collected away mid-run, silently restoring the flood.
     */
    private static final List<java.util.logging.Logger> QUIETED_PDF_LIBRARY_LOGGERS = List.of(
            java.util.logging.Logger.getLogger("org.apache.pdfbox"),
            java.util.logging.Logger.getLogger("org.apache.fontbox"));

    private DataFactory dataFactory;
    private FileFactory fileFactory;
    private ExecutorService executor;

    /**
     * Runs the decode/render step only, separately from {@link #executor} on purpose: it is the
     * one step that can run arbitrarily long, so it is bounded and abandonable on its own, and the
     * notification-driven generation threads never block on it for longer than the configured
     * timeout. Its queue is deliberately a {@link SynchronousQueue} - with no queue, a submission
     * made while both render threads are busy is rejected immediately instead of piling up tasks
     * that each retain a source byte array behind a thread that may never come back.
     */
    private ExecutorService renderExecutor;

    /** The generators, in priority order, each carrying the configured raster budget. */
    private List<ThumbnailGenerator> generators;

    /** The configured largest source file a preview is generated from, in bytes. */
    private long maxSourceBytes;

    /** The configured ceiling on how long one decode/render may run, in seconds. */
    private long renderTimeoutSeconds;

    private FileChangeListener listener;

    /**
     * Quiets {@link #QUIETED_PDF_LIBRARY_LOGGERS} (see that field's Javadoc for why that is
     * safe), resolves {@link #dataFactory}/{@link #fileFactory}, reads this deployment's source,
     * raster and render-timeout budgets, builds {@link #executor}/{@link #renderExecutor} and the
     * generators those budgets configure, publishes a {@link DefaultThumbnailService} into {@code
     * IServiceContainer#setThumbnailService}, and registers {@link #listener} against {@code
     * FileChangeListenerRegistry}. The three budgets are read once, here - changing one needs a
     * restart.
     */
    @Override
    public void onLoading() {

        QUIETED_PDF_LIBRARY_LOGGERS.forEach(libraryLogger -> libraryLogger.setLevel(Level.SEVERE));

        this.dataFactory = this.cloudDriver().getFactoryContainer().getDataFactory();
        this.fileFactory = this.cloudDriver().getFactoryContainer().getFileFactory();

        final JsonDocument configuration = this.cloudDriver().getConfiguration();
        this.maxSourceBytes = configuration.contains(MAX_SOURCE_BYTES_CONFIG_KEY)
                ? configuration.getLong(MAX_SOURCE_BYTES_CONFIG_KEY) : DEFAULT_MAX_SOURCE_BYTES;
        this.renderTimeoutSeconds = configuration.contains(RENDER_TIMEOUT_SECONDS_CONFIG_KEY)
                ? configuration.getLong(RENDER_TIMEOUT_SECONDS_CONFIG_KEY) : DEFAULT_RENDER_TIMEOUT_SECONDS;
        final long maxDecodedPixels = configuration.contains(MAX_DECODED_PIXELS_CONFIG_KEY)
                ? configuration.getLong(MAX_DECODED_PIXELS_CONFIG_KEY) : DEFAULT_MAX_DECODED_PIXELS;
        this.generators = List.of(new ImageThumbnailGenerator(maxDecodedPixels), new PdfThumbnailGenerator(maxDecodedPixels));

        this.executor = Executors.newFixedThreadPool(THUMBNAIL_EXECUTOR_THREADS, daemonThreadFactory("thumbnail-generator-"));
        // No queue on purpose - see the field's own Javadoc: a render submitted while both threads
        // are busy is rejected outright rather than queued behind a decode that may never return.
        this.renderExecutor = new ThreadPoolExecutor(0, THUMBNAIL_EXECUTOR_THREADS, RENDER_THREAD_KEEPALIVE_SECONDS,
                TimeUnit.SECONDS, new SynchronousQueue<>(), daemonThreadFactory("thumbnail-render-"));

        this.cloudDriver().getServiceContainer().setThumbnailService(
                new DefaultThumbnailService(this.dataFactory, this.fileFactory, this.getLogger()));

        this.executor.submit(this::pruneThumbnailChains);

        this.listener = (storedFileId, operation) -> {
            if (operation == null || !THUMBNAIL_TRIGGERING_OPERATIONS.contains(operation.toUpperCase(Locale.ROOT))) return;
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
        // Withdraw before tearing anything down: a consumer that reads this facet while
        // the extension is stopping must see it absent, not stopped-but-present.
        this.cloudDriver().getServiceContainer().withdrawService(de.lino.cloud.api.thumbnail.ThumbnailService.class);
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
        this.shutdownQuietly(this.executor);
        this.shutdownQuietly(this.renderExecutor);
    }

    /**
     * Shuts {@code target} down, waiting up to {@link #SHUTDOWN_AWAIT_SECONDS} for an in-flight
     * task before forcing it. A {@code null} (this extension never finished loading) is a no-op.
     *
     * @param target the pool to shut down, or {@code null}
     */
    private void shutdownQuietly(final ExecutorService target) {
        if (target == null) return;
        target.shutdown();
        try {
            if (!target.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                target.shutdownNow();
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            target.shutdownNow();
        }
    }

    /**
     * Generates and persists a thumbnail for {@code storedFileId}, if a supporting {@link
     * ThumbnailGenerator} exists for its content type and no thumbnail already exists. Generation
     * is skipped when the file is larger than the configured source cap, when its scan verdict is
     * not clean, and when no render slot is free. Every failure (a fetch/persist error, an
     * undecodable/corrupt file, an unsupported content type) is logged and swallowed - a thumbnail
     * is a best-effort convenience, never something a failure here should surface anywhere the
     * original upload would see.
     *
     * @param storedFileId the newly-inserted {@link StoredFile}'s id
     */
    private void generateThumbnail(final String storedFileId) {
        try {

            final String key = FileThumbnail.compositeKey(storedFileId, ThumbnailSize.SMALL.name());
            if (this.dataFactory.findById(key, FileThumbnail.class).isPresent()) return;
            if (this.isThumbnailFile(storedFileId)) return;

            // Metadata first, so an oversized or not-yet-scanned file is skipped before its
            // content is ever pulled into this process.
            final Optional<StoredFile> metadata = this.dataFactory.findById(storedFileId, StoredFile.class);
            if (metadata.isEmpty()) return;

            final Long knownSizeBytes = metadata.get().sizeBytesIfKnown();
            if (knownSizeBytes != null && knownSizeBytes > this.maxSourceBytes) {
                // FINE, not WARNING: every later UPDATE of the same file (a rename, a move) offers
                // it here again, so this line must never be able to flood the operator terminal.
                this.getLogger().log(Level.FINE, "@CloudThumbnailsExtension.generateThumbnail: file '" + storedFileId
                        + "' is larger than the " + this.maxSourceBytes + "-byte source cap - skipped");
                return;
            }
            // Never decode content that has not been judged clean. Image and PDF decoders are
            // exactly what a malicious file targets, and this runs before any access check, on a
            // shared worker, with no user having asked for anything. A file that becomes clean
            // later is re-offered here by the scan verdict's own write.
            if (metadata.get().scanStatus() != ScanStatus.CLEAN) return;

            final Optional<StoredFile> source = this.fileFactory.findById(storedFileId);
            if (source.isEmpty()) return;
            final StoredFile file = source.get();

            final Optional<ThumbnailGenerator> generator = this.generators.stream()
                    .filter(candidate -> candidate.supports(file.contentType()))
                    .findFirst();
            if (generator.isEmpty()) return;

            final byte[] content = file.content();
            // Backstop for a row whose size metadata was unknown above - a legacy row persisted
            // before the size was recorded answers null there, and the decoder must not be the
            // first thing to find out.
            if (content.length > this.maxSourceBytes) {
                this.getLogger().log(Level.FINE, "@CloudThumbnailsExtension.generateThumbnail: file '" + storedFileId
                        + "' resolved to more than the " + this.maxSourceBytes + "-byte source cap - skipped");
                return;
            }
            // Resolved on this thread, not inside the render task, so a timeout abandons only the
            // decode and never a pending object-store fetch.
            final byte[] thumbnailBytes = this.render(generator.get(), storedFileId, content);
            if (thumbnailBytes == null) return;

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
        } catch (final Throwable e) {
            // Throwable, not RuntimeException: an OutOfMemoryError raised by a decoder used to
            // vanish here entirely, which is why this class of failure went unnoticed. A preview
            // is never worth failing anything else over, but it must at least be visible.
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.generateThumbnail: unexpected failure for file '" + storedFileId + "'", e);
        }
    }

    /**
     * Runs {@code generator} over {@code content} on {@link #renderExecutor}, bounded by the
     * configured render timeout.
     *
     * <p>Returns {@code null} when there was no free render slot or the render was abandoned: no
     * thumbnail is written then, and a later change to the file offers it here again. Cancelling
     * interrupts the render thread, but neither {@code ImageIO} nor PDFBox is reliably
     * interruptible, so an abandoned decode may keep running - which is exactly why the pool is
     * bounded at {@link #THUMBNAIL_EXECUTOR_THREADS} and a submission with no free thread is
     * rejected rather than queued: at worst two stuck threads each retain one source array and one
     * raster, both individually capped by the configured budgets.
     *
     * @param generator the generator that supports this file's content type
     * @param storedFileId the file being previewed, for the log lines
     * @param content the source file's plaintext bytes
     * @return the thumbnail's JPEG bytes, or {@code null} if this file was skipped
     * @throws IOException if the generator itself failed
     */
    private byte[] render(@NonNull final ThumbnailGenerator generator, @NonNull final String storedFileId,
                          @NonNull final byte[] content) throws IOException {
        final Future<byte[]> render;
        try {
            render = this.renderExecutor.submit(() -> generator.generate(content, ThumbnailSize.SMALL.maxDimensionPixels()));
        } catch (final RejectedExecutionException noFreeSlot) {
            this.getLogger().log(Level.INFO, "@CloudThumbnailsExtension.render: no free render slot for file '"
                    + storedFileId + "' - skipped");
            return null;
        }
        try {
            return render.get(this.renderTimeoutSeconds, TimeUnit.SECONDS);
        } catch (final TimeoutException timedOut) {
            render.cancel(true);
            this.getLogger().log(Level.WARNING, "@CloudThumbnailsExtension.render: rendering file '" + storedFileId
                    + "' exceeded " + this.renderTimeoutSeconds + "s - abandoned");
            return null;
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            render.cancel(true);
            return null;
        } catch (final ExecutionException failed) {
            // Unwrapped so the caller's existing catch blocks see exactly the failure the
            // generator raised, and log it exactly as they did when the render ran inline.
            final Throwable cause = failed.getCause();
            if (cause instanceof final IOException ioFailure) throw ioFailure;
            if (cause instanceof final RuntimeException runtimeFailure) throw runtimeFailure;
            if (cause instanceof final Error error) throw error;
            throw new IOException("@CloudThumbnailsExtension.render: rendering file '" + storedFileId + "' failed", cause);
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

    /**
     * @param namePrefix the prefix every thread this factory produces is named with, so the two
     *     pools are distinguishable in a thread dump
     * @return a {@link ThreadFactory} producing named, daemon threads - never keeps the JVM alive on its own
     */
    private static ThreadFactory daemonThreadFactory(final String namePrefix) {
        final AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            final Thread thread = new Thread(runnable, namePrefix + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }

}
