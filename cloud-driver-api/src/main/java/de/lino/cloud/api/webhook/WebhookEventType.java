package de.lino.cloud.api.webhook;

/**
 * The file events a {@link WebhookService} subscription can be registered for.
 * Deliberately just these three (upload, delete, share) - extend alongside a real new need, the same
 * "don't add speculatively ahead of a real call site" convention {@code AuditAction} already
 * follows.
 */
public enum WebhookEventType {

    /** Fired once, after a new file has been successfully uploaded - see {@code CloudUserService#uploadFile}. */
    FILE_UPLOADED,

    /** Fired once a file is moved to the trash (soft delete) - see {@code CloudUserService#deleteFile}. Not fired again on the later permanent purge of the same file, to avoid double-firing for the normal trash-then-purge path. */
    FILE_DELETED,

    /** Fired once, after a new share grant is created on a file - see {@code CloudUserService#shareFile}. Not fired for a folder share (see {@code WebhookService}'s own Javadoc for why). */
    FILE_SHARED

}
