package de.lino.cloud.api.file;

/**
 * A {@link StoredFile}'s malware-scan status - section 9 of {@code architecture/MICRO.md}. {@link
 * StoredFile#scanStatus()} is nullable, and a {@code null} value is always read back as {@link
 * #CLEAN} (see that method's own Javadoc) - "nullable/default-clean so existing files are
 * unaffected", per the handoff doc's own instruction, and so is every new upload made while this
 * deployment doesn't run {@code cloud-driver-extensions-scan} at all.
 */
public enum ScanStatus {

    /** Content scanning is in progress - {@code GET /files/{id}}/{@code GET /files/{id}/content} both refuse to serve content while a file is in this state. */
    PENDING,

    /** Scanned and found clean, or never scanned at all ({@code null} on the entity, read back as this value - see this enum's own Javadoc). */
    CLEAN,

    /** Scanned and flagged as malware - content access is permanently refused; the file itself is left in place (never auto-deleted), the same "don't silently destroy data" caution this codebase already applies elsewhere. */
    FLAGGED

}
