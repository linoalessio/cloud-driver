package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.container.IFactoryContainer;
import de.lino.cloud.api.factory.service.IServiceContainer;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.cloud.api.redis.RedisSupport;
import de.lino.cloud.api.s3storage.ObjectStorageService;
import de.lino.cloud.api.scan.ContentScanService;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Supplier;

/**
 * A single view of which optional subsystems this deployment actually has, and which of the
 * external ones are genuinely answering right now.
 *
 * <h2>Why this command exists</h2>
 *
 * This system fails <b>open</b> in at least five independent places, and every one of them is
 * silent:
 *
 * <ul>
 *   <li>content scanning marks a file {@code CLEAN} when {@code clamd} cannot be reached, and
 *       never revisits it - so an unreachable scanner does not degrade uploads, it stops
 *       protecting them;</li>
 *   <li>semantic indexing retries a bounded number of times and then gives up, leaving the file
 *       simply un-indexed;</li>
 *   <li>rate limiting falls back from Redis to per-process counters, silently losing its
 *       cross-restart guarantee;</li>
 *   <li>outgoing mail falls back to a {@code LoggingEmailSender} that delivers nothing at all;</li>
 *   <li>every optional facet is simply {@code null} until its extension publishes one, and every
 *       caller null-checks and moves on.</li>
 * </ul>
 *
 * Each of those is the right behaviour in isolation - none should take the server down - but
 * together they mean a deployment can be substantially broken while looking completely healthy.
 * Two of them have already caused real incidents here: {@code clamd} ran for an unknown period
 * with no TCP listener at all (every scan refused, retried, failed open), and a mail
 * misconfiguration surfaced only as a bare {@code 500} on registration. Both would have been a
 * one-line answer from this command.
 *
 * <h2>Published is not the same as working</h2>
 *
 * The distinction this command draws throughout: a facet being <em>published</em> only means an
 * extension loaded. For anything backed by a separate process - {@code clamd}, the embedding
 * service, Redis, S3 - that says nothing about whether it answers. Those get a live probe, marked
 * separately, because that is the half that actually matters.
 */
public class HealthCommand implements Command {

    /** @return {@code "health"} */
    @Override
    public @NotNull String name() {
        return "health";
    }

    /** @return {@code "status"}, {@code "facets"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("status", "facets");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Show which optional subsystems are published, and probe the external ones for real";
    }

    /**
     * Prints the full report. Takes no arguments - a health check that needed to be told what to
     * check would defeat its own purpose.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final IServiceContainer services = CloudDriver.getInstance().getServiceContainer();
        final IFactoryContainer factories = CloudDriver.getInstance().getFactoryContainer();

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fCore &8---");
        this.report(terminal, "DataFactory", factories.getDataFactory() != null);
        this.report(terminal, "FileFactory", factories.getFileFactory() != null);
        this.report(terminal, "AuthService", services.getAuthService() != null);
        this.report(terminal, "CloudUserService", services.getCloudUserService() != null);
        this.report(terminal, "AuditLogService", services.getAuditLogService() != null);

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fOptional facets &8---");
        this.report(terminal, "Thumbnails", services.getThumbnailService() != null);
        this.report(terminal, "Versioning", services.getFileVersioningService() != null);
        this.report(terminal, "Search index", services.getSearchIndexService() != null);
        this.report(terminal, "Webhooks", services.getWebhookService() != null);
        this.report(terminal, "Live push", services.getLiveUpdatePublisher() != null);
        this.report(terminal, "Metrics", services.getMetricsSnapshotProvider() != null);
        this.report(terminal, "Rate-limit admin", services.getRateLimitAdmin() != null);
        this.report(terminal, "Backup", services.getBackupService() != null);
        this.report(terminal, "Object storage (S3)", factories.getObjectStorageService() != null);

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fExternal dependencies &7(live probe) &8---");
        this.probeContentScan(terminal, services.getContentScanService());
        this.probeIntelligence(terminal, services.getIntelligenceService());
        this.probeRedis(terminal, factories.getRedisSupport());
        this.probeObjectStorage(terminal, factories.getObjectStorageService());
        this.probeMail(terminal, services);

        this.reportSearchIndexDepth(terminal, services.getSearchIndexService());
        terminal.emptyLine();

    }

    /** One published/absent line. */
    private void report(final Terminal terminal, final String label, final boolean published) {
        terminal.displayApproved("&8- &7%-22s %s", label, published ? "&apublished" : "&8not published");
    }

