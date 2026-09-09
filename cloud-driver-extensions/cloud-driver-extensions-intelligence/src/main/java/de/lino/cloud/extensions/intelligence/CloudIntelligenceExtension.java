package de.lino.cloud.extensions.intelligence;

import de.lino.cloud.api.event.database.FileChangeListener;
import de.lino.cloud.api.extension.Extension;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.database.json.JsonDocument;

import java.time.Duration;
import java.util.logging.Level;

/**
 * Semantic search - publishes a {@link DefaultIntelligenceService} into {@code
 * IServiceContainer#setIntelligenceService}, bridging this deployment's file uploads to the
 * separately-run {@code cloud-driver-intelligence} Python service that owns the embedding model and
 * vector store. See {@link IntelligenceService}'s own Javadoc for the full design - in particular
 * its security-invariant section, which is the whole reason this extension never answers a search
 * itself.
 *
 * <p>Built as an in-process extension, like every other extension in this repository. Note the
 * distinction from the Python service it talks to: <em>that</em> genuinely is a separate,
 * independently deployed process (the first in this codebase after {@code clamd} and Redis), while
 * this Java half is only the bridge to it.
 *
 * <h2>Why this registers no {@link FileChangeListener}, unlike {@code CloudScanExtension}</h2>
 *
 * A deliberate, flagged deviation from the handoff document's §5, which specified a {@code
 * FileChangeListener} on {@code "INSERT"} by analogy with content scanning. That mechanism is a
 * poor fit here for two independent reasons, both of which this codebase has already been bitten
 * by:
 *
 * <ul>
 *   <li><b>It cannot observe a deletion at all.</b> The backing Postgres trigger is {@code AFTER
 *       INSERT OR UPDATE} on the {@code StoredFile} table only - a soft delete is an {@code UPDATE}
 *       of {@code StoredFileOwnership} (a different table entirely) and a hard delete fires no
 *       notification whatsoever. §4 of the same document nonetheless requires {@code DELETE
 *       /index/{fileId}} to be called on deletion/trash-purge, which a listener simply could not
 *       do. This is exactly why {@code SearchIndexService}/{@code WebhookService} are both driven
 *       from {@code CloudUserService} instead, and why {@code ContentScanService} - which only ever
 *       needs to react to a brand-new row - is the one facet the listener genuinely suits.</li>
 *   <li><b>It would miss a content replacement's semantics.</b> {@code PUT /files/{id}/content}
 *       does produce an {@code UPDATE}, but a listener reacting to {@code "INSERT"} alone ignores
 *       it, and one reacting to every {@code "UPDATE"} would re-embed a file on every rename and
 *       move as well - re-fetching and re-embedding full content to change nothing but a display
 *       name.</li>
 * </ul>
 *
 * Indexing is therefore driven synchronously (but cheaply - the calls are queue-and-return) from
 * {@code CloudUserService}'s own upload/replace/delete/restore methods, exactly as search indexing
 * already is. This extension's whole job is to build the service and publish it; nothing here
 * reacts to an event.
 */
public class CloudIntelligenceExtension extends Extension {

    /** {@code configuration.json} key for the Python service's host - defaults to {@link #DEFAULT_INTELLIGENCE_HOST} if unset. */
    private static final String INTELLIGENCE_HOST_CONFIG_KEY = "intelligence-host";
    /** Default host - the same "runs alongside this process" deployment shape {@code clamd} uses. */
    private static final String DEFAULT_INTELLIGENCE_HOST = "127.0.0.1";
    /** {@code configuration.json} key for the Python service's TCP port - defaults to {@link #DEFAULT_INTELLIGENCE_PORT} if unset. */
    private static final String INTELLIGENCE_PORT_CONFIG_KEY = "intelligence-port";
    /** Default port - an arbitrary free high port, deliberately not adjacent to any other port this deployment already binds ({@code 8080} REST, {@code 9404} metrics, {@code 3310} clamd, {@code 6379} Redis). */
    private static final int DEFAULT_INTELLIGENCE_PORT = 8600;
    /** {@code configuration.json} key for the shared secret sent as {@code X-Internal-Secret} - <b>required</b>; this extension does not load at all without it. */
    private static final String INTELLIGENCE_SHARED_SECRET_CONFIG_KEY = "intelligence-shared-secret";
    /** {@code configuration.json} key for the per-request HTTP timeout, in seconds. */
    private static final String INTELLIGENCE_TIMEOUT_SECONDS_CONFIG_KEY = "intelligence-timeout-seconds";
    private static final long DEFAULT_INTELLIGENCE_TIMEOUT_SECONDS = 30L;
    /** {@code configuration.json} key for the maximum content size ever submitted for embedding - defaults to {@link #DEFAULT_MAX_INDEXABLE_BYTES}. */
    private static final String MAX_INDEXABLE_BYTES_CONFIG_KEY = "intelligence-max-bytes";
    /**
     * Default index size cap - 100 MiB, the value the handoff document specifies.
     *
     * <p><b>Worth lowering deliberately on a deployment holding large binaries.</b> Content travels
     * to the Python service base64-encoded in a JSON body (~1.37x the raw size, so this default
     * permits a ~137 MiB request), and a large binary is overwhelmingly likely to yield nothing
     * embeddable anyway - the service falls back to a name-only embedding for it, having been sent
     * the whole file to reach that conclusion. Kept at the specified default rather than silently
     * chosen differently; a value in the low tens of MiB is more proportionate in practice.
     */
    private static final long DEFAULT_MAX_INDEXABLE_BYTES = 100L * 1024 * 1024;

