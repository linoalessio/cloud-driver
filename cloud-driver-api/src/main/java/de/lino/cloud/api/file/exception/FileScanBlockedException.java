package de.lino.cloud.api.file.exception;

import de.lino.cloud.api.file.ScanStatus;
import org.jetbrains.annotations.NotNull;

/**
 * Thrown when content access to a {@link de.lino.cloud.api.file.StoredFile} is refused because of
 * its {@link ScanStatus} - section 9 of {@code architecture/MICRO.md}. {@link
 * ScanStatus#PENDING} means "not ready yet, try again later"; {@link ScanStatus#FLAGGED} means
 * "permanently refused". {@code DefaultRestFactory} maps the two to different HTTP statuses (see
 * that class's own handling) rather than collapsing them into one generic error, matching the
 * handoff doc's own "blocked with a clear status response, not a generic error" instruction.
 */
public final class FileScanBlockedException extends RuntimeException {

    private final ScanStatus scanStatus;

    public FileScanBlockedException(@NotNull final ScanStatus scanStatus) {
        super("File content access refused - scan status is " + scanStatus);
        this.scanStatus = scanStatus;
    }

    /** @return the {@link ScanStatus} that caused this refusal - always {@link ScanStatus#PENDING} or {@link ScanStatus#FLAGGED} */
    @NotNull
    public ScanStatus scanStatus() {
        return this.scanStatus;
    }

}
