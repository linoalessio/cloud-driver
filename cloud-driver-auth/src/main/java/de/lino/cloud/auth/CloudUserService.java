package de.lino.cloud.auth;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditAction;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.FileWithFolder;
import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.file.SharedFileSummary;
import de.lino.cloud.api.file.SharedFolderContents;
import de.lino.cloud.api.file.SharedFolderSummary;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.file.StoredFileSummary;
import de.lino.cloud.api.file.TrashedFileSummary;
import de.lino.cloud.api.file.TrashedFolderSummary;
import de.lino.cloud.api.file.exception.FileIntegrityException;
import de.lino.cloud.api.file.exception.UploadQuotaExceededException;
import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.storage.object.ObjectStorageException;
import de.lino.cloud.api.storage.object.PresignedDownload;
import de.lino.cloud.api.storage.object.PresignedTransferService;
import de.lino.cloud.api.storage.object.PresignedTransferUnavailableException;
import de.lino.cloud.api.file.PresignedUploadTicket;
import de.lino.cloud.api.user.GranteeAccountNotFoundException;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.CursorPage;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.SharedFileGrant;
import de.lino.cloud.auth.entity.SharedFolderGrant;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/**
 * Ties {@link AuthUser} accounts to the {@link StoredFile}s they've uploaded and the {@link
 * Folder}s they've organized them into. Each user's own {@link CloudUser} record only
 * identifies the user; ownership of individual files is tracked separately, one {@link
 * StoredFileOwnership} row per (user, file) pair, which also carries that file's current
 * folder placement - see that class's Javadoc for why. Framework-agnostic, same reasoning as
 * {@link AuthService}: every checked exception a delegate call can throw is rewrapped as a
 * plain {@link RuntimeException} rather than declared, since a caller wiring this into an HTTP
 * layer handles failures at that boundary, not here. Every method takes the caller's plain
 * {@code authUserId} - not a full {@link AuthUser} - since that's the only thing available once
 * a JWT has been validated (see {@code DefaultRestFactory#requireValidBearerToken}).
 */
public final class CloudUserService implements ICloudUserService {

    /** Persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows. */
    private final DataFactory dataFactory;

    /** Uploads/downloads/deletes the underlying {@link StoredFile} content. */
    private final FileFactory fileFactory;

    /**
     * Records security-relevant actions ({@link #deleteFile}/{@link #deleteCloudUser}) to the
     * persisted audit trail - see {@code architecture/SERVICES.md} item 11 and {@code
     * AuditLogService}'s own Javadoc. Never throws, so both call sites below invoke it directly
     * with no defensive try/catch of their own.
     */
    private final AuditLogService auditLogService;

    /**
     * Generates presigned URLs for direct-to-client upload/download - {@code null} if this
     * deployment hasn't configured one, in which case {@link #beginPresignedUpload}/{@link
     * #completePresignedUpload}/{@link #beginPresignedDownload} all throw {@link
     * PresignedTransferUnavailableException}.
     */
    @Nullable
    private final PresignedTransferService presignedTransferService;

    /**
     * Same as {@link #CloudUserService(DataFactory, FileFactory, AuditLogService,
     * PresignedTransferService)} with {@link #presignedTransferService} defaulted to {@code null} -
     * presigned direct-to-client transfer not configured.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory,
                             @NonNull final AuditLogService auditLogService) {
        this(dataFactory, fileFactory, auditLogService, null);
    }

    /**
     * Creates a {@code CloudUserService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     * @param presignedTransferService generates presigned URLs for direct-to-client transfer, or
     *     {@code null} if this deployment hasn't configured one
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory,
                             @NonNull final AuditLogService auditLogService, @Nullable final PresignedTransferService presignedTransferService) {
        this.presignedTransferService = presignedTransferService;
        this.dataFactory = dataFactory;
        this.fileFactory = fileFactory;
        this.auditLogService = auditLogService;
    }

    /**
     * Looks up {@code authUserId}'s {@link CloudUser} record, creating and persisting a fresh
     * one on first use.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()}
     * @return the existing or newly-created {@link CloudUser}
     */
    @NonNull
    @Override
    public ICloudUser getOrCreate(@NonNull final String authUserId) {
        try {

            final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
            if (cloudUser.isPresent()) return cloudUser.get();

            final CloudUser newCloudUser = new CloudUser(authUserId);
            this.dataFactory.register(newCloudUser);
            return newCloudUser;
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getOrCreate: failed to look up/create CloudUser for " + authUserId, e);
        }
    }

