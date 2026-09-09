package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.UnitParser;
import de.lino.cloud.api.versioning.FileVersioningService;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Everything known about one file, in one place.
 *
 * <h2>Why this is worth a command</h2>
 *
 * A file's state is spread across several rows and several optional subsystems by design: the
 * {@code StoredFile} itself holds content and integrity data, a separate {@code
 * StoredFileOwnership} row holds placement, cached metadata, trash state and the cached scan
 * verdict, deduplication is expressed as a reference between two {@code StoredFile}s, and versions
 * and shares live elsewhere again. That is the right normalisation, but it means answering an
 * ordinary support question - "why can't this user download their file?" - previously meant
 * opening a SQL client and joining it together by hand.
 *
 * <p>Read-only, deliberately. Every mutating operation on a file already has a proper,
 * access-checked path; a terminal shortcut around those would bypass quota accounting, audit
 * logging and share revocation all at once.
 */
public class FileCommand implements Command {

    /** Timestamp format for the detail lines. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    /** @return {@code "file"} */
    @Override
    public @NotNull String name() {
        return "file";
    }

    /** @return {@code "storedFile"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("storedFile");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Inspect one file: owner, placement, storage mode, scan status, versions and shares";
    }

    /**
     * Prints one file's full state.
     *
     * @param arguments {@code info <fileId>}, or just {@code <fileId>}
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        final String storedFileId;
        if (arguments.hasCommand(0, "info") && arguments.hasLength(1)) {
            storedFileId = arguments.command(1);
        } else if (!arguments.isEmpty()) {
            storedFileId = arguments.command(0);
        } else {
            terminal.displayApproved("&ffile info <fileId>");
            return;
        }

        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();

        // Read metadata through DataFactory, never FileFactory: the latter resolves content, which
        // for an S3-backed file means a network fetch and a full decrypt just to print a size.
        final Optional<StoredFile> storedFile;
        try {
            storedFile = dataFactory.findById(storedFileId, StoredFile.class);
        } catch (final Exception failed) {
            terminal.displayApproved("&cCould not read the file row&7: %s", failed.getMessage());
            return;
        }
        if (storedFile.isEmpty()) {
            terminal.displayApproved("No file found with id '&b%s&7'", storedFileId);
            return;
        }
        final StoredFile file = storedFile.get();

        terminal.emptyLine();
        terminal.displayApproved("&8--- &f%s &8---", file.fileName());
        terminal.displayApproved("&8- &7Id:            &b%s", file.fileId());
        terminal.displayApproved("&8- &7Content type:  &b%s", file.contentType());
        terminal.displayApproved("&8- &7Created:       &b%s", TIMESTAMP.format(file.createdAt()));
        terminal.displayApproved("&8- &7Updated:       &b%s", TIMESTAMP.format(file.updatedAt()));
        terminal.displayApproved("&8- &7Storage:       &b%s", this.describeStorage(file));
        terminal.displayApproved("&8- &7Scan status:   %s", this.describeScanStatus(file.scanStatus() == null ? null : file.scanStatus().name()));

        this.reportOwnership(terminal, dataFactory, storedFileId);
        this.reportVersions(terminal, storedFileId);
        terminal.emptyLine();
    }

    /**
     * Which of the four mutually-exclusive content modes this file uses.
     *
     * <p>Worth surfacing explicitly because they behave very differently under failure: a
     * direct-transfer file's checksum is client-reported rather than server-computed, and a
     * deduplication alias holds no content of its own at all - so "the row exists but the download
     * fails" has a completely different cause in each case.
     */
    private String describeStorage(final StoredFile file) {
        if (file.isDedupAlias()) {
            return "deduplication alias &8-> " + file.dedupOfFileId();
        }
        if (file.isDirectTransfer()) {
            return "S3, direct transfer &8(client-reported checksum, SSE-S3)";
        }
        if (file.isS3Backed()) {
            return "S3, app-encrypted &8(key " + file.objectStorageKey() + ")";
        }
        return "inline in Postgres" + (file.isCompressed() ? " &8(DEFLATE-compressed)" : "");
    }

    /** A scan status with the colour its severity warrants. */
    private String describeScanStatus(final String scanStatus) {
        if (scanStatus == null || "CLEAN".equalsIgnoreCase(scanStatus)) return "&aCLEAN";
        if ("FLAGGED".equalsIgnoreCase(scanStatus)) return "&cFLAGGED &7- downloads are refused";
        return "&ePENDING &7- downloads are refused until a verdict lands";
    }

    /** Owner, folder placement and trash state, from the ownership row rather than the file. */
    private void reportOwnership(final Terminal terminal, final DataFactory dataFactory, final String storedFileId) {
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&8- &7Owner:         &8unknown (CloudUserService not published)");
            return;
        }

        final String ownerAuthUserId = cloudUserService.resolveOwnerAuthUserId(storedFileId).orElse(null);
        if (ownerAuthUserId == null) {
            // A StoredFile with no ownership row is a genuine inconsistency, not a normal state -
            // it is invisible to every listing while still occupying storage.
            terminal.displayApproved("&8- &cNo ownership row &7- this file is orphaned and invisible to every listing.");
            return;
        }

        final String ownerEmail = cloudUserService.getCloudUser(ownerAuthUserId)
                .map(user -> user.getAuthUser().getEmailAddress())
                .orElse(ownerAuthUserId);
        terminal.displayApproved("&8- &7Owner:         &b%s", ownerEmail);

        try {
            final Optional<StoredFileOwnership> ownership = dataFactory.findById(
                    StoredFileOwnership.compositeKey(ownerAuthUserId, storedFileId), StoredFileOwnership.class);
            ownership.ifPresent(row -> {
                terminal.displayApproved("&8- &7Folder:        &b%s", row.getFolderId() == null ? "(root)" : row.getFolderId());
                terminal.displayApproved("&8- &7Size:          &b%s", UnitParser.parseByteUnit(row.getSizeBytes()));
                if (row.isDeleted()) {
                    terminal.displayApproved("&8- &eIn the trash &7since &b%s",
                            TIMESTAMP.format(Instant.ofEpochMilli(row.getDeletedAtEpochMillis())));
                }
            });
        } catch (final Exception ignored) {
            terminal.displayApproved("&8- &7Placement:     &8unreadable");
        }

        try {
            final List<String> grantees = cloudUserService.listFileShares(ownerAuthUserId, storedFileId);
            if (!grantees.isEmpty()) {
                terminal.displayApproved("&8- &7Shared with:   &b%s", String.join(", ", grantees));
            }
            final int publicLinks = cloudUserService.listPublicFileLinks(ownerAuthUserId, storedFileId).size();
            if (publicLinks > 0) {
                // Public links are the only unauthenticated read path in the system, so their
                // existence on a given file is worth stating rather than leaving to be discovered.
                terminal.displayApproved("&8- &ePublic links:  &e%s &7(unauthenticated access)", publicLinks);
            }
        } catch (final RuntimeException ignored) {
            // Sharing state is supplementary here - a failure to read it must not hide everything above.
        }
    }

    /** Version count, if the versioning extension is running. */
    private void reportVersions(final Terminal terminal, final String storedFileId) {
        final FileVersioningService versioningService = CloudDriver.getInstance().getServiceContainer().getFileVersioningService();
        if (versioningService == null) return;
        try {
            terminal.displayApproved("&8- &7Versions:      &b%s", versioningService.listVersions(storedFileId).size());
        } catch (final RuntimeException ignored) {
            // Same reasoning as sharing state above.
        }
    }

}