    private DefaultIntelligenceService intelligenceService;

    /**
     * Builds a {@link DefaultIntelligenceService} from this deployment's configured (or default)
     * Python-service connection settings and publishes it into {@code
     * IServiceContainer#setIntelligenceService}.
     *
     * <p>Throws if {@link #INTELLIGENCE_SHARED_SECRET_CONFIG_KEY} is unset - unlike every other key
     * here, there is no safe default for it: falling back to an empty secret would either be
     * rejected by the Python service on every call (a silently permanently-broken extension) or,
     * worse, invite that service to be configured to accept an empty one. Failing loudly at load
     * time is routed to {@link #onException} and disables only this extension, exactly as any other
     * extension's load failure is.
     */
    @Override
    public void onLoading() {

        final JsonDocument configuration = this.cloudDriver().getConfiguration();
        if (!configuration.contains(INTELLIGENCE_SHARED_SECRET_CONFIG_KEY)) {
            throw new IllegalStateException("@CloudIntelligenceExtension: '" + INTELLIGENCE_SHARED_SECRET_CONFIG_KEY
                    + "' is not set in configuration.json - semantic search cannot be enabled without it");
        }
        final String sharedSecret = configuration.getString(INTELLIGENCE_SHARED_SECRET_CONFIG_KEY);
        if (sharedSecret == null || sharedSecret.isBlank()) {
            throw new IllegalStateException("@CloudIntelligenceExtension: '" + INTELLIGENCE_SHARED_SECRET_CONFIG_KEY
                    + "' is blank in configuration.json - semantic search cannot be enabled without a real secret");
        }

        final String host = configuration.contains(INTELLIGENCE_HOST_CONFIG_KEY)
                ? configuration.getString(INTELLIGENCE_HOST_CONFIG_KEY) : DEFAULT_INTELLIGENCE_HOST;
        final int port = configuration.contains(INTELLIGENCE_PORT_CONFIG_KEY)
                ? configuration.getInteger(INTELLIGENCE_PORT_CONFIG_KEY) : DEFAULT_INTELLIGENCE_PORT;
        final long timeoutSeconds = configuration.contains(INTELLIGENCE_TIMEOUT_SECONDS_CONFIG_KEY)
                ? configuration.getLong(INTELLIGENCE_TIMEOUT_SECONDS_CONFIG_KEY) : DEFAULT_INTELLIGENCE_TIMEOUT_SECONDS;
        final long maxIndexableBytes = configuration.contains(MAX_INDEXABLE_BYTES_CONFIG_KEY)
                ? configuration.getLong(MAX_INDEXABLE_BYTES_CONFIG_KEY) : DEFAULT_MAX_INDEXABLE_BYTES;

        this.intelligenceService = new DefaultIntelligenceService(
                this.getLogger(), host, port, sharedSecret, Duration.ofSeconds(timeoutSeconds), maxIndexableBytes);
        this.cloudDriver().getServiceContainer().setIntelligenceService(this.intelligenceService);

    }

    /**
     * Probes the Python service's {@code /health} endpoint once and reports the outcome - purely
     * informational. An unreachable service is <b>not</b> a startup failure (see §7 of the handoff
     * document): the first real indexing attempt simply fails open, and the service may well come
     * up moments later, so this only makes a misconfiguration visible in the terminal rather than
     * leaving it to be discovered from a warning log much later.
     */
    @Override
    public void onRunning(final String[] args) {
        if (this.intelligenceService.isServiceReachable()) {
            this.cloudDriver().getTerminal().displayApproved("&3Semantic search &bready &7- cloud-driver-intelligence reachable");
        } else {
            this.cloudDriver().getTerminal().displayApproved("&3Semantic search &eloaded &7- but cloud-driver-intelligence is "
                    + "&cnot reachable&7; uploads stay un-indexed until it comes up");
        }
    }

    /** Shuts {@link #intelligenceService} down. */
    @Override
    public void onEnding() {
        this.shutdown();
    }

    /**
     * Shuts {@link #intelligenceService} down, then logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        this.shutdown();
        this.getLogger().log(Level.SEVERE, "An error occurred while running the semantic-search extension.", reason);
    }

    private void shutdown() {
        if (this.intelligenceService != null) {
            this.intelligenceService.shutdown();
            this.cloudDriver().getTerminal().displayApproved("&3Semantic search &7successfully &cclosed&7.");
        }
    }

}
