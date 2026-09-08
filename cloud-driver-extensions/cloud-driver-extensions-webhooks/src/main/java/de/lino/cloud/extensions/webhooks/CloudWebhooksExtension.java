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

    /** Publishes a {@link DefaultWebhookService}. */
    @Override
    public void onLoading() {
        this.webhookService = new DefaultWebhookService(this.cloudDriver().getFactoryContainer().getDataFactory(), this.getLogger());
        this.cloudDriver().getServiceContainer().setWebhookService(this.webhookService);
    }

    /** Prints a confirmation once {@link #onLoading()} has published the webhook service. */
    @Override
    public void onRunning(final String[] args) {
        this.cloudDriver().getTerminal().displayApproved("&dWebhooks &bready &7- dispatching upload/delete/share events to subscribed URLs");
    }

    /** Shuts {@link #webhookService}'s dispatch workers down, if it was ever built. */
    @Override
    public void onEnding() {
        if (this.webhookService != null) this.webhookService.shutdown();
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