    /**
     * One line for a subsystem whose real state needs a network/socket round trip.
     *
     * <p>Every probe is wrapped so a broken dependency reports as broken rather than throwing out
     * of this command - a diagnostic that can itself fail is worth very little at the moment it is
     * actually needed.
     */
    private void probe(final Terminal terminal, final String label, final boolean published, final Supplier<Boolean> probe) {
        if (!published) {
            terminal.displayApproved("&8- &7%-22s &8not published", label);
            return;
        }
        final boolean reachable;
        try {
            reachable = Boolean.TRUE.equals(probe.get());
        } catch (final RuntimeException probeFailed) {
            terminal.displayApproved("&8- &7%-22s &cprobe failed &8(%s)", label, probeFailed.getClass().getSimpleName());
            return;
        }
        terminal.displayApproved("&8- &7%-22s %s", label, reachable ? "&areachable" : "&cUNREACHABLE");
    }

    private void probeContentScan(final Terminal terminal, final ContentScanService contentScanService) {
        this.probe(terminal, "ClamAV (clamd)", contentScanService != null,
                () -> contentScanService.isScannerReachable());
        if (contentScanService != null && !contentScanService.isScannerReachable()) {
            // Worth stating outright rather than leaving to be inferred: this is the one failure
            // here that silently removes a security control instead of a convenience.
            terminal.displayApproved("  &c! &7Uploads are being marked &fCLEAN &7without ever being scanned.");
        }
    }

    private void probeIntelligence(final Terminal terminal, final IntelligenceService intelligenceService) {
        this.probe(terminal, "Semantic search", intelligenceService != null,
                () -> intelligenceService.isServiceHealthy());
    }

    private void probeRedis(final Terminal terminal, final RedisSupport redisSupport) {
        this.probe(terminal, "Redis", redisSupport != null, () -> {
            // A real command, not just "the object exists" - a pool that failed after boot would
            // otherwise still report healthy. Read-only: getCount never creates a key, so this
            // probe cannot contribute to the keyspace growth RedisSupport's Javadoc warns about.
            redisSupport.counterService().getCount("cloud-driver:health-probe");
            return true;
        });
        if (redisSupport == null) {
            terminal.displayApproved("  &8  &7Rate-limit windows are per-process and reset on restart.");
        }
    }

    private void probeObjectStorage(final Terminal terminal, final ObjectStorageService objectStorageService) {
        this.probe(terminal, "S3 bucket", objectStorageService != null, () -> {
            // Listing one key proves credentials, region, bucket name and s3:ListBucket all work -
            // which a mere "the service object was constructed" does not, since construction never
            // contacts AWS at all.
            objectStorageService.listObjects(null, 1);
            return true;
        });
    }

    /**
     * Mail cannot be probed without actually sending something, so this reports which
     * implementation was resolved instead - which is the part that has historically been wrong.
     */
    private void probeMail(final Terminal terminal, final IServiceContainer services) {
        final Object emailSender = services.getEmailSender();
        if (emailSender == null) {
            terminal.displayApproved("&8- &7%-22s &8not published", "Outgoing mail");
            return;
        }
        final String implementation = emailSender.getClass().getSimpleName();
        final boolean deliversNothing = implementation.contains("Logging");
        terminal.displayApproved("&8- &7%-22s %s &8(%s)", "Outgoing mail",
                deliversNothing ? "&cLOGS ONLY" : "&aconfigured", implementation);
        if (deliversNothing) {
            terminal.displayApproved("  &c! &7No verification e-mail is actually being delivered. Run &fmail test <address>&7.");
        }
    }

    /**
     * A published search index that holds nothing is indistinguishable from a broken one from a
     * user's point of view - this is the number that tells them apart.
     */
    private void reportSearchIndexDepth(final Terminal terminal, final SearchIndexService searchIndexService) {
        if (searchIndexService == null) return;
        final int documents = searchIndexService.indexedDocumentCount();
        if (documents < 0) return;
        terminal.emptyLine();
        terminal.displayApproved("&8--- &fSearch index &8---");
        terminal.displayApproved("&8- &7Indexed documents: &b%s", documents);
        if (documents == 0) {
            terminal.displayApproved("  &e! &7Index is empty - run &fsearchIndex rebuild &7if files exist.");
        }
    }

}