    /**
     * Looks up {@code authUserId}'s {@link CloudUser} record directly, without creating one if it
     * doesn't exist yet - the read-only counterpart to {@link #getOrCreate(String)}.
     *
     * @param authUserId the owning {@link AuthUser#getId()}
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if none is registered under that id
     */
    @Override
    public @NonNull Optional<ICloudUser> getCloudUser(@NotNull String authUserId) {
        try {
            return this.dataFactory.findById(authUserId, CloudUser.class).map(cloudUser -> cloudUser);
        } catch (DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Looks up the {@link AuthUser} registered under {@code emailAddress} (the same full-table
     * scan {@link AuthService#login} performs), then resolves that account's own {@link
     * CloudUser} record via {@link #getCloudUser(String)}.
     *
     * @param emailAddress the {@link AuthUser#getEmailAddress()} to look up
     * @return the matching {@link ICloudUser}, or {@link Optional#empty()} if no account is registered under that email
     */
    @Override
    public @NonNull Optional<ICloudUser> getCloudUserByEmail(@NonNull final String emailAddress) {
        try {

            final Optional<AuthUser> authUser = this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(user -> user.getEmailAddress().equals(emailAddress))
                    .findFirst();
            return authUser.flatMap(user -> this.getCloudUser(user.getId()));

        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getCloudUserByEmail: failed to look up CloudUser for " + emailAddress, e);
        }
    }

    /**
     * Scans every {@link StoredFileOwnership} row (same full-section-scan trade-off {@link
     * #getCloudUserByEmail(String)}/{@link #listFiles} already accept) for the one tracking
     * {@code storedFileId}, and returns its {@code authUserId}. Deliberately does not filter out
     * a trashed row (item 3, soft delete) - the owner of a file that was just moved to trash (or
     * restored, or hard-deleted) is exactly who a live-push notification about that change should
     * still reach.
     *
     * @param storedFileId the {@link StoredFile#fileId()} to resolve an owner for
     * @return the owning {@code authUserId}, or {@link Optional#empty()} if no ownership row tracks this file
     */
    @Override
    public @NonNull Optional<String> resolveOwnerAuthUserId(@NotNull final String storedFileId) {
        try {

            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getStoredFileId().equals(storedFileId))
                    .map(StoredFileOwnership::getAuthUserId)
                    .findFirst();

        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.resolveOwnerAuthUserId: failed to look up owner for " + storedFileId, e);
        }
    }

    /**
     * Deletes every {@link StoredFile}/{@link Folder} owned by {@code authUserId} (via {@link
     * #resetCloudUser(String)}) and additionally removes the {@link CloudUser} record itself -
     * unlike {@link #resetCloudUser(String)}, the account is no longer tracked at all afterwards.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to delete
     */
    @Override
    public void deleteCloudUser(@NonNull final String authUserId) {
        this.resetCloudUser(authUserId);
        try {
            this.dataFactory.delete(authUserId, CloudUser.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteCloudUser: failed to delete CloudUser record for " + authUserId, e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.ACCOUNT_DELETE, authUserId, null));
    }

    /**
     * Deletes every {@link StoredFile} (via {@link #deleteFile}, so both the file content and
     * its {@link StoredFileOwnership} row are removed together) and every {@link Folder} owned
     * by {@code authUserId}, leaving the {@link CloudUser} record itself untouched. Folders are
     * deleted leaf-first ({@link #deleteAllOwnedFolders}) since {@link #deleteFolder} refuses to
     * remove a folder that still has children.
     *
     * @param authUserId the owning {@link de.lino.cloud.api.jwt.user.AuthUser#getId()} to reset
     */
    @Override
    public void resetCloudUser(@NonNull final String authUserId) {
        // Bypasses the trash entirely (hardDeleteFile), regardless of each file's current
        // deleteFile/restoreFile trash state - this operation's whole point is to actually empty
        // the account, not move everything into (or leave it sitting in) the trash.
        for (final StoredFileOwnership ownership : this.ownedFileOwnershipsIncludingDeleted(authUserId)) {
            this.hardDeleteFile(authUserId, ownership);
        }
        this.deleteAllOwnedFolders(authUserId);
    }

    /**
     * Permanently removes {@code storedFileId}'s content and ownership tracking, bypassing the
     * trash {@link #deleteFile}/{@link #restoreFile} normally goes through entirely - the same
     * delete/decrement sequence {@link #deleteFile} performed before soft delete existed. Used by
     * {@link #resetCloudUser(String)} (which must actually empty the account, not fill its trash)
     * and by a future purge job for records past their retention window.
     *
     * @param authUserId the owning user, whose usage total is decremented if {@code ownership} carries metadata
     * @param ownership the ownership row to permanently remove
     */
    private void hardDeleteFile(final String authUserId, final StoredFileOwnership ownership) {
        final String storedFileId = ownership.getStoredFileId();
        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.hardDeleteFile: failed to delete " + storedFileId, e);
        }
        try {
            this.dataFactory.delete(StoredFileOwnership.compositeKey(authUserId, storedFileId), StoredFileOwnership.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException(
                    "@CloudUserService.hardDeleteFile: failed to untrack ownership of " + storedFileId + " for " + authUserId, e
            );
        }
        // Item 9 (sharing), fixed 2026-09-02 - a no-op scan if deleteFile already revoked these
        // (the normal trash-then-purge path), but resetCloudUser/deleteCloudUser call this
        // directly on a possibly still-live file (bypassing the trash entirely), so this must not
        // assume deleteFile's own revocation already ran.
        this.revokeAllFileShares(storedFileId);
        if (ownership.hasMetadata()) {
            this.updateCloudUserBytesUsage(authUserId, -ownership.getSizeBytes());
        }
    }

    /**
     * Adjusts {@code authUserId}'s {@link ICloudUser#getCurrentUploadedBytes()} running total by
     * {@code delta} (positive after a successful upload, negative after a successful delete) and
     * persists the change - a single-row {@link DataFactory#update}, not a rewrite of anything
     * else on the account. Clamped at a minimum of {@code 0}: a negative running total would be
     * nonsensical and would under-report usage to {@link CloudUser#isUploadLimitReached}, letting
     * a caller upload past its real quota. A no-op if {@code authUserId} has no {@link CloudUser}
     * record yet (nothing to adjust).
     *
     * @param authUserId the account whose running total to adjust
     * @param delta how many bytes to add (or, if negative, remove) from the running total
     */
    @Override
    public void updateCloudUserBytesUsage(@NonNull final String authUserId, final long delta) {
        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return;

        final ICloudUser existing = cloudUser.get();
        existing.setCurrentUploadedBytes(Math.max(0, existing.getCurrentUploadedBytes() + delta));

        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateCloudUserBytesUsage: failed to persist usage update for " + authUserId, e);
        }
    }

    /**
     * Sets {@code authUserId}'s {@link ICloudUser#getMaxBytesToUpload()} upload quota to {@code
     * bytes} and persists the change - a single-row {@link DataFactory#update}. Clamped at a
     * minimum of {@code 0}. A no-op if {@code authUserId} has no {@link CloudUser} record yet.
     *
     * @param authUserId the account whose quota to change
     * @param bytes the new quota ceiling, in bytes
     */
    @Override
    public void updateCloudUserBytesLimit(@NonNull String authUserId, final long bytes) {

        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return;

        final ICloudUser existing = cloudUser.get();
        existing.setMaxBytesToUpload(Math.max(0, bytes));

        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateCloudUserBytesLimit: failed to persist usage update for " + authUserId, e);
        }

    }

    /**
     * See {@link ICloudUserService#recomputeUploadedBytes}'s Javadoc. Sums {@link
     * StoredFileOwnership#getSizeBytes()} across every row this account still tracks - trashed
     * rows included, via {@link #ownedFileOwnershipsIncludingDeleted(String)} rather than {@link
     * #ownedFileOwnerships(String)}, since a trashed-but-not-yet-purged file still occupies
     * storage (see {@link #deleteFile}'s own Javadoc: trashing alone never decrements the usage
     * total, only {@link #hardDeleteFile} does) - so this recompute must agree with that same
     * accounting rule rather than silently under-counting relative to it.
     */
    @Override
    public long recomputeUploadedBytes(@NonNull final String authUserId) {
        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return 0L;

        final long total = this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(StoredFileOwnership::hasMetadata)
                .mapToLong(StoredFileOwnership::getSizeBytes)
                .sum();

        final ICloudUser existing = cloudUser.get();
        existing.setCurrentUploadedBytes(total);
        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.recomputeUploadedBytes: failed to persist recomputed usage for " + authUserId, e);
        }
        return total;
    }

    /**
     * Deletes every {@link Folder} owned by {@code authUserId}, regardless of nesting depth,
     * by repeatedly deleting whichever folders are currently leaves (no other remaining folder
     * points at them via {@link Folder#getParentFolderId()}) until none are left - the same
     * "must be empty first" constraint {@link #deleteFolder} enforces for a single folder,
     * applied bottom-up across the whole tree instead of requiring the caller to do so one
     * folder at a time.
     *
     * @param authUserId the owning user whose entire folder tree should be deleted
     * @throws IllegalStateException if a cycle is detected among the remaining folders (defense-in-depth;
     *     writes elsewhere already prevent this from occurring)
     */
    private void deleteAllOwnedFolders(final String authUserId) {
        final List<Folder> remaining;
        try {
            remaining = new ArrayList<>(this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .toList());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.deleteAllOwnedFolders: failed to list folders for " + authUserId, e);
        }

        while (!remaining.isEmpty()) {
            final Set<String> parentIds = remaining.stream()
                    .map(Folder::getParentFolderId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            final List<Folder> leaves = remaining.stream()
                    .filter(folder -> !parentIds.contains(folder.getFolderId()))
                    .toList();
            if (leaves.isEmpty()) {
                throw new IllegalStateException(
                        "@CloudUserService.deleteAllOwnedFolders: cycle detected among folders owned by " + authUserId);
            }
            for (final Folder leaf : leaves) {
                try {
                    this.dataFactory.delete(leaf.getFolderId(), Folder.class);
                } catch (final DatabaseClientException e) {
                    throw new RuntimeException("@CloudUserService.deleteAllOwnedFolders: failed to delete folder " + leaf.getFolderId(), e);
                }
                // Item 9 (sharing), fixed 2026-09-02 - see deleteFile's own comment.
                this.revokeAllFolderShares(leaf.getFolderId());
            }
            remaining.removeAll(leaves);
        }
    }

    /**
     * Permanently removes every file and folder currently in {@code authUserId}'s trash - see
     * {@link ICloudUserService#emptyTrash}'s Javadoc. Files go through {@link #hardDeleteFile} (the
     * same permanent-removal primitive {@link #resetCloudUser} uses), bypassing the retention
     * window entirely; folders go through the private {@link #deleteAllTrashedFolders} below.
     */
    @Override
    public void emptyTrash(@NonNull final String authUserId) {
        final List<StoredFileOwnership> trashedFiles = this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(StoredFileOwnership::isDeleted)
                .toList();
        for (final StoredFileOwnership ownership : trashedFiles) {
            this.hardDeleteFile(authUserId, ownership);
        }
        this.deleteAllTrashedFolders(authUserId);
    }

    /**
     * Permanently deletes every currently-trashed {@link Folder} owned by {@code authUserId},
     * leaf-first, the same bottom-up convergence {@link #deleteAllOwnedFolders} uses for a full
     * wipe - but scoped to only the trashed subset, used by {@link #emptyTrash}. <b>Deliberately
     * computes "is this folder a leaf" against every owned folder (trashed and live alike), not
     * just the trashed ones being removed</b> - a trashed folder that still has a <em>live</em>
     * child (e.g. the child was individually restored while its parent wasn't) must never be
     * deleted out from under that child, which would otherwise leave the live child's {@code
     * parentFolderId} pointing at nothing. {@code stillExisting} is kept in sync as trashed leaves
     * are removed each round, so a whole trashed chain (grandparent/parent/child, all trashed)
     * still converges leaf-first exactly like {@link #deleteAllOwnedFolders} does - it is not a
     * static snapshot recomputed from a fixed set, which would otherwise never let an ancestor
     * become eligible once its already-deleted child stopped actually existing.
     *
     * @param authUserId the owning user whose trashed folders should be permanently removed
     * @throws IllegalStateException if a cycle is detected among the remaining trashed folders
     *     (defense-in-depth; writes elsewhere already prevent this from occurring)
     */
    private void deleteAllTrashedFolders(final String authUserId) {
        final List<Folder> stillExisting;
        try {
            stillExisting = new ArrayList<>(this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .toList());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.deleteAllTrashedFolders: failed to list folders for " + authUserId, e);
        }
        final List<Folder> remainingTrashed = stillExisting.stream()
                .filter(Folder::isDeleted)
                .collect(Collectors.toCollection(ArrayList::new));

        while (!remainingTrashed.isEmpty()) {
            final Set<String> occupiedParentIds = stillExisting.stream()
                    .map(Folder::getParentFolderId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            final List<Folder> leaves = remainingTrashed.stream()
                    .filter(folder -> !occupiedParentIds.contains(folder.getFolderId()))
                    .toList();
            if (leaves.isEmpty()) {
                throw new IllegalStateException(
                        "@CloudUserService.deleteAllTrashedFolders: cycle detected among trashed folders owned by " + authUserId);
            }
            for (final Folder leaf : leaves) {
                try {
                    this.dataFactory.delete(leaf.getFolderId(), Folder.class);
                } catch (final DatabaseClientException e) {
                    throw new RuntimeException("@CloudUserService.deleteAllTrashedFolders: failed to delete folder " + leaf.getFolderId(), e);
                }
                // Item 9 (sharing), fixed 2026-09-02 - see deleteFile's own comment.
                this.revokeAllFolderShares(leaf.getFolderId());
            }
            remainingTrashed.removeAll(leaves);
            stillExisting.removeAll(leaves);
        }
    }

    /**
     * Uploads {@code fileName}/{@code content} as a new {@link StoredFile} (a fresh,
     * random id) and tracks it as owned by {@code authUserId} via a single new {@link
     * StoredFileOwnership} row - a plain insert, not a rewrite of any existing data,
     * regardless of how many files {@code authUserId} already owns. Placed at the root.
     *
     * @param authUserId the uploading user's id, tracked as the new file's owner
     * @param fileName the file's name, used to infer its content type and preserved on download
     * @param content the file's raw bytes
     * @return the newly-created {@link StoredFile}
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content) {
        return this.uploadFile(authUserId, fileName, content, null);
    }

    /**
     * Same as {@link #uploadFile(String, String, byte[])}, placing the new file directly into
     * {@code folderId} instead of the root.
     *
     * @param authUserId the uploading user's id, tracked as the new file's owner
     * @param fileName the file's name, used to infer its content type and preserved on download
     * @param content the file's raw bytes
     * @param folderId the folder to place the new file in, or {@code null} for the root
     * @return the newly-created {@link StoredFile}
     * @throws IllegalArgumentException if {@code folderId} is non-null and isn't owned by {@code authUserId}
     * @throws UploadQuotaExceededException if {@code authUserId} has reached its {@link
     *                                       ICloudUser#getMaxBytesToUpload()} upload quota
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName, final byte[] content,
                                  @Nullable final String folderId) {

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        // Checked before requireOwnedFolder/constructing the StoredFile (which DEFLATE-compresses
        // and base64-encodes content up front) - no reason to pay for either on a rejected upload.
        if (cloudUser.isUploadLimitReached(content.length)) {
            recordMetric(MetricsRecorder::recordUploadQuotaRejected);
            throw new UploadQuotaExceededException(
                    authUserId, cloudUser.getCurrentUploadedBytes(), content.length, cloudUser.getMaxBytesToUpload());
        }
        // Item 9 (sharing): deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final StoredFile storedFile = new StoredFile(UUID.randomUUID().toString(), fileName, content);

        try {
            this.fileFactory.upload(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
        }

        try {
            this.dataFactory.register(StoredFileOwnership.of(authUserId, storedFile, folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.uploadFile: failed to track ownership of " + storedFile.fileId() + " for " + authUserId, e
            );
        }

        this.updateCloudUserBytesUsage(authUserId, content.length);

        return storedFile;
    }

    /** Default lifetime of a presigned upload/download URL - long enough for a slow connection on a large file, short enough that a leaked URL doesn't stay usable indefinitely. */
    private static final Duration PRESIGNED_URL_EXPIRY = Duration.ofMinutes(15);

    /** {@inheritDoc} */
    @NonNull
    @Override
    public PresignedUploadTicket beginPresignedUpload(@NonNull final String authUserId, @NonNull final String fileName,
                                                        final long sizeBytes, @Nullable final String folderId) {
        final PresignedTransferService presignedTransferService = requirePresignedTransferService();

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        // Soft check only - the client's own declared sizeBytes, not yet verified against the
        // real uploaded object (that happens in completePresignedUpload, once it's knowable).
        if (cloudUser.isUploadLimitReached(sizeBytes)) {
            recordMetric(MetricsRecorder::recordUploadQuotaRejected);
            throw new UploadQuotaExceededException(
                    authUserId, cloudUser.getCurrentUploadedBytes(), sizeBytes, cloudUser.getMaxBytesToUpload());
        }
        // Item 9 (sharing): deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final String fileId = UUID.randomUUID().toString();
        return new PresignedUploadTicket(fileId, presignedTransferService.presignUpload(fileId, sizeBytes, PRESIGNED_URL_EXPIRY));
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public StoredFileSummary completePresignedUpload(@NonNull final String authUserId, @NonNull final String fileId, @NonNull final String fileName,
                                                       @NonNull final String checksumSha256Hex, @Nullable final String folderId) {
        final PresignedTransferService presignedTransferService = requirePresignedTransferService();

        final long realSizeBytes;
        try {
            realSizeBytes = presignedTransferService.headObjectContentLength(fileId);
        } catch (final ObjectStorageException e) {
            throw new IllegalArgumentException("@CloudUserService.completePresignedUpload: no object uploaded yet under '" + fileId + "'", e);
        }

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        if (cloudUser.isUploadLimitReached(realSizeBytes)) {
            deleteOrphanedPresignedObjectQuietly(presignedTransferService, fileId);
            recordMetric(MetricsRecorder::recordUploadQuotaRejected);
            throw new UploadQuotaExceededException(
                    authUserId, cloudUser.getCurrentUploadedBytes(), realSizeBytes, cloudUser.getMaxBytesToUpload());
        }
        // Item 9 (sharing): deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final Instant now = Instant.now();
        final StoredFile storedFile = new StoredFile(
                fileId, fileName, realSizeBytes, new FileChecksum(HashAlgorithm.SHA_256, checksumSha256Hex), now, now, fileId
        );

        try {
            this.dataFactory.register(storedFile);
        } catch (final DatabaseClientException | KeyWrapException e) {
            deleteOrphanedPresignedObjectQuietly(presignedTransferService, fileId);
            throw new RuntimeException("@CloudUserService.completePresignedUpload: failed to register '" + fileId + "'", e);
        }

        try {
            this.dataFactory.register(StoredFileOwnership.of(authUserId, storedFile, folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.completePresignedUpload: failed to track ownership of " + fileId + " for " + authUserId, e
            );
        }

        this.updateCloudUserBytesUsage(authUserId, realSizeBytes);
        recordMetric(MetricsRecorder::recordUploadSuccess);

        return new StoredFileSummary(fileId, fileName, storedFile.contentType(), realSizeBytes,
                now.toEpochMilli(), now.toEpochMilli(), folderId);
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public PresignedDownload beginPresignedDownload(@NonNull final String authUserId, @NonNull final String storedFileId) {
        final PresignedTransferService presignedTransferService = requirePresignedTransferService();

        final StoredFileOwnership ownership = this.tryOwnedFile(authUserId, storedFileId)
                .orElseGet(() -> this.requireSharedFileAccess(authUserId, storedFileId));
        if (ownership.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.beginPresignedDownload: " + authUserId + " does not own or have shared access to " + storedFileId);
        }

        final StoredFile file;
        try {
            file = this.dataFactory.findById(storedFileId, StoredFile.class)
                    .orElseThrow(() -> new IllegalStateException("@CloudUserService.beginPresignedDownload: owned file not found: " + storedFileId));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.beginPresignedDownload: failed to look up " + storedFileId, e);
        }

        // Only a direct-transfer file's content is plaintext-in-S3 and safe to hand a client a raw
        // link to - an inline or app-encrypted-S3 file's bytes would be ciphertext the client has
        // no DEK/KEK access to decrypt, so this reuses the same "not available, fall back" signal
        // beginPresignedUpload/completePresignedUpload use when nothing is configured at all; the
        // caller doesn't need to distinguish why, only that GET /files/{id}/content is the right
        // route for this particular file instead.
        if (!file.isDirectTransfer()) {
            throw new PresignedTransferUnavailableException();
        }

        return presignedTransferService.presignDownload(file.objectStorageKey(), PRESIGNED_URL_EXPIRY);
    }

    /**
     * @throws PresignedTransferUnavailableException if {@link #presignedTransferService} is {@code null}
     */
    private PresignedTransferService requirePresignedTransferService() {
        if (this.presignedTransferService == null) {
            throw new PresignedTransferUnavailableException();
        }
        return this.presignedTransferService;
    }

    /**
     * Best-effort delete of a presigned-upload object that turned out to violate a constraint only
     * checkable at completion time (the account's quota) - a failure here is logged, not thrown,
     * so it never masks the real {@link UploadQuotaExceededException}/database failure the caller
     * is already about to throw.
     */
    private static void deleteOrphanedPresignedObjectQuietly(final PresignedTransferService presignedTransferService, final String fileId) {
        try {
            presignedTransferService.deleteObject(fileId);
        } catch (final ObjectStorageException cleanupFailed) {
            CloudDriver.getInstance().getLogger().log(
                    Level.WARNING,
                    "@CloudUserService: failed to delete orphaned presigned-upload object for file '" + fileId + "'", cleanupFailed
            );
        }
    }

    /**
     * Lists every {@link StoredFile} currently tracked as belonging to {@code authUserId}.
     *
     * <p><strong>Trade-off:</strong> neither {@link DataFactory} nor the underlying
     * database-driver expose a lookup by a non-primary-key field, so this scans and
     * decrypts every {@link StoredFileOwnership} row across <em>every</em> user - via
     * {@link DataFactory#getEntities} - and filters down to {@code authUserId} in
     * memory. Each row is tiny (two ids plus a folder id) and decrypted concurrently
     * (see {@code EntityDatabaseClient#retrieveAll}), so this is still far cheaper than
     * the old single-blob-of-10,000-ids design on the read side, and this method is
     * called far less often than {@link #uploadFile}/{@link #deleteFile}. If the number
     * of ownership rows system-wide grows large enough for this scan itself to matter,
     * the fix is a proper indexed query (e.g. {@code WHERE authUserId = ?}) exposed
     * from {@code database-driver-v2} up through {@code DataFactory} - not something
     * available today.
     *
     * @param authUserId the user whose files should be listed
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFile> listFiles(@NonNull final String authUserId) {
        final List<String> ownedFileIds = this.ownedFileIds(authUserId);
        try {
            return this.fileFactory.download(ownedFileIds.toArray(new String[0]));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.listFiles: failed to download files for " + authUserId, e);
        }
    }

    /**
     * Same as {@link #listFiles(String)}, but paired with each file's current folder
     * placement - see that method's Javadoc for the same full-scan trade-off this shares.
     *
     * @param authUserId the user whose files should be listed
     * @return every {@link StoredFile} currently tracked as belonging to {@code authUserId}, each paired with its folder
     */
    @NonNull
    @Override
    public List<FileWithFolder> listFilesWithFolder(@NonNull final String authUserId) {
        return this.resolveFilesWithFolder(this.ownedFileOwnerships(authUserId));
    }

    /**
     * Same as {@link #listFilesWithFolder(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the user whose files should be listed
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return every {@link StoredFile} directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NonNull
    @Override
    public List<FileWithFolder> listFilesWithFolder(@NonNull final String authUserId, @Nullable final String folderId) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        return this.resolveFilesWithFolder(filtered);
    }

    /**
     * Downloads every file in {@code ownerships} and pairs each with its recorded {@link StoredFileOwnership#getFolderId()}.
     *
     * @param ownerships the ownership rows whose files should be downloaded and paired
     * @return each downloaded {@link StoredFile}, paired with its recorded folder placement
     */
    private List<FileWithFolder> resolveFilesWithFolder(final List<StoredFileOwnership> ownerships) {
        final Map<String, String> folderIdByFileId = new HashMap<>();
        ownerships.forEach(ownership -> folderIdByFileId.put(ownership.getStoredFileId(), ownership.getFolderId()));

        final String[] ids = ownerships.stream().map(StoredFileOwnership::getStoredFileId).toArray(String[]::new);
        try {
            return this.fileFactory.download(ids).stream()
                    .map(file -> new FileWithFolder(file, folderIdByFileId.get(file.fileId())))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.resolveFilesWithFolder: failed to download files", e);
        }
    }

    /**
     * Same as {@link #listFilesWithFolder(String)}, but without any file's content - just each
     * {@link StoredFileOwnership} row's own recorded name/size/content-type/timestamps/folder.
     * Unlike {@link #listFilesWithFolder(String)}/{@link #listFiles(String)}, this never calls
     * {@link FileFactory#download} at all: every {@link StoredFileOwnership} row already carries
     * its own file's descriptive fields (captured once, at upload time - see {@link
     * StoredFileOwnership#hasMetadata()}), so building a listing is just reading rows this method
     * already scans regardless. This is the efficient path for rendering a file list; reach for
     * {@link #listFilesWithFolder(String)} only once a specific file's actual content is needed.
     *
     * @param authUserId the user whose files should be listed
     * @return a {@link StoredFileSummary} for every file currently tracked as belonging to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFileSummary> listFileSummaries(@NonNull final String authUserId) {
        // Item 9 (sharing): deliberately does NOT include files shared with authUserId - a caller
        // listing "my files" should never be silently surprised by someone else's file appearing
        // here. Use listSharedWithMe(authUserId) for the separate, explicit "shared with me" list.
        return this.resolveFileSummaries(this.ownedFileOwnerships(authUserId));
    }

    /**
     * Same as {@link #listFileSummaries(String)}, filtered to only the files directly inside {@code folderId}.
     *
     * @param authUserId the user whose files should be listed
     * @param folderId the folder to list files from, or {@code null} for the root
     * @return a {@link StoredFileSummary} for every file directly inside {@code folderId} (or the root) that belongs to {@code authUserId}
     */
    @NonNull
    @Override
    public List<StoredFileSummary> listFileSummaries(@NonNull final String authUserId, @Nullable final String folderId) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        return this.resolveFileSummaries(filtered);
    }

    /**
     * {@link #resolveFileSummary}, applied to every entry.
     *
     * @param ownerships the ownership rows to summarize
     * @return one {@link StoredFileSummary} per entry in {@code ownerships}
     */
    private List<StoredFileSummary> resolveFileSummaries(final List<StoredFileOwnership> ownerships) {
        return ownerships.stream().map(this::resolveFileSummary).toList();
    }

    /**
     * See {@link ICloudUserService#listFileSummariesPage}'s Javadoc. Resolves the same
     * full-scan/filter list {@link #listFileSummaries(String, String)} does, sorts it by {@link
     * StoredFileSummary#fileId()}, then slices out one page via {@link #paginate}.
     */
    @NonNull
    @Override
    public CursorPage<StoredFileSummary> listFileSummariesPage(@NonNull final String authUserId, @Nullable final String folderId,
                                                                @Nullable final String cursor, final int limit) {
        final List<StoredFileOwnership> filtered = this.ownedFileOwnerships(authUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .toList();
        final List<StoredFileSummary> sorted = this.resolveFileSummaries(filtered).stream()
                .sorted(Comparator.comparing(StoredFileSummary::fileId))
                .toList();
        return paginate(sorted, cursor, limit, StoredFileSummary::fileId);
    }

    /**
     * Generic keyset-pagination slice over an already-fully-materialized, ascending-{@code
     * keyExtractor}-sorted list - the same "{@code WHERE key > cursor ORDER BY key LIMIT limit}"
     * shape {@code DatabaseBackupScheduler#fetchBatch} applies at the SQL level, applied here at
     * the application level instead (see {@link CursorPage}'s Javadoc for why a real SQL-level
     * cursor isn't available for these owner-scoped, encrypted rows).
     *
     * @param sorted       the full result set, already sorted ascending by {@code keyExtractor}
     * @param cursor       the previous page's {@link CursorPage#nextCursor()}, or {@code null} for the first page
     * @param limit        the maximum number of entries to return; must be positive
     * @param keyExtractor extracts the stable sort/cursor key from one element
     */
    private static <T> CursorPage<T> paginate(final List<T> sorted, @Nullable final String cursor,
                                               final int limit, final java.util.function.Function<T, String> keyExtractor) {
        if (limit <= 0) {
            throw new IllegalArgumentException("@CloudUserService.paginate: limit must be positive, was " + limit);
        }
        final List<T> afterCursor = cursor == null
                ? sorted
                : sorted.stream().filter(item -> keyExtractor.apply(item).compareTo(cursor) > 0).toList();
        final boolean hasMore = afterCursor.size() > limit;
        final List<T> page = afterCursor.subList(0, Math.min(limit, afterCursor.size()));
        final String nextCursor = hasMore ? keyExtractor.apply(page.getLast()) : null;
        return new CursorPage<>(page, nextCursor);
    }

    /**
     * Builds one {@link StoredFileSummary} straight from {@code ownership}'s own fields - unless
     * it predates metadata capture ({@link StoredFileOwnership#hasMetadata()} {@code false}), in
     * which case this falls back to downloading the full {@link StoredFile} exactly once,
     * persisting a {@link StoredFileOwnership#withMetadata(StoredFile)} copy so every later call
     * for this same row takes the fast, no-download path.
     *
     * @param ownership the ownership row to summarize
     * @return the resulting {@link StoredFileSummary}
     */
    private StoredFileSummary resolveFileSummary(final StoredFileOwnership ownership) {
        final String storedFileId = ownership.getStoredFileId();
        StoredFileOwnership resolved = ownership;
        if (!resolved.hasMetadata()) {
            final StoredFile file;
            try {
                file = this.fileFactory.findById(storedFileId)
                        .orElseThrow(() -> new IllegalStateException(
                                "@CloudUserService.resolveFileSummary: owned file not found: " + storedFileId));
            } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
                throw new RuntimeException(
                        "@CloudUserService.resolveFileSummary: failed to backfill metadata for " + storedFileId, e);
            }
            resolved = resolved.withMetadata(file);
            try {
                this.dataFactory.update(resolved);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException(
                        "@CloudUserService.resolveFileSummary: failed to persist backfilled metadata for " + storedFileId, e);
            }
        }
        return new StoredFileSummary(resolved.getStoredFileId(), resolved.getFileName(), resolved.getContentType(),
                resolved.getSizeBytes(), resolved.getCreatedAtEpochMilli(), resolved.getUpdatedAtEpochMilli(), resolved.getFolderId());
    }

    /**
     * Fetches one file's full content, paired with its current folder placement - unlike {@link
     * #listFileSummaries(String)}, this does pay the decrypt/decompress cost {@link
     * FileFactory#findById} incurs, the same cost {@link #listFilesWithFolder(String)} pays for
     * every entry it returns; only reach for this once a specific file's actual content is needed
     * (e.g. the user opened/downloaded it).
     *
     * <p><b>Share-aware, deliberately - the one read path in this class that is.</b> If {@code
     * authUserId} doesn't own {@code storedFileId} outright, this falls back to {@link
     * #requireSharedFileAccess}, which honors both a direct {@link SharedFileGrant} on this file
     * and an inherited {@link SharedFolderGrant} on any of its ancestor folders (see item 9's
     * design in {@code architecture/SERVICES.md} and this project's {@code CLAUDE.md} for the full
     * "which operations honor a share" table). This is the <em>only</em> place sharing is honored -
     * every mutating method below ({@link #moveFile}, {@link #deleteFile}, folder methods, etc.)
     * deliberately keeps calling {@link #requireOwnedFile}/{@link #requireOwnedFolder} directly,
     * never this shared-access fallback, since a read-only grant must never permit mutation. Both
     * {@code DefaultRestFactory}'s {@code GET /files/{id}} and {@code GET /files/{id}/content}
     * routes call this same method, so a grantee reaches a shared file's content through the exact
     * same routes an owner does - no separate "shared file" route exists.
     *
     * @param authUserId the requesting user's id - checked against the ownership record first, then against any share
     * @param storedFileId the file to fetch
     * @return the file's full content, paired with its current folder
     * @throws IllegalArgumentException if {@code storedFileId} isn't owned by {@code authUserId}
     *                                   and isn't shared with {@code authUserId} either (directly,
     *                                   or via an ancestor folder)
     */
    @NonNull
    @Override
    public FileWithFolder getFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        final StoredFileOwnership ownership = this.tryOwnedFile(authUserId, storedFileId)
                .orElseGet(() -> this.requireSharedFileAccess(authUserId, storedFileId));
        if (ownership.isDeleted()) {
            // Hidden from a normal fetch the same "don't confirm existence" way an unowned/unshared
            // file already is - a trashed file is only reachable via listDeletedFiles/restoreFile,
            // both owner-only, so a grantee never sees a trashed shared file at all.
            throw new IllegalArgumentException("@CloudUserService.getFile: " + authUserId + " does not own or have shared access to " + storedFileId);
        }
        try {
            final StoredFile file = this.fileFactory.findById(storedFileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "@CloudUserService.getFile: owned file not found: " + storedFileId));
            return new FileWithFolder(file, ownership.getFolderId());
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.getFile: failed to download " + storedFileId, e);
        }
    }

    /**
     * Grants {@code granteeEmail}'s account read-only access to {@code fileId} - see {@link
     * ICloudUserService#shareFile}'s Javadoc. Owner-only: sharing is itself treated as a mutation
     * of the file's grant state, so this calls {@link #requireOwnedFile} directly, never {@link
     * #requireSharedFileAccess} - a grantee can never re-share what was shared with them.
     */
    @Override
    public void shareFile(@NonNull final String ownerAuthUserId, @NonNull final String fileId, @NonNull final String granteeEmail) {
        final StoredFileOwnership ownership = this.requireOwnedFile(ownerAuthUserId, fileId);
        if (ownership.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.shareFile: cannot share trashed file " + fileId);
        }
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        if (granteeAuthUserId.equals(ownerAuthUserId)) {
            throw new IllegalArgumentException("@CloudUserService.shareFile: cannot share a file with its own owner");
        }
        try {
            this.dataFactory.register(new SharedFileGrant(granteeAuthUserId, fileId, ownerAuthUserId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.shareFile: failed to persist grant for " + fileId + " to " + granteeEmail, e);
        }
    }

    /**
     * Revokes a previously-granted share of {@code fileId} from {@code granteeEmail} - see {@link
     * ICloudUserService#revokeFileShare}'s Javadoc. Owner-only, same reasoning as {@link
     * #shareFile}.
     */
    @Override
    public void revokeFileShare(@NonNull final String ownerAuthUserId, @NonNull final String fileId, @NonNull final String granteeEmail) {
        this.requireOwnedFile(ownerAuthUserId, fileId);
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        final String key = SharedFileGrant.compositeKey(granteeAuthUserId, fileId);
        try {
            if (this.dataFactory.findById(key, SharedFileGrant.class).isEmpty()) {
                return;
            }
            this.dataFactory.delete(key, SharedFileGrant.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokeFileShare: failed to revoke grant for " + fileId + " from " + granteeEmail, e);
        }
    }

    /**
     * Lists every file directly shared with {@code authUserId} - see {@link
     * ICloudUserService#listSharedWithMe}'s Javadoc. Resolves each grant's underlying {@link
     * StoredFileOwnership} row (owned by the granter, keyed via {@link
     * StoredFileOwnership#compositeKey}) and reuses {@link #resolveFileSummary} for the same
     * lazy-metadata-backfill behavior {@link #listFileSummaries(String)} already has.
     */
    @NonNull
    @Override
    public List<SharedFileSummary> listSharedWithMe(@NonNull final String authUserId) {
        final List<SharedFileGrant> grants;
        try {
            grants = this.dataFactory.getEntities(SharedFileGrant.class).stream()
                    .filter(grant -> grant.getGranteeAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listSharedWithMe: failed to list file grants for " + authUserId, e);
        }
        return grants.stream()
                .map(grant -> {
                    final Optional<StoredFileOwnership> ownership;
                    try {
                        ownership = this.dataFactory.findById(
                                StoredFileOwnership.compositeKey(grant.getOwnerAuthUserId(), grant.getStoredFileId()), StoredFileOwnership.class);
                    } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                        throw new RuntimeException("@CloudUserService.listSharedWithMe: failed to resolve ownership for grant " + grant, e);
                    }
                    // A share whose file the owner has since trashed is hidden the same way a
                    // trashed owned file is hidden from listFileSummaries - not surfaced as a
                    // broken/error entry.
                    return ownership.filter(candidate -> !candidate.isDeleted())
                            .map(candidate -> new SharedFileSummary(
                                    this.resolveFileSummary(candidate),
                                    this.resolveEmailForAuthUserId(grant.getOwnerAuthUserId()).orElse(grant.getOwnerAuthUserId())));
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Grants {@code granteeEmail}'s account read-only access to {@code folderId} - see {@link
     * ICloudUserService#shareFolder}'s Javadoc. Owner-only, same reasoning as {@link #shareFile}.
     */
    @Override
    public void shareFolder(@NonNull final String ownerAuthUserId, @NonNull final String folderId, @NonNull final String granteeEmail) {
        final Folder folder = this.requireOwnedFolder(ownerAuthUserId, folderId);
        if (folder.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.shareFolder: cannot share trashed folder " + folderId);
        }
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        if (granteeAuthUserId.equals(ownerAuthUserId)) {
            throw new IllegalArgumentException("@CloudUserService.shareFolder: cannot share a folder with its own owner");
        }
        try {
            this.dataFactory.register(new SharedFolderGrant(granteeAuthUserId, folderId, ownerAuthUserId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.shareFolder: failed to persist grant for " + folderId + " to " + granteeEmail, e);
        }
    }

    /**
     * Revokes a previously-granted share of {@code folderId} from {@code granteeEmail} - see
     * {@link ICloudUserService#revokeFolderShare}'s Javadoc. Owner-only, same reasoning as {@link
     * #shareFile}.
     */
    @Override
    public void revokeFolderShare(@NonNull final String ownerAuthUserId, @NonNull final String folderId, @NonNull final String granteeEmail) {
        this.requireOwnedFolder(ownerAuthUserId, folderId);
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        final String key = SharedFolderGrant.compositeKey(granteeAuthUserId, folderId);
        try {
            if (this.dataFactory.findById(key, SharedFolderGrant.class).isEmpty()) {
                return;
            }
            this.dataFactory.delete(key, SharedFolderGrant.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokeFolderShare: failed to revoke grant for " + folderId + " from " + granteeEmail, e);
        }
    }

    /**
     * Lists every folder directly shared with {@code authUserId} - see {@link
     * ICloudUserService#listSharedFoldersWithMe}'s Javadoc.
     */
    @NonNull
    @Override
    public List<SharedFolderSummary> listSharedFoldersWithMe(@NonNull final String authUserId) {
        final List<SharedFolderGrant> grants;
        try {
            grants = this.dataFactory.getEntities(SharedFolderGrant.class).stream()
                    .filter(grant -> grant.getGranteeAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listSharedFoldersWithMe: failed to list folder grants for " + authUserId, e);
        }
        return grants.stream()
                .map(grant -> {
                    final Optional<Folder> folder;
                    try {
                        folder = this.dataFactory.findById(grant.getFolderId(), Folder.class);
                    } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
                        throw new RuntimeException("@CloudUserService.listSharedFoldersWithMe: failed to resolve folder for grant " + grant, e);
                    }
                    return folder.filter(candidate -> !candidate.isDeleted())
                            .map(candidate -> new SharedFolderSummary(
                                    candidate, this.resolveEmailForAuthUserId(grant.getOwnerAuthUserId()).orElse(grant.getOwnerAuthUserId())));
                })
                .flatMap(Optional::stream)
                .toList();
    }

    /**
     * Lists the email addresses of every account {@code fileId} is currently shared with - see
     * {@link ICloudUserService#listFileShares}'s Javadoc. Owner-only, checked via {@link
     * #requireOwnedFile} the same way {@link #shareFile}/{@link #revokeFileShare} already are.
     */
    @NonNull
    @Override
    public List<String> listFileShares(@NonNull final String ownerAuthUserId, @NonNull final String fileId) {
        this.requireOwnedFile(ownerAuthUserId, fileId);
        try {
            return this.dataFactory.getEntities(SharedFileGrant.class).stream()
                    .filter(grant -> grant.getOwnerAuthUserId().equals(ownerAuthUserId) && grant.getStoredFileId().equals(fileId))
                    .map(SharedFileGrant::getGranteeAuthUserId)
                    .map(this::resolveEmailForAuthUserId)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFileShares: failed to list shares for " + fileId, e);
        }
    }

    /**
     * Lists the email addresses of every account {@code folderId} is currently shared with - see
     * {@link ICloudUserService#listFolderShares}'s Javadoc. Owner-only, checked via {@link
     * #requireOwnedFolder} the same way {@link #shareFolder}/{@link #revokeFolderShare} already are.
     */
    @NonNull
    @Override
    public List<String> listFolderShares(@NonNull final String ownerAuthUserId, @NonNull final String folderId) {
        this.requireOwnedFolder(ownerAuthUserId, folderId);
        try {
            return this.dataFactory.getEntities(SharedFolderGrant.class).stream()
                    .filter(grant -> grant.getOwnerAuthUserId().equals(ownerAuthUserId) && grant.getFolderId().equals(folderId))
                    .map(SharedFolderGrant::getGranteeAuthUserId)
                    .map(this::resolveEmailForAuthUserId)
                    .flatMap(Optional::stream)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFolderShares: failed to list shares for " + folderId, e);
        }
    }

    /**
     * Counts the distinct files {@code authUserId} owns with at least one active share - see
     * {@link ICloudUserService#countFilesSharedByMe}'s Javadoc. Same full-{@link
     * SharedFileGrant}-table-scan trade-off {@link #listFileShares}/{@link #listSharedWithMe}
     * already accept elsewhere in this class.
     */
    @Override
    public int countFilesSharedByMe(@NonNull final String authUserId) {
        try {
            return (int) this.dataFactory.getEntities(SharedFileGrant.class).stream()
                    .filter(grant -> grant.getOwnerAuthUserId().equals(authUserId))
                    .map(SharedFileGrant::getStoredFileId)
                    .distinct()
                    .count();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.countFilesSharedByMe: failed to count shares for " + authUserId, e);
        }
    }

    /**
     * Resolves {@code authUserId} back to its account's email address - the reverse of {@link
     * #resolveGranteeAuthUserId}, used by {@link #listFileShares}/{@link #listFolderShares} to
     * display a grant's grantee as an email rather than a raw id. Same full-{@code AuthUser}-scan
     * trade-off {@link #getCloudUserByEmail(String)} already accepts. {@link Optional#empty()}
     * (rather than a thrown exception) if the account no longer exists - a grant whose grantee
     * account was since deleted is simply omitted from the caller's result, not surfaced as an error.
     */
    private Optional<String> resolveEmailForAuthUserId(final String authUserId) {
        try {
            return this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(user -> user.getId().equals(authUserId))
                    .map(AuthUser::getEmailAddress)
                    .findFirst();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.resolveEmailForAuthUserId: failed to resolve email for " + authUserId, e);
        }
    }

    /**
     * Permanently revokes every outstanding {@link SharedFileGrant} on {@code fileId}, regardless
     * of grantee - called from every place a file is deleted (soft, via {@link #deleteFile}, or
     * permanent, via {@link #hardDeleteFile}), added 2026-09-02 to fix a real bug: {@link
     * CloudUserService#getFile}'s {@code ownership.isDeleted()} check already blocked a grantee's
     * <em>access</em> to a trashed file, but left the grant row itself dangling - so restoring the
     * file later (via {@link #restoreFile}) silently re-granted every previously-shared recipient
     * access again, without the owner ever choosing to re-share. Explicitly deleting the grant here
     * closes that gap: a restored file starts back at "not shared with anyone," matching what an
     * owner who deleted a shared file would actually expect. Idempotent (a no-op if no grants
     * exist) - safe to call from multiple delete paths that might already have run this.
     *
     * @param fileId the file whose outstanding shares (if any) to revoke
     */
    private void revokeAllFileShares(final String fileId) {
        final List<SharedFileGrant> grants;
        try {
            grants = this.dataFactory.getEntities(SharedFileGrant.class).stream()
                    .filter(grant -> grant.getStoredFileId().equals(fileId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokeAllFileShares: failed to list shares of " + fileId, e);
        }
        for (final SharedFileGrant grant : grants) {
            try {
                this.dataFactory.delete(SharedFileGrant.compositeKey(grant.getGranteeAuthUserId(), fileId), SharedFileGrant.class);
            } catch (final DatabaseClientException e) {
                throw new RuntimeException("@CloudUserService.revokeAllFileShares: failed to revoke a share of " + fileId, e);
            }
        }
    }

    /**
     * Permanently revokes every outstanding {@link SharedFolderGrant} on {@code folderId} - the
     * folder-level counterpart to {@link #revokeAllFileShares}, called from every place a folder is
     * deleted (soft, via {@link #deleteFolder}, or permanent, via {@link #deleteAllOwnedFolders}/
     * {@link #deleteAllTrashedFolders}), for the same reason. Does <b>not</b> revoke a direct {@link
     * SharedFileGrant} on a file nested inside {@code folderId} - those are tracked independently
     * (see {@link ICloudUserService#revokeFolderShare}'s own Javadoc for the same distinction) and
     * are already covered separately, since any file actually being permanently removed goes
     * through {@link #revokeAllFileShares} itself via {@link #hardDeleteFile}.
     *
     * @param folderId the folder whose outstanding shares (if any) to revoke
     */
    private void revokeAllFolderShares(final String folderId) {
        final List<SharedFolderGrant> grants;
        try {
            grants = this.dataFactory.getEntities(SharedFolderGrant.class).stream()
                    .filter(grant -> grant.getFolderId().equals(folderId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokeAllFolderShares: failed to list shares of " + folderId, e);
        }
        for (final SharedFolderGrant grant : grants) {
            try {
                this.dataFactory.delete(SharedFolderGrant.compositeKey(grant.getGranteeAuthUserId(), folderId), SharedFolderGrant.class);
            } catch (final DatabaseClientException e) {
                throw new RuntimeException("@CloudUserService.revokeAllFolderShares: failed to revoke a share of " + folderId, e);
            }
        }
    }

    /**
     * Resolves {@code email} to a registered account's {@code authUserId}, via the existing {@link
     * #getCloudUserByEmail(String)} lookup - reused rather than re-implemented, since an {@link
     * ICloudUser}'s {@link ICloudUser#getAuthUserId()} already equals the {@link
     * de.lino.cloud.api.jwt.user.AuthUser#getId()} sharing needs.
     *
     * @throws GranteeAccountNotFoundException if no account is registered under {@code email} -
     *     deliberately its own exception type, not a plain {@link IllegalArgumentException} (see
     *     that class's own Javadoc for why: {@code DefaultRestFactory#folderFailureOrPropagate}
     *     would otherwise collapse this into the same generic "No StoredFile/Folder with id ..."
     *     message every other {@link IllegalArgumentException} on the share routes maps to, hiding
     *     that the grantee address - not the file/folder - was the actual problem, a real bug
     *     confirmed 2026-09-02)
     */
    private String resolveGranteeAuthUserId(final String email) {
        return this.getCloudUserByEmail(email)
                .map(ICloudUser::getAuthUserId)
                .orElseThrow(() -> new GranteeAccountNotFoundException(email));
    }

    /**
     * Non-throwing counterpart to {@link #requireOwnedFile}, used by {@link #getFile} to first
     * check plain ownership before falling back to {@link #requireSharedFileAccess}.
     */
    private Optional<StoredFileOwnership> tryOwnedFile(final String authUserId, final String storedFileId) {
        final String ownershipKey = StoredFileOwnership.compositeKey(authUserId, storedFileId);
        try {
            return this.dataFactory.findById(ownershipKey, StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.tryOwnedFile: failed to look up ownership record " + ownershipKey, e);
        }
    }

    /**
     * Resolves read access to {@code storedFileId} for a non-owning {@code authUserId}, honoring
     * either a direct {@link SharedFileGrant} on this exact file or an inherited {@link
     * SharedFolderGrant} on any ancestor folder the file currently sits in (see {@link
     * SharedFolderGrant}'s own Javadoc for why a folder share implies access to everything nested
     * inside it). Called only from {@link #getFile} - every mutating method must keep calling
     * {@link #requireOwnedFile} directly instead.
     *
     * <p><b>Cost, documented:</b> since {@code authUserId} isn't the owner, this first has to
     * discover who <em>is</em> - there is no O(1) "find the ownership row for this file id,
     * regardless of owner" lookup (a {@link StoredFileOwnership} row is keyed on grantee, not
     * file, the same "no lookup by non-primary-key field" limitation {@link #listFiles}'s own
     * Javadoc already documents and accepts). This is only paid on the shared-access path -
     * {@link #getFile} skips it entirely for an owner - and only once per call, not once per
     * folder-ancestry step.
     *
     * @throws IllegalArgumentException if {@code storedFileId} has no owner on record, or isn't
     *                                   shared with {@code authUserId} either directly or via an
     *                                   ancestor folder
     */
    private StoredFileOwnership requireSharedFileAccess(final String authUserId, final String storedFileId) {
        final StoredFileOwnership ownerOwnership;
        try {
            ownerOwnership = this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getStoredFileId().equals(storedFileId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "@CloudUserService.requireSharedFileAccess: no such file " + storedFileId));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireSharedFileAccess: failed to resolve owner of " + storedFileId, e);
        }

        try {
            if (this.dataFactory.findById(SharedFileGrant.compositeKey(authUserId, storedFileId), SharedFileGrant.class).isPresent()) {
                return ownerOwnership;
            }
            String currentFolderId = ownerOwnership.getFolderId();
            while (currentFolderId != null) {
                if (this.dataFactory.findById(SharedFolderGrant.compositeKey(authUserId, currentFolderId), SharedFolderGrant.class).isPresent()) {
                    return ownerOwnership;
                }
                currentFolderId = this.dataFactory.findById(currentFolderId, Folder.class)
                        .map(Folder::getParentFolderId)
                        .orElse(null);
            }
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireSharedFileAccess: failed checking share grants for " + storedFileId, e);
        }

        throw new IllegalArgumentException(
                "@CloudUserService.requireSharedFileAccess: " + storedFileId + " is not owned by or shared with " + authUserId);
    }

    /**
     * Resolves share-based read access to {@code folderId} for a non-owning {@code authUserId} -
     * the folder-browsing counterpart to {@link #requireSharedFileAccess}, added 2026-09-02 for
     * {@link #listSharedFolderContents}. Checks {@code folderId} itself first, then walks up its
     * ancestor chain via {@link Folder#getParentFolderId()} the exact same way {@link
     * #requireSharedFileAccess} does for a file's containing folder - so a share on an ancestor
     * folder covers browsing into any of its descendants too, not just the exact folder it was
     * granted on.
     *
     * @throws IllegalArgumentException if {@code folderId} isn't shared with {@code authUserId},
     *                                   directly or via any ancestor
     */
    private void requireSharedFolderAccess(final String authUserId, final String folderId) {
        try {
            String currentFolderId = folderId;
            while (currentFolderId != null) {
                if (this.dataFactory.findById(SharedFolderGrant.compositeKey(authUserId, currentFolderId), SharedFolderGrant.class).isPresent()) {
                    return;
                }
                currentFolderId = this.dataFactory.findById(currentFolderId, Folder.class)
                        .map(Folder::getParentFolderId)
                        .orElse(null);
            }
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireSharedFolderAccess: failed checking share grants for " + folderId, e);
        }
        throw new IllegalArgumentException(
                "@CloudUserService.requireSharedFolderAccess: " + folderId + " is not owned by or shared with " + authUserId);
    }

    /**
     * Lists the non-trashed files/subfolders directly inside {@code folderId} for a caller reaching
     * it via ownership or a share - see {@link ICloudUserService#listSharedFolderContents}'s own
     * Javadoc. Resolves {@code folderId}'s actual owner (not necessarily {@code authUserId}) and
     * scans that owner's own rows for children, the same "resolve the real owner first, since a
     * grantee has no O(1) lookup of their own" cost {@link #requireSharedFileAccess} already
     * documents and accepts.
     */
    @NonNull
    @Override
    public SharedFolderContents listSharedFolderContents(@NonNull final String authUserId, @NonNull final String folderId) {
        final Folder folder;
        final List<Folder> allFolders;
        try {
            folder = this.dataFactory.findById(folderId, Folder.class)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "@CloudUserService.listSharedFolderContents: no such folder " + folderId));
            allFolders = this.dataFactory.getEntities(Folder.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listSharedFolderContents: failed to look up " + folderId, e);
        }
        if (folder.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.listSharedFolderContents: " + folderId + " is trashed");
        }
        if (!folder.getOwnerId().equals(authUserId)) {
            this.requireSharedFolderAccess(authUserId, folderId);
        }
        final String ownerAuthUserId = folder.getOwnerId();

        final List<StoredFileSummary> files = this.ownedFileOwnerships(ownerAuthUserId).stream()
                .filter(ownership -> Objects.equals(ownership.getFolderId(), folderId))
                .map(this::resolveFileSummary)
                .toList();
        final List<Folder> subfolders = allFolders.stream()
                .filter(candidate -> candidate.getOwnerId().equals(ownerAuthUserId))
                .filter(candidate -> !candidate.isDeleted())
                .filter(candidate -> Objects.equals(candidate.getParentFolderId(), folderId))
                .toList();
        return new SharedFolderContents(files, subfolders);
    }

    /**
     * Moves {@code storedFileId} into {@code folderId} (or back to the root, if {@code null}) -
     * a single-row update on its {@link StoredFileOwnership}, never touching the file's own
     * content or any other file's placement.
     *
     * @param authUserId the requesting user's id, checked against the ownership record
     * @param storedFileId the file to move
     * @param folderId the folder to move the file into, or {@code null} for the root
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId},
     *                                   or {@code folderId} is non-null and isn't owned by {@code authUserId}
     */
    @Override
    public void moveFile(@NonNull final String authUserId, @NonNull final String storedFileId, @Nullable final String folderId) {

        // Item 9 (sharing): deliberately owner-only - requireOwnedFile, never requireSharedFileAccess.
        // A read-only grant must never let a grantee move a file it doesn't own.
        final StoredFileOwnership existing = this.requireOwnedFile(authUserId, storedFileId);
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        try {
            this.dataFactory.update(existing.movedTo(folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.moveFile: failed to move " + storedFileId + " to folder " + folderId, e);
        }
    }

    /**
     * Lists every {@link CloudUser} currently registered, as their {@link ICloudUser} contract.
     *
     * @return every currently-registered {@link ICloudUser}
     */
    @NonNull
    @Override
    public List<ICloudUser> getCloudUsers() {
        try {
            return this.dataFactory.getEntities(CloudUser.class).stream()
                    .map(ICloudUser.class::cast)
                    .toList();
        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.getCloudUsers: failed to list CloudUser records", e);
        }
    }

    /**
     * Soft-deletes (moves to the trash) {@code storedFileId}, but only if {@code authUserId}
     * actually owns it - content and ownership tracking are left untouched, only {@link
     * StoredFileOwnership#isDeleted()} flips, via a single-row {@link DataFactory#update} (see
     * {@link StoredFileOwnership#deletedAtEpochMillis}'s own Javadoc for why this row, not the
     * underlying {@link StoredFile}, carries the flag {@link CloudUserService} actually checks).
     * Idempotent - a no-op if {@code storedFileId} is already in the trash. Does <b>not</b>
     * decrement the owner's usage total - the file's bytes still occupy storage until a purge job
     * (or {@link #resetCloudUser(String)}, via {@link #hardDeleteFile}) actually removes it; see
     * {@link #restoreFile(String, String)} for the reverse.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to trash
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void deleteFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        // Item 9 (sharing): deliberately owner-only - a grantee can read a shared file but never trash it.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (ownership.isDeleted()) {
            return;
        }
        try {
            this.dataFactory.update(ownership.markedDeleted());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to trash " + storedFileId, e);
        }
        // Item 9 (sharing), fixed 2026-09-02: revoke every outstanding share on this file the
        // moment it's deleted (trashed), not merely relying on getFile's own isDeleted() check to
        // block access - that check alone left the grant itself dangling, so a later restoreFile
        // would silently re-grant every previously-shared recipient access again, without the
        // owner ever having chosen to re-share. See revokeAllFileShares's own Javadoc.
        this.revokeAllFileShares(storedFileId);
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_DELETE, storedFileId, null));
    }

    /**
     * Restores a previously soft-deleted {@code storedFileId} out of the trash, but only if
     * {@code authUserId} actually owns it - the reverse of {@link #deleteFile(String, String)}.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to restore
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code storedFileId} is not currently in the trash
     */
    @Override
    public void restoreFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        // Item 9 (sharing): deliberately owner-only.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (!ownership.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFile: " + storedFileId + " is not in the trash");
        }
        try {
            this.dataFactory.update(ownership.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFile: failed to restore " + storedFileId, e);
        }
    }

    /**
     * Lists every file currently in {@code authUserId}'s trash, as {@link TrashedFileSummary}s
     * (added 2026-09-02 - each paired with when it becomes eligible for permanent removal, see
     * that record's own Javadoc) - same descriptive-fields-only shape/cost as {@link
     * #listFileSummaries(String)} for the underlying {@link StoredFileSummary}, just filtered to
     * trashed rows instead of live ones.
     *
     * @param authUserId the user whose trash to list
     * @return a {@link TrashedFileSummary} for every file currently in {@code authUserId}'s trash
     */
    @NonNull
    @Override
    public List<TrashedFileSummary> listDeletedFiles(@NonNull final String authUserId) {
        final List<StoredFileOwnership> deleted = this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(StoredFileOwnership::isDeleted)
                .toList();
        final List<StoredFileSummary> summaries = this.resolveFileSummaries(deleted);
        final long retentionMillis = this.resolveTrashRetentionDays() * MILLIS_PER_DAY;
        final List<TrashedFileSummary> trashed = new ArrayList<>(deleted.size());
        for (int i = 0; i < deleted.size(); i++) {
            trashed.add(new TrashedFileSummary(summaries.get(i), deleted.get(i).getDeletedAtEpochMillis() + retentionMillis));
        }
        return trashed;
    }

    /**
     * Creates a new, empty {@link Folder} owned by {@code authUserId} - a plain insert, the same
     * O(1) shape {@link #uploadFile(String, String, byte[], String)} already has for a new file.
     *
     * @param authUserId the owning user's id
     * @param name the new folder's display name
     * @param parentFolderId the parent folder to nest the new folder inside, or {@code null} for the top level
     * @return the newly created {@link Folder}
     * @throws IllegalArgumentException if {@code parentFolderId} is non-null and isn't owned by {@code authUserId}
     */
    @NonNull
    @Override
    public Folder createFolder(@NonNull final String authUserId, @NonNull final String name, @Nullable final String parentFolderId) {

        this.getOrCreate(authUserId);
        // Item 9 (sharing): deliberately owner-only - a grantee with folder-level read access
        // can never create content inside a folder shared with them.
        if (parentFolderId != null) this.requireOwnedFolder(authUserId, parentFolderId);

        final Folder folder = new Folder(UUID.randomUUID().toString(), authUserId, name, parentFolderId);
        try {
            this.dataFactory.register(folder);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.createFolder: failed to create folder '" + name + "'", e);
        }
        return folder;
    }

    /**
     * Lists every {@link Folder} belonging to {@code authUserId} directly inside {@code
     * parentFolderId} - the same full-scan-then-filter trade-off {@link #listFiles(String)}
     * already documents and accepts.
     *
     * @param authUserId the user whose folders should be listed
     * @param parentFolderId the parent folder to list children of, or {@code null} for the top level
     * @return every {@link Folder} belonging to {@code authUserId} directly inside {@code parentFolderId} (or the top level)
     */
    @NonNull
    @Override
    public List<Folder> listFolders(@NonNull final String authUserId, @Nullable final String parentFolderId) {
        // Item 9 (sharing): deliberately owner-only, does NOT include folders shared with
        // authUserId - see listFileSummaries's own comment for the same reasoning; use
        // listSharedFoldersWithMe(authUserId) instead.
        try {
            return this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
                    .filter(folder -> !folder.isDeleted())
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFolders: failed to list folders for " + authUserId, e);
        }
    }

    /**
     * See {@link ICloudUserService#listFoldersPage}'s Javadoc. Same full-scan-then-sort-then-slice
     * shape as {@link #listFileSummariesPage}, keyed on {@link Folder#getFolderId()}.
     */
    @NonNull
    @Override
    public CursorPage<Folder> listFoldersPage(@NonNull final String authUserId, @Nullable final String parentFolderId,
                                               @Nullable final String cursor, final int limit) {
        final List<Folder> sorted;
        try {
            sorted = this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(folder -> Objects.equals(folder.getParentFolderId(), parentFolderId))
                    .filter(folder -> !folder.isDeleted())
                    .sorted(Comparator.comparing(Folder::getFolderId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listFoldersPage: failed to list folders for " + authUserId, e);
        }
        return paginate(sorted, cursor, limit, Folder::getFolderId);
    }

    /**
     * Renames and/or moves {@code folderId} in one step, via a single {@code DataFactory#update}
     * on the resulting copy (see {@link Folder#renamedTo(String)}/{@link Folder#movedTo(String)}).
     * A move is only validated - never blindly trusted - against two failure modes: the target
     * parent must actually belong to {@code authUserId} ({@link #requireOwnedFolder}), and it must
     * not be {@code folderId} itself or one of its own descendants, which would otherwise create a
     * cycle {@link #listFolders}/a client's own tree walk could loop on forever.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to update
     * @param newName the folder's new display name
     * @param newParentFolderId the folder's new parent, or {@code null} to move it to the top level
     * @return the updated {@link Folder}
     * @throws IllegalArgumentException if {@code folderId}/{@code newParentFolderId} (when non-null) isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code newParentFolderId} is {@code folderId} itself, or one of its own descendants
     */
    @NonNull
    @Override
    public Folder updateFolder(@NonNull final String authUserId, @NonNull final String folderId,
                                @NonNull final String newName, @Nullable final String newParentFolderId) {

        // Item 9 (sharing): deliberately owner-only - a grantee can browse a shared folder but never rename/move it.
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);

        if (newParentFolderId != null) {
            if (newParentFolderId.equals(folderId)) {
                throw new IllegalStateException("@CloudUserService.updateFolder: cannot move " + folderId + " into itself");
            }
            final Folder targetParent = this.requireOwnedFolder(authUserId, newParentFolderId);
            this.requireNotDescendant(authUserId, folderId, targetParent);
        }

        final Folder updated = existing.renamedTo(newName).movedTo(newParentFolderId);
        try {
            this.dataFactory.update(updated);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateFolder: failed to update " + folderId, e);
        }
        return updated;
    }

    /**
     * Walks {@code targetParent}'s own ancestor chain up to the top level, failing if {@code
     * folderId} appears anywhere in it - that would mean {@code targetParent} already sits
     * (transitively) inside {@code folderId}, so moving {@code folderId} to become a child of
     * {@code targetParent} would create a cycle. O(depth of {@code targetParent}); folder
     * nesting is expected to stay shallow enough for this to be cheap.
     *
     * @param authUserId the owning user, used to resolve each ancestor via {@link #requireOwnedFolder}
     * @param folderId the folder being moved, checked for appearing in {@code targetParent}'s own ancestor chain
     * @param targetParent the folder {@code folderId} would be moved into
     * @throws IllegalStateException if {@code folderId} appears in {@code targetParent}'s ancestor chain
     */
    private void requireNotDescendant(final String authUserId, final String folderId, final Folder targetParent) {
        Folder current = targetParent;
        while (current != null) {
            if (current.getFolderId().equals(folderId)) {
                throw new IllegalStateException(
                        "@CloudUserService.updateFolder: cannot move " + folderId
                                + " into its own descendant " + targetParent.getFolderId());
            }
            current = current.getParentFolderId() == null ? null : this.requireOwnedFolder(authUserId, current.getParentFolderId());
        }
    }

    /**
     * Soft-deletes (moves to the trash) {@code folderId}, but only if {@code authUserId} owns it
     * and it is currently empty of non-trashed content. A folder is never deleted recursively - a
     * non-empty folder must be emptied (its children moved out or deleted individually) first.
     * Idempotent - a no-op if {@code folderId} is already in the trash.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to delete
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code folderId} still has non-trashed child folders or files inside it
     */
    @Override
    public void deleteFolder(@NonNull final String authUserId, @NonNull final String folderId) {

        // Item 9 (sharing): deliberately owner-only.
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        if (existing.isDeleted()) {
            return;
        }

        final boolean hasChildFolders = !this.listFolders(authUserId, folderId).isEmpty();
        // A plain ownership-row check, not listFilesWithFolder(...).isEmpty() - this only needs a
        // yes/no answer, so there's no reason to download and decrypt every file's content just to
        // count them. Both listFolders and ownedFileOwnerships already exclude trashed entries, so
        // a folder containing only already-trashed children is treated as empty here.
        final boolean hasChildFiles = this.ownedFileOwnerships(authUserId).stream()
                .anyMatch(ownership -> Objects.equals(ownership.getFolderId(), folderId));
        if (hasChildFolders || hasChildFiles) {
            throw new IllegalStateException("@CloudUserService.deleteFolder: " + folderId + " is not empty");
        }

        try {
            this.dataFactory.update(existing.markedDeleted());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.deleteFolder: failed to trash " + folderId, e);
        }
        // Item 9 (sharing), fixed 2026-09-02 - see deleteFile's own comment on why this must be
        // explicit rather than relying on Folder#isDeleted() alone.
        this.revokeAllFolderShares(folderId);
    }

    /**
     * Restores a previously soft-deleted {@code folderId} out of the trash, but only if {@code
     * authUserId} owns it - the reverse of {@link #deleteFolder(String, String)}. Does not
     * validate {@code folderId}'s own parent - see this method's own {@link
     * ICloudUserService#restoreFolder} Javadoc for why that's an accepted trade-off.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to restore
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     * @throws IllegalStateException if {@code folderId} is not currently in the trash
     */
    @Override
    public void restoreFolder(@NonNull final String authUserId, @NonNull final String folderId) {
        // Item 9 (sharing): deliberately owner-only.
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        if (!existing.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFolder: " + folderId + " is not in the trash");
        }
        try {
            this.dataFactory.update(existing.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFolder: failed to restore " + folderId, e);
        }
    }

    /**
     * Lists every {@link Folder} currently in {@code authUserId}'s trash, regardless of nesting,
     * each paired with when it becomes eligible for permanent removal (added 2026-09-02 - see
     * {@link TrashedFolderSummary}'s own Javadoc).
     *
     * @param authUserId the user whose trash to list
     * @return a {@link TrashedFolderSummary} for every folder currently in {@code authUserId}'s trash
     */
    @NonNull
    @Override
    public List<TrashedFolderSummary> listDeletedFolders(@NonNull final String authUserId) {
        final List<Folder> deleted;
        try {
            deleted = this.dataFactory.getEntities(Folder.class).stream()
                    .filter(folder -> folder.getOwnerId().equals(authUserId))
                    .filter(Folder::isDeleted)
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listDeletedFolders: failed to list trashed folders for " + authUserId, e);
        }
        final long retentionMillis = this.resolveTrashRetentionDays() * MILLIS_PER_DAY;
        return deleted.stream()
                .map(folder -> new TrashedFolderSummary(folder, folder.getDeletedAtEpochMillis() + retentionMillis))
                .toList();
    }

    /**
     * An O(1) point lookup on {@code storedFileId}'s ownership row, failing if {@code authUserId}
     * doesn't own it.
     *
     * @param authUserId the user expected to own the file
     * @param storedFileId the file to check ownership of
     * @return the matching {@link StoredFileOwnership} row
     * @throws IllegalArgumentException if no such ownership row exists for {@code authUserId}/{@code storedFileId}
     */
    private StoredFileOwnership requireOwnedFile(final String authUserId, final String storedFileId) {
        final String ownershipKey = StoredFileOwnership.compositeKey(authUserId, storedFileId);
        final Optional<StoredFileOwnership> ownership;
        try {
            ownership = this.dataFactory.findById(ownershipKey, StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireOwnedFile: failed to look up ownership record " + ownershipKey, e);
        }
        if (ownership.isEmpty()) {
            throw new IllegalArgumentException("@CloudUserService.requireOwnedFile: " + authUserId + " does not own " + storedFileId);
        }
        return ownership.get();
    }

    /**
     * Forwards one metric event to {@link CloudDriver#getInstance()}'s {@link MetricsRecorder}, if
     * {@code cloud-driver-extensions-metrics} has published one - a no-op otherwise. Never throws:
     * a missing/misbehaving metrics sink must never affect a real upload, matching {@link
     * MetricsRecorder}'s own "must never throw" contract, enforced here defensively too. Mirrors
     * {@code DefaultFileFactory}'s own private helper of the same name/shape in {@code
     * cloud-driver-plugin} - not shared code, since neither module may depend on the other.
     *
     * @param action the {@link MetricsRecorder} method to invoke, e.g. {@code
     *     MetricsRecorder::recordUploadQuotaRejected}
     */
    private static void recordMetric(final Consumer<MetricsRecorder> action) {
        try {
            final MetricsRecorder recorder = CloudDriver.getInstance().getServiceContainer().getMetricsRecorder();
            if (recorder != null) action.accept(recorder);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /** {@code configuration.json} key {@link #resolveTrashRetentionDays} reads the trash retention window from - the exact same key {@code TrashPurgeScheduler} (cloud-driver-plugin) reads for its own retention resolution. */
    private static final String TRASH_RETENTION_DAYS_CONFIG_KEY = "trash-retention-days";

    /** Default retention window (days) if {@link #TRASH_RETENTION_DAYS_CONFIG_KEY} is unset - mirrors {@code TrashPurgeScheduler#DEFAULT_RETENTION_DAYS} exactly. */
    private static final long DEFAULT_TRASH_RETENTION_DAYS = 30L;

    /** Milliseconds in a day - used to convert {@link #resolveTrashRetentionDays}'s result into an epoch-millis offset. */
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    /**
     * Resolves the configured trash retention window (in days), the same {@code
     * "trash-retention-days"} key/30-day-default {@code TrashPurgeScheduler#withConfiguredRetention}
     * reads - used by {@link #listDeletedFiles}/{@link #listDeletedFolders} to compute each trashed
     * item's {@code purgeAtEpochMillis}.
     *
     * <p><b>Necessarily duplicated, not shared, with {@code TrashPurgeScheduler}'s own resolution
     * logic</b> - that class lives in {@code cloud-driver-plugin}, which this module ({@code
     * cloud-driver-auth}) must never depend on (see {@code CLAUDE.md}'s "Module layout and
     * dependency direction"). Reading {@code configuration.json} directly from this module is an
     * already-established pattern here, though - see {@code CloudUser#resolveMaxBytesToUpload}'s
     * own {@link JsonDocument#contains}-first read of a different optional key, the same shape
     * this method uses. Keep the key name/default in sync with {@code TrashPurgeScheduler}'s own
     * constants by hand if either ever changes - nothing enforces this automatically.
     *
     * @return the configured (or default) trash retention window, in days
     */
    private long resolveTrashRetentionDays() {
        final JsonDocument configuration = CloudDriver.getInstance().getConfiguration();
        return configuration.contains(TRASH_RETENTION_DAYS_CONFIG_KEY)
                ? configuration.getLong(TRASH_RETENTION_DAYS_CONFIG_KEY)
                : DEFAULT_TRASH_RETENTION_DAYS;
    }

    /**
     * An O(1) point lookup on {@code folderId}, failing if it doesn't exist or belongs to someone
     * other than {@code authUserId}.
     *
     * @param authUserId the user expected to own the folder
     * @param folderId the folder to check ownership of
     * @return the matching {@link Folder}
     * @throws IllegalArgumentException if {@code folderId} doesn't exist or isn't owned by {@code authUserId}
     */
    private Folder requireOwnedFolder(final String authUserId, final String folderId) {
        final Optional<Folder> folder;
        try {
            folder = this.dataFactory.findById(folderId, Folder.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireOwnedFolder: failed to look up folder " + folderId, e);
        }
        if (folder.isEmpty() || !folder.get().getOwnerId().equals(authUserId)) {
            throw new IllegalArgumentException("@CloudUserService.requireOwnedFolder: " + authUserId + " does not own folder " + folderId);
        }
        return folder.get();
    }

    /**
     * Backs {@link #listFiles}/{@link #listFilesWithFolder}/{@link #listFileSummaries}/{@link
     * #deleteFolder}'s emptiness check - see {@link #listFiles}'s Javadoc for the full-scan
     * trade-off this implies. Excludes trashed rows by default; see {@link
     * #ownedFileOwnershipsIncludingDeleted(String)} for the raw, unfiltered scan.
     */
    private List<StoredFileOwnership> ownedFileOwnerships(final String authUserId) {
        return this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(ownership -> !ownership.isDeleted())
                .toList();
    }

    /**
     * Same full scan as {@link #ownedFileOwnerships(String)}, without the trash filter - backs
     * {@link #listDeletedFiles(String)} and {@link #resetCloudUser(String)} (which must reach
     * already-trashed rows too, to actually purge them via {@link #hardDeleteFile}).
     */
    private List<StoredFileOwnership> ownedFileOwnershipsIncludingDeleted(final String authUserId) {
        try {
            return this.dataFactory.getEntities(StoredFileOwnership.class).stream()
                    .filter(ownership -> ownership.getAuthUserId().equals(authUserId))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.ownedFileOwnershipsIncludingDeleted: failed to list ownership records for " + authUserId, e);
        }
    }

    /**
     * {@link #ownedFileOwnerships(String)}, mapped down to just each row's {@link StoredFileOwnership#getStoredFileId()}.
     *
     * @param authUserId the user whose owned file ids should be listed
     * @return every {@link StoredFile#fileId()} tracked as belonging to {@code authUserId}
     */
    private List<String> ownedFileIds(final String authUserId) {
        return this.ownedFileOwnerships(authUserId).stream().map(StoredFileOwnership::getStoredFileId).toList();
    }

}
