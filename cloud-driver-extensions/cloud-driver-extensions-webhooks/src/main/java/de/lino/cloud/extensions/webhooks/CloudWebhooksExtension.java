package de.lino.cloud.extensions.webhooks;

import de.lino.cloud.api.extension.Extension;

import java.util.logging.Level;

/**
 * Webhooks - publishes a {@link DefaultWebhookService}
 * into {@code IServiceContainer#setWebhookService}. See {@code WebhookService}'s own Javadoc for
 * the full design, in particular why dispatch is driven synchronously (but cheaply - real HTTP
 * delivery always happens on the service's own background workers) from {@code
 * de.lino.cloud.auth.CloudUserService}'s own mutation methods rather than the async {@code
 * FileChangeListener} mechanism.
 *
 * <p>Built as an in-process extension, not a genuinely standalone deployable service, for the
 * same reason {@code cloud-driver-extensions-thumbnails}/{@code -versioning} were.
 */
public class CloudWebhooksExtension extends Extension {

    private DefaultWebhookService webhookService;

    /**
     * Publishes a {@link DefaultWebhookService}, sized by the optional {@code
     * webhook-dispatch-pool-size} key in {@code configuration.json} ({@link
     * DefaultWebhookService#DEFAULT_DISPATCH_POOL_SIZE} when unset - the fixed size this pool
     * always had before it became tunable).
     */
    @Override
    public void onLoading() {
        final de.lino.database.json.JsonDocument configuration = this.cloudDriver().getConfiguration();
        final int dispatchPoolSize = configuration.contains("webhook-dispatch-pool-size")
                ? configuration.getInteger("webhook-dispatch-pool-size")
                : DefaultWebhookService.DEFAULT_DISPATCH_POOL_SIZE;
        this.webhookService = new DefaultWebhookService(
                this.cloudDriver().getFactoryContainer().getDataFactory(), this.getLogger(), dispatchPoolSize);
        this.cloudDriver().getServiceContainer().setWebhookService(this.webhookService);
    }

    /** Prints a confirmation once {@link #onLoading()} has published the webhook service. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&3Webhooks &bready &7- dispatching upload/delete/share events to subscribed URLs");
        this.cloudDriver().getTerminal().displayApproved(
                this.webhookService.isDeliveryHistoryDurable()
                        ? "&3Webhook delivery history &bpersisted &7to Redis - survives a restart"
                        : "&3Webhook delivery history &ein-process only &7- no Redis configured, history is lost on restart");
    }

    /** Shuts {@link #webhookService}'s dispatch workers down, if it was ever built. */
    @Override
    public void onEnding() {
        if (this.webhookService != null) {
            this.cloudDriver().getTerminal().displayApproved("&3Webhooks endpoint &7successfully &cclosed&7.");
            this.webhookService.shutdown();
        }
    }

    /**
     * Shuts {@link #webhookService}'s dispatch workers down, if it was ever built, and logs the failure.
     *
     * @param reason the exception that occurred
     */
    @Override
    public void onException(final RuntimeException reason) {
        if (this.webhookService != null) this.webhookService.shutdown();
        this.getLogger().log(Level.SEVERE, "An error occurred while running the webhooks extension.", reason);
    }

}
