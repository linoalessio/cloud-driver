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
import de.lino.cloud.api.file.exception.FileScanBlockedException;
import de.lino.cloud.api.file.exception.PublicShareLinkInvalidException;
import de.lino.cloud.api.file.exception.SyncConflictException;
import de.lino.cloud.api.file.exception.UploadQuotaExceededException;
import de.lino.cloud.api.file.meta.FileChecksum;
import de.lino.cloud.api.file.PublicFileLinkSummary;
import de.lino.cloud.api.file.ScanStatus;
import de.lino.cloud.api.file.SharePermission;
import de.lino.cloud.api.scan.ContentScanService;
import de.lino.cloud.api.intelligence.DuplicateFileGroup;
import de.lino.cloud.api.intelligence.DuplicateGroup;
import de.lino.cloud.api.intelligence.IntelligenceDocument;
import de.lino.cloud.api.intelligence.IntelligenceService;
import de.lino.cloud.api.intelligence.SemanticMatch;
import de.lino.cloud.api.intelligence.SemanticSearchResult;
import de.lino.cloud.api.intelligence.TagSuggestion;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.metrics.MetricsRecorder;
import de.lino.cloud.api.search.SearchDocument;
import de.lino.cloud.api.search.SearchIndexService;
import de.lino.cloud.api.webhook.WebhookEventType;
import de.lino.cloud.api.webhook.WebhookService;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.hash.HashAlgorithm;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.s3storage.ContentKeyService;
import de.lino.cloud.api.s3storage.ObjectStorageException;
import de.lino.cloud.api.s3storage.PresignedDownload;
import de.lino.cloud.api.s3storage.PresignedTransferService;
import de.lino.cloud.api.s3storage.PresignedTransferUnavailableException;
import de.lino.cloud.api.file.PresignedDownloadEncryption;
import de.lino.cloud.api.file.PresignedDownloadTicket;
import de.lino.cloud.api.file.PresignedUploadEncryption;
import de.lino.cloud.api.file.PresignedUploadTicket;
import de.lino.cloud.api.user.GranteeAccountNotFoundException;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.CursorPage;
import de.lino.cloud.api.versioning.FileVersioningService;
import de.lino.cloud.auth.entity.CloudUser;
import de.lino.cloud.auth.entity.PublicShareLink;
import de.lino.cloud.auth.entity.SharedFileGrant;
import de.lino.cloud.auth.entity.SharedFolderGrant;
import de.lino.cloud.auth.entity.StoredFileOwnership;
import de.lino.cloud.auth.pending.PendingPresignedUpload;
import de.lino.database.json.JsonDocument;
import lombok.NonNull;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
     * persisted audit trail - see {@code AuditLogService}'s own Javadoc. Never throws, so both
     * call sites below invoke it directly with no defensive try/catch of their own.
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
     * Issues/recovers the per-file content keys a presigned transfer's client-side encryption
     * uses (see {@link ContentKeyService}) - {@code null} only in tests/legacy wirings, in which
     * case {@link #beginPresignedUpload} issues unencrypted (legacy-behavior) tickets. Unused
     * unless {@link #presignedTransferService} is configured.
     */
    @Nullable
    private final ContentKeyService contentKeyService;

    /**
     * Same as {@link #CloudUserService(DataFactory, FileFactory, AuditLogService,
     * PresignedTransferService, ContentKeyService)} with {@link #presignedTransferService}/{@link
     * #contentKeyService} defaulted to {@code null} - presigned direct-to-client transfer not
     * configured.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory,
                             @NonNull final AuditLogService auditLogService) {
        this(dataFactory, fileFactory, auditLogService, null, null);
    }

    /**
     * Same as {@link #CloudUserService(DataFactory, FileFactory, AuditLogService,
     * PresignedTransferService, ContentKeyService)} with {@link #contentKeyService} defaulted to
     * {@code null} - presigned tickets stay unencrypted (legacy behavior). Kept for
     * source-compatibility with pre-client-side-encryption callers.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     * @param presignedTransferService generates presigned URLs for direct-to-client transfer, or
     *     {@code null} if this deployment hasn't configured one
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory,
                             @NonNull final AuditLogService auditLogService, @Nullable final PresignedTransferService presignedTransferService) {
        this(dataFactory, fileFactory, auditLogService, presignedTransferService, null);
    }

    /**
     * Creates a {@code CloudUserService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link CloudUser}, {@link Folder}, and {@link StoredFileOwnership} rows
     * @param fileFactory uploads/downloads/deletes the underlying {@link StoredFile} content
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     * @param presignedTransferService generates presigned URLs for direct-to-client transfer, or
     *     {@code null} if this deployment hasn't configured one
     * @param contentKeyService issues/recovers per-file content keys for presigned client-side
     *     encryption, or {@code null} to issue unencrypted (legacy-behavior) tickets
     */
    public CloudUserService(@NonNull final DataFactory dataFactory, @NonNull final FileFactory fileFactory,
                             @NonNull final AuditLogService auditLogService, @Nullable final PresignedTransferService presignedTransferService,
                             @Nullable final ContentKeyService contentKeyService) {
        this.presignedTransferService = presignedTransferService;
        this.contentKeyService = contentKeyService;
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
     * a trashed row (soft delete) - the owner of a file that was just moved to trash (or
     * restored, or hard-deleted) is exactly who a live-push notification about that change should
     * still reach.
     *
     * @param storedFileId the {@link StoredFile#fileId()} to resolve an owner for
     * @return the owning {@code authUserId}, or {@link Optional#empty()} if no ownership row tracks this file
     */
    @Override
    public @NonNull Optional<String> resolveOwnerAuthUserId(@NotNull final String storedFileId) {
        try {

            return this.dataFactory.getEntitiesByIndex(StoredFileOwnership.class, StoredFileOwnership.INDEX_STORED_FILE_ID, storedFileId).stream()
                    .map(StoredFileOwnership::getAuthUserId)
                    .findFirst();

        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.resolveOwnerAuthUserId: failed to look up owner for " + storedFileId, e);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void updateCachedFileScanStatus(@NotNull final String storedFileId, @NotNull final String scanStatus) {
        try {
            final Optional<String> ownerAuthUserId = resolveOwnerAuthUserId(storedFileId);
            if (ownerAuthUserId.isEmpty()) {
                return;
            }
            final String ownershipKey = StoredFileOwnership.compositeKey(ownerAuthUserId.get(), storedFileId);
            final Optional<StoredFileOwnership> ownership = this.dataFactory.findById(ownershipKey, StoredFileOwnership.class);
            if (ownership.isEmpty()) {
                return;
            }
            this.dataFactory.update(ownership.get().withScanStatus(scanStatus));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException e) {
            // Best-effort only - see this method's own Javadoc on ICloudUserService.
        }
    }

    /** {@inheritDoc} */
    @Override
    public void purgeExpiredFile(@NotNull final String authUserId, @NotNull final String storedFileId) {
        final String ownershipKey = StoredFileOwnership.compositeKey(authUserId, storedFileId);
        final Optional<StoredFileOwnership> ownership;
        try {
            ownership = this.dataFactory.findById(ownershipKey, StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.purgeExpiredFile: failed to look up ownership record " + ownershipKey, e);
        }
        if (ownership.isEmpty()) {
            return; // already removed by an earlier purge tick
        }
        this.hardDeleteFile(authUserId, ownership.get());
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

        // Per-account deduplication: a row is either a
        // deduplication alias of another of this account's own files (dedupCanonicalFileId set) or
        // "the payer" carrying real content of its own - see deleteDeduplicatedFile's own Javadoc
        // for how content and usage accounting are kept correct across either shape.
        this.deleteDeduplicatedFile(authUserId, ownership);

        try {
            this.dataFactory.delete(StoredFileOwnership.compositeKey(authUserId, storedFileId), StoredFileOwnership.class);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException(
                    "@CloudUserService.hardDeleteFile: failed to untrack ownership of " + storedFileId + " for " + authUserId, e
            );
        }
        // Sharing - a no-op scan if deleteFile already revoked these
        // (the normal trash-then-purge path), but resetCloudUser/deleteCloudUser call this
        // directly on a possibly still-live file (bypassing the trash entirely), so this must not
        // assume deleteFile's own revocation already ran.
        this.revokeAllFileShares(storedFileId);
        // Same idempotency reasoning as revokeAllFileShares above.
        this.revokeAllPublicFileLinks(storedFileId);
        // Same idempotency reasoning as revokeAllFileShares
        // above: a no-op if deleteFile already removed this id from the index (the normal
        // trash-then-purge path), but resetCloudUser/deleteCloudUser call hardDeleteFile directly
        // on a possibly still-live (never-trashed) file, so this must not assume it already ran.
        removeFromSearchIndex(authUserId, storedFileId);
        // Same idempotency reasoning again - and harmless even if it never runs at all, since a
        // vector outliving its file can never surface (see IntelligenceService#removeAsync).
        removeFromIntelligenceIndex(storedFileId);
    }

    /**
     * Removes {@code ownership}'s underlying {@link StoredFile} content, if this is genuinely the
     * moment it becomes unreferenced by {@code authUserId}'s own account - the deduplication-aware
     * half of {@link #hardDeleteFile}, kept separate since it's substantial enough to warrant its
     * own Javadoc. Three cases:
     *
     * <ul>
     *   <li><b>{@code ownership} is a deduplication alias</b> ({@link
     *       StoredFileOwnership#getDedupCanonicalFileId()} non-{@code null}) - its own row carries
     *       no content, so it's always safe to remove; the canonical file's {@link
     *       StoredFile#dedupRefCount()} is decremented, and if that reaches zero <em>and</em> the
     *       canonical no longer has a live ownership row of its own either (see {@link
     *       #resolveOwnerAuthUserId}) - i.e. this was genuinely the last reference to that content
     *       left anywhere in the account - the canonical's content is finally freed and only then
     *       is usage decremented (see the next case for why usage was never charged to the alias
     *       itself).
     *   <li><b>{@code ownership} is the canonical (payer) with active aliases</b> ({@link
     *       StoredFile#dedupRefCount()} {@code > 0}) - its content must survive for those aliases,
     *       so neither the {@link StoredFile} row nor the usage total is touched here; usage is
     *       decremented later, whenever the last alias referencing it is itself removed (the case
     *       above).
     *   <li><b>Otherwise</b> (a plain file, or a canonical with no remaining aliases) - exactly the
     *       delete/decrement sequence this method always performed before deduplication existed.
     * </ul>
     *
     * @param authUserId the owning user, whose usage total may be decremented
     * @param ownership the ownership row being permanently removed
     */
    private void deleteDeduplicatedFile(final String authUserId, final StoredFileOwnership ownership) {
        final String storedFileId = ownership.getStoredFileId();

        if (ownership.getDedupCanonicalFileId() != null) {
            final String canonicalFileId = ownership.getDedupCanonicalFileId();
            try {
                this.fileFactory.delete(storedFileId);
            } catch (final DatabaseClientException e) {
                throw new RuntimeException("@CloudUserService.deleteDeduplicatedFile: failed to delete alias " + storedFileId, e);
            }
            this.decrementDedupRefCountAndMaybeFree(authUserId, canonicalFileId, ownership);
            return;
        }

        final int refCount = this.findStoredFileMetadata(storedFileId).map(StoredFile::dedupRefCount).orElse(0);
        if (refCount > 0) {
            // Other files this same account owns still alias this content - keep it alive. Only
            // this ownership row is removed (by hardDeleteFile, right after this method returns);
            // usage stays charged until the last remaining alias is itself removed.
            return;
        }

        try {
            this.fileFactory.delete(storedFileId);
        } catch (final DatabaseClientException e) {
            throw new RuntimeException("@CloudUserService.deleteDeduplicatedFile: failed to delete " + storedFileId, e);
        }
        if (ownership.hasMetadata()) {
            this.updateCloudUserBytesUsage(authUserId, -ownership.getSizeBytes());
        }
    }

    /**
     * Decrements {@code canonicalFileId}'s {@link StoredFile#dedupRefCount()} by one (a no-op if
     * the canonical no longer exists) and, only if that reaches zero <em>and</em> no live ownership
     * row still points directly at {@code canonicalFileId} (its own original upload was already
     * removed earlier - see {@link #deleteDeduplicatedFile}'s middle case), actually frees its
     * content and decrements {@code authUserId}'s usage total by {@code aliasOwnership}'s own
     * (byte-identical) {@link StoredFileOwnership#getSizeBytes()} - the one point in this whole
     * scheme usage is ever decremented on the alias side, ensuring it happens exactly once,
     * whichever deletion (the canonical's own, or the last alias's) turns out to be the one that
     * actually frees the bytes.
     *
     * @param authUserId the account whose usage total to adjust if the content is actually freed
     * @param canonicalFileId the canonical file whose reference count to decrement
     * @param aliasOwnership the alias ownership row being removed, whose size backs the usage decrement
     */
    private void decrementDedupRefCountAndMaybeFree(final String authUserId, final String canonicalFileId,
                                                      final StoredFileOwnership aliasOwnership) {
        final Optional<StoredFile> canonical = this.findStoredFileMetadata(canonicalFileId);
        if (canonical.isEmpty()) {
            return;
        }

        final int newCount = Math.max(0, canonical.get().dedupRefCount() - 1);
        try {
            this.dataFactory.update(canonical.get().withDedupRefCount(newCount));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.decrementDedupRefCountAndMaybeFree: failed to persist refcount for " + canonicalFileId, e);
        }

        if (newCount == 0 && this.resolveOwnerAuthUserId(canonicalFileId).isEmpty()) {
            try {
                this.fileFactory.delete(canonicalFileId);
            } catch (final DatabaseClientException e) {
                throw new RuntimeException(
                        "@CloudUserService.decrementDedupRefCountAndMaybeFree: failed to delete orphaned canonical " + canonicalFileId, e);
            }
            if (aliasOwnership.hasMetadata()) {
                this.updateCloudUserBytesUsage(authUserId, -aliasOwnership.getSizeBytes());
            }
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
     * Sets {@code authUserId}'s stored theme preference to {@code themeMode} and persists the
     * change - a single-row {@link DataFactory#update}, the same shape {@link
     * #updateCloudUserBytesLimit} uses. A no-op if {@code authUserId} has no {@link CloudUser}
     * record yet.
     *
     * @param authUserId the account whose theme preference to update
     * @param themeMode the new theme preference, or {@code null} to clear it
     */
    @Override
    public void updateThemePreference(@NonNull final String authUserId, @Nullable final String themeMode) {
        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return;

        final ICloudUser existing = cloudUser.get();
        existing.setThemeMode(themeMode);

        try {
            this.dataFactory.update((CloudUser) existing);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateThemePreference: failed to persist theme preference for " + authUserId, e);
        }
    }

    /**
     * See {@link ICloudUserService#recomputeUploadedBytes}'s Javadoc. Sums {@link
     * StoredFileOwnership#getSizeBytes()} across every row this account still tracks - trashed
     * rows included, via {@link #ownedFileOwnershipsIncludingDeleted(String)} rather than {@link
     * #ownedFileOwnerships(String)}, since a trashed-but-not-yet-purged file still occupies
     * s3storage (see {@link #deleteFile}'s own Javadoc: trashing alone never decrements the usage
     * total, only {@link #hardDeleteFile} does) - so this recompute must agree with that same
     * accounting rule rather than silently under-counting relative to it.
     *
     * <p><b>Counts each distinct content once, not each row</b> (fixed 2026-09-10; previously
     * summed every row, silently switching the counter from the physical convention every other
     * writer uses to a logical one): the incremental accounting is dedup-aware - {@link
     * #uploadFile} never charges a deduplication alias ("consumes no new physical storage") and
     * {@link #deleteDeduplicatedFile} only decrements when content is actually freed - so this
     * recompute must be too. Rows are therefore grouped by {@link
     * StoredFileOwnership#resolvedDedupCanonicalFileId()} (an alias resolves to its canonical, a
     * plain row to itself) and each group's size counted once. Deliberately <em>not</em> "skip
     * alias rows": when a canonical's own upload was already hard-deleted while its aliases live
     * on, only alias rows remain for content that is still stored and still charged - grouping by
     * canonical id counts that content exactly once either way.
     */
    @Override
    public long recomputeUploadedBytes(@NonNull final String authUserId) {
        final Optional<ICloudUser> cloudUser = this.getCloudUser(authUserId);
        if (cloudUser.isEmpty()) return 0L;

        final long total = this.ownedFileOwnershipsIncludingDeleted(authUserId).stream()
                .filter(StoredFileOwnership::hasMetadata)
                .collect(Collectors.toMap(
                        StoredFileOwnership::resolvedDedupCanonicalFileId,
                        StoredFileOwnership::getSizeBytes,
                        (firstSize, duplicateSize) -> firstSize))
                .values().stream()
                .mapToLong(Long::longValue)
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
            remaining = new ArrayList<>(this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
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
                // Fixed 2026-09-02 - see deleteFile's own comment.
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
            stillExisting = new ArrayList<>(this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
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
                // Fixed 2026-09-02 - see deleteFile's own comment.
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

        // Per-account deduplication (confirmed with Lino as
        // per-account only - never across accounts). Computed up front (cheap - a single SHA-256
        // pass, unlike the StoredFile constructor below, which also DEFLATE-compresses and
        // base64-encodes) so a match can skip both the quota check and the real upload entirely.
        final FileChecksum checksum = FileChecksum.of(HashAlgorithm.SHA_256, content);
        final Optional<StoredFileOwnership> dedupCandidate = this.findDedupCandidate(authUserId, checksum);

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        if (dedupCandidate.isEmpty()) {
            // Checked before requireOwnedFolder/constructing the StoredFile (which DEFLATE-compresses
            // and base64-encodes content up front) - no reason to pay for either on a rejected upload.
            if (cloudUser.isUploadLimitReached(content.length)) {
                recordMetric(MetricsRecorder::recordUploadQuotaRejected);
                throw new UploadQuotaExceededException(
                        authUserId, cloudUser.getCurrentUploadedBytes(), content.length, cloudUser.getMaxBytesToUpload());
            }
        }
        // Sharing: deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        // Content scanning - checked once, up front: cheap (a
        // null check on an already-resolved service reference), and decides whether this upload
        // (either branch below, dedup alias included - see uploadFile's own dedup-alias-scanning
        // trade-off documented on ContentScanService/DefaultContentScanService) starts out
        // PENDING or stays permanently CLEAN, matching "must keep working with every microservice
        // turned off" - a deployment not running cloud-driver-extensions-scan never produces a
        // PENDING file at all.
        final boolean scanningEnabled = isContentScanServicePublished();

        final StoredFile storedFile;
        final String dedupCanonicalFileId;
        if (dedupCandidate.isPresent()) {
            dedupCanonicalFileId = dedupCandidate.get().resolvedDedupCanonicalFileId();
            StoredFile alias = StoredFile.createDedupAlias(
                    UUID.randomUUID().toString(), fileName, content.length, checksum, Instant.now(), Instant.now(), dedupCanonicalFileId);
            if (scanningEnabled) alias = alias.withScanStatus(ScanStatus.PENDING);
            storedFile = alias;
            this.incrementDedupRefCount(dedupCanonicalFileId);
            try {
                // No content of its own to persist through fileFactory.upload - a plain metadata
                // insert, same reasoning CloudUserService#completePresignedUpload's register call has.
                this.dataFactory.register(storedFile);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException("@CloudUserService.uploadFile: failed to register deduplicated '" + fileName + "'", e);
            }
        } else {
            dedupCanonicalFileId = null;
            StoredFile fresh = new StoredFile(UUID.randomUUID().toString(), fileName, content);
            if (scanningEnabled) fresh = fresh.withScanStatus(ScanStatus.PENDING);
            storedFile = fresh;
            try {
                this.fileFactory.upload(storedFile);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
            }
        }

        try {
            this.dataFactory.register(StoredFileOwnership.of(authUserId, storedFile, folderId, dedupCanonicalFileId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.uploadFile: failed to track ownership of " + storedFile.fileId() + " for " + authUserId, e
            );
        }

        if (dedupCandidate.isEmpty()) {
            // A deduplicated upload consumes no new physical storage - see this method's own dedup
            // branch above and hardDeleteFile's matching decrement-on-actual-free logic.
            this.updateCloudUserBytesUsage(authUserId, content.length);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_UPLOAD, storedFile.fileId(), null));
        indexFileForSearch(authUserId, storedFile, folderId, content);
        indexFileForIntelligence(authUserId, storedFile, content);
        dispatchWebhookEvent(authUserId, WebhookEventType.FILE_UPLOADED, storedFile.fileId());

        return storedFile;
    }

    /**
     * Streaming counterpart of {@link #uploadFile(String, String, byte[], String)}, for uploads
     * too large to hold in memory: {@code contentFile}'s bytes never reach the heap as one array
     * on this path - the checksum is computed by streaming the file, the dedup-alias branch needs
     * no content at all, and a fresh file is handed to {@link FileFactory#upload} as a {@link
     * StoredFile#createFromContentFile content-file-backed} instance that {@code
     * DefaultFileFactory#prepareForPersistence} chunk-encrypts straight off the file into the
     * object store. See {@link ICloudUserService#uploadFile(String, String, Path, String)} for
     * the two deliberate behavioral differences (no compression, no content-based
     * search/intelligence extraction - both matching {@link #completePresignedUpload}'s existing
     * treatment of content this server never holds).
     *
     * <p>Every other step - dedup, quota, folder ownership, scan status, ownership tracking,
     * usage accounting, audit, webhooks - is identical to the {@code byte[]} overload, in the
     * same order.
     */
    @NonNull
    @Override
    public StoredFile uploadFile(@NonNull final String authUserId, @NonNull final String fileName,
                                  @NonNull final Path contentFile, @Nullable final String folderId) {

        final long sizeBytes;
        final FileChecksum checksum;
        try {
            sizeBytes = Files.size(contentFile);
            try (InputStream content = Files.newInputStream(contentFile)) {
                checksum = FileChecksum.of(HashAlgorithm.SHA_256, content);
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(
                    "@CloudUserService.uploadFile: failed reading content file '" + contentFile + "' for '" + fileName + "'", e);
        }
        final Optional<StoredFileOwnership> dedupCandidate = this.findDedupCandidate(authUserId, checksum);

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        if (dedupCandidate.isEmpty()) {
            if (cloudUser.isUploadLimitReached(sizeBytes)) {
                recordMetric(MetricsRecorder::recordUploadQuotaRejected);
                throw new UploadQuotaExceededException(
                        authUserId, cloudUser.getCurrentUploadedBytes(), sizeBytes, cloudUser.getMaxBytesToUpload());
            }
        }
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final boolean scanningEnabled = isContentScanServicePublished();

        final StoredFile storedFile;
        final String dedupCanonicalFileId;
        if (dedupCandidate.isPresent()) {
            dedupCanonicalFileId = dedupCandidate.get().resolvedDedupCanonicalFileId();
            StoredFile alias = StoredFile.createDedupAlias(
                    UUID.randomUUID().toString(), fileName, sizeBytes, checksum, Instant.now(), Instant.now(), dedupCanonicalFileId);
            if (scanningEnabled) alias = alias.withScanStatus(ScanStatus.PENDING);
            storedFile = alias;
            this.incrementDedupRefCount(dedupCanonicalFileId);
            try {
                this.dataFactory.register(storedFile);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException("@CloudUserService.uploadFile: failed to register deduplicated '" + fileName + "'", e);
            }
        } else {
            dedupCanonicalFileId = null;
            StoredFile fresh = StoredFile.createFromContentFile(
                    UUID.randomUUID().toString(), fileName, sizeBytes, checksum, contentFile);
            if (scanningEnabled) fresh = fresh.withScanStatus(ScanStatus.PENDING);
            // The factory registers the persisted shape it derives itself (S3-backed, or inline
            // on a deployment without an object store); everything downstream here only reads
            // metadata off this instance - identical either way - never its content.
            storedFile = fresh;
            try {
                this.fileFactory.upload(storedFile);
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException("@CloudUserService.uploadFile: failed to upload '" + fileName + "'", e);
            }
        }

        try {
            this.dataFactory.register(StoredFileOwnership.of(authUserId, storedFile, folderId, dedupCanonicalFileId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException(
                    "@CloudUserService.uploadFile: failed to track ownership of " + storedFile.fileId() + " for " + authUserId, e
            );
        }

        if (dedupCandidate.isEmpty()) {
            this.updateCloudUserBytesUsage(authUserId, sizeBytes);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_UPLOAD, storedFile.fileId(), null));
        // Content deliberately not passed: extracting indexable text/an embedding would require
        // materializing the very bytes this path exists to keep off the heap - the file is still
        // indexed by name/folder, the same degradation completePresignedUpload already accepts.
        indexFileForSearch(authUserId, storedFile, folderId, null);
        indexFileForIntelligence(authUserId, storedFile, null);
        dispatchWebhookEvent(authUserId, WebhookEventType.FILE_UPLOADED, storedFile.fileId());

        return storedFile;
    }

    /**
     * Looks for a file {@code authUserId} already owns (live, not trashed) whose content matches
     * {@code checksum} - the per-account deduplication candidate {@link #uploadFile(String, String,
     * byte[], String)} aliases a fresh upload against instead of storing a second copy of the same
     * bytes. Reuses {@link #ownedFileOwnerships(String)}'s already-accepted full-scan trade-off (see
     * that method's own Javadoc) - this adds one more scan per upload, on top of whatever else a
     * caller already pays for uploading a file.
     *
     * <p>Scoped to a single account, deliberately, per Lino's own explicit sign-off - this
     * sidesteps the cross-account blob-sharing privacy tradeoff entirely by never matching against
     * another account's files.
     *
     * @param authUserId the uploading user's id - only their own files are considered
     * @param checksum the freshly-computed checksum of the content being uploaded
     * @return the matching {@link StoredFileOwnership} row, if any
     */
    @NotNull
    private Optional<StoredFileOwnership> findDedupCandidate(@NotNull final String authUserId, @NotNull final FileChecksum checksum) {
        return this.ownedFileOwnerships(authUserId).stream()
                .filter(StoredFileOwnership::hasChecksum)
                .filter(ownership -> ownership.getChecksumAlgorithm().equals(checksum.algorithm().name())
                        && ownership.getChecksumHex().equalsIgnoreCase(checksum.hexDigest()))
                .findFirst();
    }

    /**
     * Increments {@code canonicalFileId}'s {@link StoredFile#dedupRefCount()} by one and persists
     * the change - called once for every alias {@link #uploadFile(String, String, byte[], String)}
     * creates against it. A plain read-modify-write, not compare-and-swap - like {@link
     * #updateCloudUserBytesUsage}, this codebase has no atomic increment primitive to reach for
     * here; two uploads deduplicating against the exact same canonical file at the same instant is
     * a narrow, accepted race, consistent with every other read-modify-write update in this class.
     *
     * @param canonicalFileId the canonical file whose reference count to increment
     * @throws IllegalStateException if {@code canonicalFileId} no longer exists
     */
    private void incrementDedupRefCount(@NotNull final String canonicalFileId) {
        final StoredFile canonical = this.findStoredFileMetadata(canonicalFileId)
                .orElseThrow(() -> new IllegalStateException(
                        "@CloudUserService.incrementDedupRefCount: canonical file " + canonicalFileId + " no longer exists"));
        try {
            this.dataFactory.update(canonical.withDedupRefCount(canonical.dedupRefCount() + 1));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.incrementDedupRefCount: failed to persist refcount for " + canonicalFileId, e);
        }
    }

    /**
     * Looks up {@code storedFileId}'s raw {@link StoredFile} row directly via {@link #dataFactory}
     * - not {@link #fileFactory}, which would also resolve/verify full content (S3 fetch,
     * deduplication-alias resolution, checksum verification) this method never needs, only ever
     * reading {@link StoredFile#isDedupAlias()}/{@link StoredFile#dedupRefCount()}.
     *
     * @param storedFileId the file to look up
     * @return the raw entity, or {@link Optional#empty()} if it no longer exists
     */
    @NotNull
    private Optional<StoredFile> findStoredFileMetadata(@NotNull final String storedFileId) {
        try {
            return this.dataFactory.findById(storedFileId, StoredFile.class);
        } catch (final DatabaseClientException | AuthenticationFailedException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.findStoredFileMetadata: failed to look up " + storedFileId, e);
        }
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
        // Sharing: deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final String fileId = UUID.randomUUID().toString();

        if (this.contentKeyService != null) {
            // Client-side encryption (see ContentKeyService): issue a fresh per-file key, and
            // presign for the exact ciphertext length the declared plaintext size must produce -
            // the client's upload only completes verifiably if it encrypted exactly sizeBytes.
            final ContentKeyService.IssuedContentKey issuedKey;
            try {
                issuedKey = this.contentKeyService.issueContentKey();
            } catch (final KeyWrapException e) {
                throw new RuntimeException("@CloudUserService.beginPresignedUpload: failed to issue a content key for '" + fileId + "'", e);
            }
            final byte[] header = issuedKey.header();
            final long objectLengthBytes = this.contentKeyService.objectLength(header.length, sizeBytes);
            // Mandatory, unlike the unencrypted branch's best-effort tracking below: this row is
            // the only durable carrier of the wrapped content key between begin and complete -
            // losing it would leave the client's uploaded ciphertext permanently undecryptable,
            // so a persist failure must fail the ticket, not merely log.
            this.trackPendingPresignedUpload(fileId, authUserId, Base64.getEncoder().encodeToString(header), sizeBytes);
            return new PresignedUploadTicket(
                    fileId,
                    presignedTransferService.presignUpload(fileId, objectLengthBytes, PRESIGNED_URL_EXPIRY),
                    new PresignedUploadEncryption(issuedKey.keyMaterial(), header,
                            this.contentKeyService.associatedDataPrefix(fileId), this.contentKeyService.chunkSizeBytes(), objectLengthBytes)
            );
        }

        // Fixed a real gap (2026-09-09): this method used to persist nothing at all, so a client
        // that abandoned the upload after this point (crash, closed app, network failure, or
        // simply never calling completePresignedUpload) left its already-uploaded S3 object
        // permanently orphaned - nothing anywhere ever tracked or cleaned it up. Best-effort: a
        // failure to persist this tracking row must never block the upload ticket itself from
        // being issued, since real uploads working is more important than this cleanup mechanism.
        trackPendingPresignedUploadQuietly(fileId, authUserId);
        return new PresignedUploadTicket(fileId, presignedTransferService.presignUpload(fileId, sizeBytes, PRESIGNED_URL_EXPIRY));
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public StoredFileSummary completePresignedUpload(@NonNull final String authUserId, @NonNull final String fileId, @NonNull final String fileName,
                                                       @NonNull final String checksumSha256Hex, @Nullable final String folderId) {
        final PresignedTransferService presignedTransferService = requirePresignedTransferService();

        final long confirmedObjectBytes;
        try {
            confirmedObjectBytes = presignedTransferService.headObjectContentLength(fileId);
        } catch (final ObjectStorageException e) {
            throw new IllegalArgumentException("@CloudUserService.completePresignedUpload: no object uploaded yet under '" + fileId + "'", e);
        }

        // An encrypted ticket's pending row (see beginPresignedUpload) carries the wrapped content
        // key and the declared plaintext size; its absence means a legacy/unencrypted ticket whose
        // object is plaintext and whose real size is simply what the store confirmed.
        final PendingPresignedUpload pendingTicket = this.findPendingPresignedUpload(fileId).orElse(null);
        final String contentKeyHeaderBase64 = pendingTicket == null ? null : pendingTicket.getContentKeyHeaderBase64();

        final long realSizeBytes;
        if (contentKeyHeaderBase64 != null) {
            if (this.contentKeyService == null) {
                throw new IllegalStateException(
                        "@CloudUserService.completePresignedUpload: ticket '" + fileId
                                + "' was issued with a content key but no ContentKeyService is configured");
            }
            // The stored object is ciphertext; the recorded/quota-counted size is the plaintext's.
            // The declared size is only trusted because the object's confirmed length must equal
            // exactly what that size produces under the issued key's chunked scheme - an
            // under-declared (or tampered/truncated-in-flight) upload can't match and is deleted.
            final Long declaredSizeBytes = pendingTicket.getDeclaredSizeBytes();
            final int headerLengthBytes = Base64.getDecoder().decode(contentKeyHeaderBase64).length;
            final long expectedObjectBytes = declaredSizeBytes == null
                    ? -1 : this.contentKeyService.objectLength(headerLengthBytes, declaredSizeBytes);
            if (declaredSizeBytes == null || confirmedObjectBytes != expectedObjectBytes) {
                deleteOrphanedPresignedObjectQuietly(presignedTransferService, fileId);
                throw new IllegalArgumentException(
                        "@CloudUserService.completePresignedUpload: object under '" + fileId + "' is "
                                + confirmedObjectBytes + " bytes but the ticket's declared plaintext size ("
                                + declaredSizeBytes + ") requires exactly " + expectedObjectBytes + " - upload rejected");
            }
            realSizeBytes = declaredSizeBytes;
        } else {
            realSizeBytes = confirmedObjectBytes;
        }

        final ICloudUser cloudUser = this.getOrCreate(authUserId);
        if (cloudUser.isUploadLimitReached(realSizeBytes)) {
            deleteOrphanedPresignedObjectQuietly(presignedTransferService, fileId);
            recordMetric(MetricsRecorder::recordUploadQuotaRejected);
            throw new UploadQuotaExceededException(
                    authUserId, cloudUser.getCurrentUploadedBytes(), realSizeBytes, cloudUser.getMaxBytesToUpload());
        }
        // Sharing: deliberately owner-only - a grantee can never upload into a shared folder.
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        final Instant now = Instant.now();
        final StoredFile storedFile = new StoredFile(
                fileId, fileName, realSizeBytes, new FileChecksum(HashAlgorithm.SHA_256, checksumSha256Hex), now, now, fileId,
                contentKeyHeaderBase64
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

        // The real StoredFile now exists, so this ticket is no longer "abandoned" no matter what
        // happens next - best-effort only, since PendingPresignedUploadPurgeScheduler's own
        // real-StoredFile-existence check (see that class's Javadoc) already protects against
        // ever deleting this file's content even if this particular delete happens to fail.
        untrackPendingPresignedUploadQuietly(fileId);

        // Fixed a real bug (2026-09-08, reported as "uploaded file never shows up in Search"):
        // this method used to skip every one of uploadFile(...)'s own audit/search/webhook hooks
        // entirely - a presigned upload (what the desktop/mobile clients always try first, see
        // AWS_S3_IMPL.md section 8) persisted and counted against quota correctly, but was
        // permanently invisible to the audit log, the search index, and any registered webhook.
        // Content itself is never available here (it went straight to S3, this server never saw
        // the bytes) - indexFileForSearch is called with content = null, so the file is still
        // indexed by name/folder, just without a text extract.
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_UPLOAD, fileId, null));
        indexFileForSearch(authUserId, storedFile, folderId, null);
        // Content is likewise unavailable here, so this file is embedded by name alone - see
        // IntelligenceDocument#content()'s own Javadoc for that nullable case.
        indexFileForIntelligence(authUserId, storedFile, null);
        dispatchWebhookEvent(authUserId, WebhookEventType.FILE_UPLOADED, fileId);

        // Direct-transfer (presigned) content is never scanned - see ContentScanService's own
        // Javadoc: content scanning only triggers off a server-mediated upload's own INSERT.
        return new StoredFileSummary(fileId, fileName, storedFile.contentType(), realSizeBytes,
                now.toEpochMilli(), now.toEpochMilli(), folderId, ScanStatus.CLEAN.name());
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public PresignedDownloadTicket beginPresignedDownload(@NonNull final String authUserId, @NonNull final String storedFileId) {
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

        // Only a direct-transfer file's object is usable by the client end to end - plaintext for
        // a legacy one, or decryptable locally with the recovered content key below. A
        // server-encrypted-S3 or inline file's bytes would be an envelope the client has no way
        // to unwrap, so this reuses the same "not available, fall back" signal
        // beginPresignedUpload/completePresignedUpload use when nothing is configured at all; the
        // caller doesn't need to distinguish why, only that GET /files/{id}/content is the right
        // route for this particular file instead.
        if (!file.isDirectTransfer()) {
            throw new PresignedTransferUnavailableException();
        }

        final PresignedDownload download = presignedTransferService.presignDownload(file.objectStorageKey(), PRESIGNED_URL_EXPIRY);
        if (!file.isContentKeyProtected()) {
            return new PresignedDownloadTicket(download, null);
        }
        if (this.contentKeyService == null) {
            // The file needs a key this instance can't recover - same fall-back signal as above:
            // GET /files/{id}/content decrypts server-side and still serves the file correctly.
            throw new PresignedTransferUnavailableException();
        }
        final byte[] header = Base64.getDecoder().decode(file.contentKeyHeaderBase64());
        final byte[] keyMaterial;
        try {
            keyMaterial = this.contentKeyService.recoverContentKey(header);
        } catch (final KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException(
                    "@CloudUserService.beginPresignedDownload: failed to recover the content key for " + storedFileId, e);
        }
        return new PresignedDownloadTicket(download, new PresignedDownloadEncryption(
                keyMaterial, this.contentKeyService.associatedDataPrefix(file.fileId()), header.length
        ));
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
    private void deleteOrphanedPresignedObjectQuietly(final PresignedTransferService presignedTransferService, final String fileId) {
        try {
            presignedTransferService.deleteObject(fileId);
        } catch (final ObjectStorageException cleanupFailed) {
            CloudDriver.getInstance().getLogger().log(
                    Level.WARNING,
                    "@CloudUserService: failed to delete orphaned presigned-upload object for file '" + fileId + "'", cleanupFailed
            );
        }
        // The object (if it ever existed) is gone either way - drop the tracking row now rather
        // than waiting for PendingPresignedUploadPurgeScheduler's own retention window to elapse.
        untrackPendingPresignedUploadQuietly(fileId);
    }

    /**
     * Persists a {@link PendingPresignedUpload} row so {@code PendingPresignedUploadPurgeScheduler}
     * (cloud-driver-plugin) can eventually notice and clean up this ticket's S3 object if the
     * upload is ever abandoned - see that class's Javadoc, and {@link PendingPresignedUpload}'s
     * own Javadoc, for the full mechanism this closes a gap in. Best-effort: a failure to persist
     * this bookkeeping row must never block {@link #beginPresignedUpload} from returning a usable
     * ticket - a real upload succeeding matters more than this cleanup mechanism working.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}
     * @param authUserId the account the ticket was issued to
     */
    private void trackPendingPresignedUploadQuietly(final String fileId, final String authUserId) {
        try {
            this.dataFactory.register(new PendingPresignedUpload(fileId, authUserId, System.currentTimeMillis()));
        } catch (final DatabaseClientException | KeyWrapException trackingFailed) {
            CloudDriver.getInstance().getLogger().log(
                    Level.WARNING,
                    "@CloudUserService: failed to persist PendingPresignedUpload tracking row for file '" + fileId + "'", trackingFailed
            );
        }
    }

    /**
     * Persists an <em>encrypted</em> ticket's {@link PendingPresignedUpload} row - unlike {@link
     * #trackPendingPresignedUploadQuietly}, a failure here is rethrown and fails {@link
     * #beginPresignedUpload}: this row is the only durable carrier of the ticket's wrapped
     * content key (and declared size) between begin and complete, so silently losing it would
     * leave the client's uploaded ciphertext permanently undecryptable.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}
     * @param authUserId the account the ticket was issued to
     * @param contentKeyHeaderBase64 base64 of the issued content key's streaming header
     * @param declaredSizeBytes the plaintext size the client declared
     */
    private void trackPendingPresignedUpload(final String fileId, final String authUserId,
                                             final String contentKeyHeaderBase64, final long declaredSizeBytes) {
        try {
            this.dataFactory.register(new PendingPresignedUpload(
                    fileId, authUserId, System.currentTimeMillis(), contentKeyHeaderBase64, declaredSizeBytes));
        } catch (final DatabaseClientException | KeyWrapException trackingFailed) {
            throw new RuntimeException(
                    "@CloudUserService.beginPresignedUpload: failed to persist the ticket's content key for file '"
                            + fileId + "' - ticket not issued", trackingFailed
            );
        }
    }

    /**
     * Looks up {@code fileId}'s {@link PendingPresignedUpload} tracking row, if it still exists -
     * how {@link #completePresignedUpload} recovers the ticket's content key and declared size.
     */
    private Optional<PendingPresignedUpload> findPendingPresignedUpload(final String fileId) {
        try {
            return this.dataFactory.findById(fileId, PendingPresignedUpload.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException(
                    "@CloudUserService.completePresignedUpload: failed to look up the pending ticket for '" + fileId + "'", e);
        }
    }

    /**
     * Deletes the {@link PendingPresignedUpload} tracking row for {@code fileId}, if any - called
     * once an upload attempt under that id is no longer "abandoned" one way or another (either it
     * completed successfully, or it was rolled back and its S3 object already removed). Best-effort:
     * a failure here never blocks the caller, since {@code PendingPresignedUploadPurgeScheduler}
     * only ever deletes an S3 object after independently confirming no real {@code StoredFile}
     * exists under the same id - a stale tracking row surviving this delete is a wasted future
     * lookup, never a data-loss risk.
     *
     * @param fileId the ticket's {@link PresignedUploadTicket#fileId()}
     */
    private void untrackPendingPresignedUploadQuietly(final String fileId) {
        try {
            this.dataFactory.delete(fileId, PendingPresignedUpload.class);
        } catch (final DatabaseClientException alreadyGoneOrOther) {
            // best-effort only - see this method's own Javadoc
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
        // Sharing: deliberately does NOT include files shared with authUserId - a caller
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
                resolved.getSizeBytes(), resolved.getCreatedAtEpochMilli(), resolved.getUpdatedAtEpochMilli(), resolved.getFolderId(),
                resolved.resolvedScanStatus());
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
     * and an inherited {@link SharedFolderGrant} on any of its ancestor folders. This is the
     * <em>only</em> place sharing is honored -
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
        final StoredFileOwnership ownership = this.requireFileAccess(authUserId, storedFileId, "getFile");
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
     * {@link ICloudUserService#checkFileAccess}: the same ownership-or-share check {@link #getFile}
     * performs, without ever touching {@link #fileFactory}/resolving content.
     */
    @Override
    public void checkFileAccess(@NonNull final String authUserId, @NonNull final String storedFileId) {
        this.requireFileAccess(authUserId, storedFileId, "checkFileAccess");
    }

    /**
     * Shared ownership-or-share check backing both {@link #getFile} and {@link #checkFileAccess} -
     * see {@link #getFile}'s own Javadoc for the exact rule this applies (plain ownership first,
     * falling back to {@link #requireSharedFileAccess}; a trashed file is treated as inaccessible
     * either way, the same "don't confirm existence" idiom every unowned/unshared file already gets).
     *
     * @param callerMethodName the public method name to attribute a thrown exception's message to
     * @throws IllegalArgumentException if {@code storedFileId} isn't owned by {@code authUserId} and isn't shared with them either
     */
    private StoredFileOwnership requireFileAccess(final String authUserId, final String storedFileId, final String callerMethodName) {
        final StoredFileOwnership ownership = this.tryOwnedFile(authUserId, storedFileId)
                .orElseGet(() -> this.requireSharedFileAccess(authUserId, storedFileId));
        if (ownership.isDeleted()) {
            throw new IllegalArgumentException(
                    "@CloudUserService." + callerMethodName + ": " + authUserId + " does not own or have shared access to " + storedFileId);
        }
        // Content scanning - a cheap metadata-only fetch (no
        // content resolution/decompression), checked after ownership/trash but before this method
        // returns, so every caller (getFile, checkFileAccess - and therefore every route built on
        // either, thumbnail generation included) uniformly refuses content access to a
        // still-scanning or flagged file, without each caller having to remember to check this
        // itself.
        this.findStoredFileMetadata(storedFileId).ifPresent(file -> {
            final ScanStatus scanStatus = file.scanStatus();
            if (scanStatus != ScanStatus.CLEAN) {
                throw new FileScanBlockedException(scanStatus);
            }
        });
        return ownership;
    }

    /**
     * Grants {@code granteeEmail}'s account read-only access to {@code fileId} - see {@link
     * ICloudUserService#shareFile}'s Javadoc. Owner-only: sharing is itself treated as a mutation
     * of the file's grant state, so this calls {@link #requireOwnedFile} directly, never {@link
     * #requireSharedFileAccess} - a grantee can never re-share what was shared with them.
     */
    @Override
    public void shareFile(@NonNull final String ownerAuthUserId, @NonNull final String fileId, @NonNull final String granteeEmail) {
        this.shareFile(ownerAuthUserId, fileId, granteeEmail, SharePermission.VIEW, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shareFile(@NonNull final String ownerAuthUserId, @NonNull final String fileId, @NonNull final String granteeEmail,
                           @NonNull final SharePermission permissionLevel, @Nullable final Long expiresAtEpochMillis) {
        final StoredFileOwnership ownership = this.requireOwnedFile(ownerAuthUserId, fileId);
        if (ownership.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.shareFile: cannot share trashed file " + fileId);
        }
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        if (granteeAuthUserId.equals(ownerAuthUserId)) {
            throw new IllegalArgumentException("@CloudUserService.shareFile: cannot share a file with its own owner");
        }
        try {
            this.dataFactory.register(new SharedFileGrant(granteeAuthUserId, fileId, ownerAuthUserId, permissionLevel, expiresAtEpochMillis));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.shareFile: failed to persist grant for " + fileId + " to " + granteeEmail, e);
        }
        dispatchWebhookEvent(ownerAuthUserId, WebhookEventType.FILE_SHARED, fileId);
        // Keeps the vector store's ownership hint current across sharing changes - see
        // refreshIntelligenceOwner's own Javadoc for why that matters now that duplicate
        // detection is scoped per account, and why it never was a search-security issue.
        refreshIntelligenceOwner(fileId, ownerAuthUserId);
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
        // The counterpart to shareFile's own refresh - see refreshIntelligenceOwner.
        refreshIntelligenceOwner(fileId, ownerAuthUserId);
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
            grants = this.dataFactory.getEntitiesByIndex(SharedFileGrant.class, SharedFileGrant.INDEX_GRANTEE_AUTH_USER_ID, authUserId).stream()
                    .filter(grant -> !grant.isExpired())
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
        this.shareFolder(ownerAuthUserId, folderId, granteeEmail, SharePermission.VIEW, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void shareFolder(@NonNull final String ownerAuthUserId, @NonNull final String folderId, @NonNull final String granteeEmail,
                             @NonNull final SharePermission permissionLevel, @Nullable final Long expiresAtEpochMillis) {
        final Folder folder = this.requireOwnedFolder(ownerAuthUserId, folderId);
        if (folder.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.shareFolder: cannot share trashed folder " + folderId);
        }
        final String granteeAuthUserId = this.resolveGranteeAuthUserId(granteeEmail);
        if (granteeAuthUserId.equals(ownerAuthUserId)) {
            throw new IllegalArgumentException("@CloudUserService.shareFolder: cannot share a folder with its own owner");
        }
        try {
            this.dataFactory.register(new SharedFolderGrant(granteeAuthUserId, folderId, ownerAuthUserId, permissionLevel, expiresAtEpochMillis));
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
            grants = this.dataFactory.getEntitiesByIndex(SharedFolderGrant.class, SharedFolderGrant.INDEX_GRANTEE_AUTH_USER_ID, authUserId).stream()
                    .filter(grant -> !grant.isExpired())
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

    /** {@inheritDoc} */
    @NonNull
    @Override
    public PublicFileLinkSummary createPublicFileLink(@NonNull final String ownerAuthUserId, @NonNull final String fileId,
                                                        @Nullable final Long expiresAtEpochMillis) {
        final StoredFileOwnership ownership = this.requireOwnedFile(ownerAuthUserId, fileId);
        if (ownership.isDeleted()) {
            throw new IllegalArgumentException("@CloudUserService.createPublicFileLink: cannot create a public link for trashed file " + fileId);
        }
        final PublicShareLink link = new PublicShareLink(fileId, ownerAuthUserId, expiresAtEpochMillis);
        try {
            this.dataFactory.register(link);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.createPublicFileLink: failed to persist link for " + fileId, e);
        }
        return new PublicFileLinkSummary(link.getToken(), link.getCreatedAtEpochMillis(), link.getExpiresAtEpochMillis());
    }

    /** {@inheritDoc} */
    @Override
    public void revokePublicFileLink(@NonNull final String ownerAuthUserId, @NonNull final String fileId, @NonNull final String token) {
        this.requireOwnedFile(ownerAuthUserId, fileId);
        try {
            final Optional<PublicShareLink> link = this.dataFactory.findById(token, PublicShareLink.class);
            if (link.isEmpty() || !link.get().getOwnerAuthUserId().equals(ownerAuthUserId) || !link.get().getStoredFileId().equals(fileId)) {
                return;
            }
            this.dataFactory.delete(token, PublicShareLink.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokePublicFileLink: failed to revoke link " + token + " for " + fileId, e);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public List<PublicFileLinkSummary> listPublicFileLinks(@NonNull final String ownerAuthUserId, @NonNull final String fileId) {
        this.requireOwnedFile(ownerAuthUserId, fileId);
        try {
            return this.dataFactory.getEntitiesByIndex(PublicShareLink.class, PublicShareLink.INDEX_STORED_FILE_ID, fileId).stream()
                    .filter(link -> link.getOwnerAuthUserId().equals(ownerAuthUserId))
                    .filter(link -> !link.isExpired())
                    .map(link -> new PublicFileLinkSummary(link.getToken(), link.getCreatedAtEpochMillis(), link.getExpiresAtEpochMillis()))
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listPublicFileLinks: failed to list links for " + fileId, e);
        }
    }

    /** {@inheritDoc} */
    @NonNull
    @Override
    public StoredFile resolvePublicFileLink(@NonNull final String token) {
        final PublicShareLink link;
        try {
            link = this.dataFactory.findById(token, PublicShareLink.class).orElseThrow(PublicShareLinkInvalidException::new);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.resolvePublicFileLink: failed to look up link " + token, e);
        }
        if (link.isExpired()) {
            throw new PublicShareLinkInvalidException();
        }

        final Optional<StoredFileOwnership> ownership;
        try {
            ownership = this.dataFactory.findById(
                    StoredFileOwnership.compositeKey(link.getOwnerAuthUserId(), link.getStoredFileId()), StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.resolvePublicFileLink: failed to resolve owner of " + link.getStoredFileId(), e);
        }
        // The owner has since trashed/removed the file - the link row may still exist, but there's
        // nothing left to serve. Same exception as every other invalid-token case - don't leak which.
        if (ownership.isEmpty() || ownership.get().isDeleted()) {
            throw new PublicShareLinkInvalidException();
        }

        try {
            return this.fileFactory.findById(link.getStoredFileId()).orElseThrow(PublicShareLinkInvalidException::new);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.resolvePublicFileLink: failed to fetch content for " + link.getStoredFileId(), e);
        }
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
            return this.dataFactory.getEntitiesByIndex(SharedFileGrant.class, SharedFileGrant.INDEX_STORED_FILE_ID, fileId).stream()
                    .filter(grant -> grant.getOwnerAuthUserId().equals(ownerAuthUserId))
                    .filter(grant -> !grant.isExpired())
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
            return this.dataFactory.getEntitiesByIndex(SharedFolderGrant.class, SharedFolderGrant.INDEX_FOLDER_ID, folderId).stream()
                    .filter(grant -> grant.getOwnerAuthUserId().equals(ownerAuthUserId))
                    .filter(grant -> !grant.isExpired())
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
            return (int) this.dataFactory.getEntitiesByIndex(SharedFileGrant.class, SharedFileGrant.INDEX_OWNER_AUTH_USER_ID, authUserId).stream()
                    .filter(grant -> !grant.isExpired())
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
            grants = this.dataFactory.getEntitiesByIndex(SharedFileGrant.class, SharedFileGrant.INDEX_STORED_FILE_ID, fileId).stream()
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
     * Deletes every {@link PublicShareLink} still pointing at {@code fileId} - the public-link
     * counterpart to {@link #revokeAllFileShares},
     * called from the exact same two places (soft delete via {@link #deleteFile}, permanent
     * removal via {@link #hardDeleteFile}) and for the same reason: {@link #resolvePublicFileLink}
     * already denies access to a trashed/removed file's link defensively, but leaving the row
     * itself behind would let it silently start working again the moment the file is restored,
     * without the owner ever choosing to re-share it that way. Idempotent - a no-op if no links exist.
     *
     * @param fileId the file whose outstanding public links (if any) to revoke
     */
    private void revokeAllPublicFileLinks(final String fileId) {
        final List<PublicShareLink> links;
        try {
            links = this.dataFactory.getEntitiesByIndex(PublicShareLink.class, PublicShareLink.INDEX_STORED_FILE_ID, fileId).stream()
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.revokeAllPublicFileLinks: failed to list links for " + fileId, e);
        }
        for (final PublicShareLink link : links) {
            try {
                this.dataFactory.delete(link.getToken(), PublicShareLink.class);
            } catch (final DatabaseClientException e) {
                throw new RuntimeException("@CloudUserService.revokeAllPublicFileLinks: failed to revoke a link for " + fileId, e);
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
            grants = this.dataFactory.getEntitiesByIndex(SharedFolderGrant.class, SharedFolderGrant.INDEX_FOLDER_ID, folderId).stream()
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
            ownerOwnership = this.dataFactory.getEntitiesByIndex(StoredFileOwnership.class, StoredFileOwnership.INDEX_STORED_FILE_ID, storedFileId).stream()
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "@CloudUserService.requireSharedFileAccess: no such file " + storedFileId));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireSharedFileAccess: failed to resolve owner of " + storedFileId, e);
        }

        try {
            // Expiring shares - an expired grant is treated as
            // if it doesn't exist at all, the same "don't distinguish, just deny" idiom a trashed
            // StoredFileOwnership row already gets.
            if (this.dataFactory.findById(SharedFileGrant.compositeKey(authUserId, storedFileId), SharedFileGrant.class)
                    .filter(grant -> !grant.isExpired()).isPresent()) {
                return ownerOwnership;
            }
            String currentFolderId = ownerOwnership.getFolderId();
            while (currentFolderId != null) {
                if (this.dataFactory.findById(SharedFolderGrant.compositeKey(authUserId, currentFolderId), SharedFolderGrant.class)
                        .filter(grant -> !grant.isExpired()).isPresent()) {
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
                // Expiring shares - see requireSharedFileAccess's own comment.
                if (this.dataFactory.findById(SharedFolderGrant.compositeKey(authUserId, currentFolderId), SharedFolderGrant.class)
                        .filter(grant -> !grant.isExpired()).isPresent()) {
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

        // Sharing: deliberately owner-only - requireOwnedFile, never requireSharedFileAccess.
        // A read-only grant must never let a grantee move a file it doesn't own.
        final StoredFileOwnership existing = this.requireOwnedFile(authUserId, storedFileId);
        if (folderId != null) this.requireOwnedFolder(authUserId, folderId);

        try {
            this.dataFactory.update(existing.movedTo(folderId));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.moveFile: failed to move " + storedFileId + " to folder " + folderId, e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_MOVE, storedFileId, folderId));
        updateSearchIndexMetadata(authUserId, storedFileId, existing.getFileName(), folderId);
    }

    /**
     * Renames {@code storedFileId} to {@code newFileName} - unlike {@link #moveFile}, this
     * touches the actual {@link StoredFile} entity itself (its {@link StoredFile#fileName()}, and
     * therefore {@link StoredFile#contentType()}, live only there - {@link StoredFileOwnership}
     * only ever holds a cached copy for cheap listing), not just the cheap ownership row - so,
     * unlike a move, this pays the full {@link DataFactory#update} cost of rewriting the file
     * entity (a full re-encrypt for an inline file; a cheap metadata-only rewrite for an
     * S3-backed one, since its persisted row never carries its actual content - see {@link
     * StoredFile}'s own Javadoc). {@link StoredFileOwnership}'s own cached {@code fileName}/{@code
     * contentType} are updated in the same call via {@link StoredFileOwnership#withMetadata(StoredFile)},
     * so every listing (which reads that cached copy, never the underlying file directly) reflects
     * the new name immediately too - without this second update, a listing and a direct
     * fetch/download would disagree about the file's own name.
     *
     * @param authUserId the requesting user's id, checked against the ownership record
     * @param storedFileId the file to rename
     * @param newFileName the file's new display name
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void renameFile(@NonNull final String authUserId, @NonNull final String storedFileId, @NonNull final String newFileName) {
        // Sharing: deliberately owner-only - a grantee can read a shared file but never rename it.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);

        final StoredFile renamed;
        try {
            final StoredFile existing = this.fileFactory.findById(storedFileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "@CloudUserService.renameFile: owned file not found: " + storedFileId));
            renamed = existing.renamedTo(newFileName);
            this.dataFactory.update(renamed);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.renameFile: failed to rename " + storedFileId, e);
        }

        try {
            this.dataFactory.update(ownership.withMetadata(renamed));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.renameFile: failed to update cached metadata for " + storedFileId, e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_RENAME, storedFileId, newFileName));
        updateSearchIndexMetadata(authUserId, storedFileId, newFileName, ownership.getFolderId());
    }

    /**
     * Overwrites {@code storedFileId}'s content in place - see {@link
     * ICloudUserService#replaceFileContent}'s own Javadoc for the full contract. Ownership-checked
     * the same owner-only way {@link #renameFile} is; the size-increase-only quota check mirrors
     * {@link #uploadFile(String, String, byte[], String)}'s own (checked before ever fetching the
     * existing file, for the same "don't pay for a rejected write" reasoning).
     *
     * @param authUserId the requesting user's id, checked against the ownership record
     * @param storedFileId the file to overwrite
     * @param newContent the file's new raw bytes
     * @return a {@link StoredFileSummary} of the updated file
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     * @throws IllegalStateException if {@code storedFileId} is a per-account deduplication alias
     *     or is itself aliased by another of the account's own
     *     files - overwriting either would silently corrupt content another "file" still relies on;
     *     duplicate the file first (breaking the alias relationship) if it genuinely needs its own,
     *     independent content
     * @throws UploadQuotaExceededException if the size increase would exceed {@code authUserId}'s upload quota
     */
    @NonNull
    @Override
    public StoredFileSummary replaceFileContent(@NonNull final String authUserId, @NonNull final String storedFileId, final byte[] newContent) {
        return this.replaceFileContent(authUserId, storedFileId, newContent, null);
    }

    /**
     * {@inheritDoc}
     */
    @NonNull
    @Override
    public StoredFileSummary replaceFileContent(@NonNull final String authUserId, @NonNull final String storedFileId,
                                                 final byte[] newContent, @Nullable final Long expectedUpdatedAtEpochMillis) {
        // Sharing (EDIT-level shares): the owner, or a
        // grantee holding a direct, non-expired SharePermission.EDIT grant on this exact file, may
        // overwrite its content - see requireEditableFileAccess's own Javadoc. The returned
        // ownership row always belongs to the file's real owner, never the calling grantee -
        // quota/usage/search-indexing below are all charged to that owner, not the caller, since
        // the owner is who actually stores the bytes.
        final StoredFileOwnership ownership = this.requireEditableFileAccess(authUserId, storedFileId);
        final String ownerAuthUserId = ownership.getAuthUserId();

        final StoredFile existing;
        try {
            existing = this.fileFactory.findById(storedFileId)
                    .orElseThrow(() -> new IllegalStateException(
                            "@CloudUserService.replaceFileContent: owned file not found: " + storedFileId));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException e) {
            throw new RuntimeException("@CloudUserService.replaceFileContent: failed to look up " + storedFileId, e);
        }

        // Optimistic concurrency. Checked before the
        // dedup guard/quota check/version capture below, since a detected conflict skips all of
        // that entirely: the canonical file is never touched, only a new "conflicted copy" is
        // created (via the ordinary uploadFile path, in the same folder). Uploaded under
        // ownerAuthUserId, not the calling authUserId - the same "charged to whoever actually
        // stores the bytes" reasoning the quota check below already applies, and the only way this
        // works at all for an EDIT-grantee caller (who doesn't own, and therefore couldn't upload
        // into, ownership.getFolderId() themselves) - the conflicted copy lands in the file
        // owner's own account, alongside the original, where they can reconcile it.
        if (expectedUpdatedAtEpochMillis != null && existing.updatedAt().toEpochMilli() != expectedUpdatedAtEpochMillis) {
            final String conflictName = conflictedCopyFileName(existing.fileName());
            final StoredFile conflictFile = this.uploadFile(ownerAuthUserId, conflictName, newContent, ownership.getFolderId());
            throw new SyncConflictException(new StoredFileSummary(conflictFile.fileId(), conflictFile.fileName(),
                    conflictFile.contentType(), conflictFile.sizeBytes(), conflictFile.createdAt().toEpochMilli(),
                    conflictFile.updatedAt().toEpochMilli(), ownership.getFolderId(), conflictFile.scanStatus().name()));
        }

        // Per-account deduplication - see this method's own
        // @throws Javadoc above for why neither shape is safe to overwrite in place.
        if (existing.isDedupAlias() || existing.dedupRefCount() > 0) {
            throw new IllegalStateException(
                    "@CloudUserService.replaceFileContent: " + storedFileId + " shares content with another file "
                            + "via per-account deduplication - duplicate it first before replacing its content");
        }

        final long delta = newContent.length - existing.sizeBytes();
        if (delta > 0) {
            final ICloudUser cloudUser = this.getOrCreate(ownerAuthUserId);
            if (cloudUser.isUploadLimitReached(delta)) {
                recordMetric(MetricsRecorder::recordUploadQuotaRejected);
                throw new UploadQuotaExceededException(ownerAuthUserId, cloudUser.getCurrentUploadedBytes(), delta, cloudUser.getMaxBytesToUpload());
            }
        }

        // Only point at which the about-to-be-overwritten content is still available - see
        // FileVersioningService#captureVersion's own Javadoc for why this must happen here,
        // synchronously, before the write below, rather than via any after-the-fact notification.
        captureFileVersion(storedFileId, existing);

        final StoredFile replaced = new StoredFile(
                storedFileId, existing.fileName(), newContent,
                FileChecksum.of(HashAlgorithm.SHA_256, newContent), existing.createdAt(), Instant.now()
        );
        try {
            this.fileFactory.upload(replaced);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.replaceFileContent: failed to persist new content for " + storedFileId, e);
        }

        try {
            this.dataFactory.update(ownership.withMetadata(replaced));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.replaceFileContent: failed to update cached metadata for " + storedFileId, e);
        }

        this.updateCloudUserBytesUsage(ownerAuthUserId, delta);
        // The audit entry's actor is the real caller (possibly an EDIT-grantee, not the owner) -
        // this is "who did this", unlike the quota/usage charge above, which is "whose storage".
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_CONTENT_REPLACED, storedFileId, null));
        indexFileForSearch(ownerAuthUserId, replaced, ownership.getFolderId(), newContent);
        indexFileForIntelligence(ownerAuthUserId, replaced, newContent);

        return new StoredFileSummary(replaced.fileId(), replaced.fileName(), replaced.contentType(), replaced.sizeBytes(),
                replaced.createdAt().toEpochMilli(), replaced.updatedAt().toEpochMilli(), ownership.getFolderId(),
                replaced.scanStatus().name());
    }

    /** {@code yyyy-MM-dd HHmmss}, system default zone - matches this codebase's own client-side date-formatting convention (e.g. {@code cloud-driver-platforms-desktop}'s "Restore all"/"Deleted on" timestamps), just applied server-side for {@link #conflictedCopyFileName}. */
    private static final DateTimeFormatter CONFLICTED_COPY_TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HHmmss").withZone(ZoneId.systemDefault());

    /**
     * Builds a "conflicted copy" file name from {@code originalFileName} - {@code "name (conflicted
     * copy yyyy-MM-dd HHmmss).ext"}, inserted before the extension (matching {@link
     * StoredFile}'s own extension-inference convention) so the copy still carries a recognizable
     * type. No collision-avoidance beyond the timestamp itself - a second conflict on the exact
     * same file within the same second is vanishingly unlikely, and {@link #uploadFile} places
     * files by id, not name, so an exact name collision wouldn't fail regardless.
     */
    private static String conflictedCopyFileName(final String originalFileName) {
        final int dotIndex = originalFileName.lastIndexOf('.');
        final String suffix = " (conflicted copy " + CONFLICTED_COPY_TIMESTAMP_FORMAT.format(Instant.now()) + ")";
        if (dotIndex <= 0 || dotIndex == originalFileName.length() - 1) {
            return originalFileName + suffix;
        }
        return originalFileName.substring(0, dotIndex) + suffix + originalFileName.substring(dotIndex);
    }

    /**
     * Resolves write access to {@code storedFileId} for {@code authUserId} - either the file's
     * real owner, or an account holding a direct (never folder-inherited - see {@link
     * SharePermission}'s own Javadoc), non-expired {@link SharePermission#EDIT} {@link
     * SharedFileGrant} on this exact file. Backs {@link #replaceFileContent} only - every other
     * mutating method on this class stays strictly owner-only, unaffected by this new access path.
     *
     * @param authUserId the requesting user's id - checked for ownership first, then for a direct EDIT grant
     * @param storedFileId the file write access is being requested for
     * @return the file's real owner's {@link StoredFileOwnership} row (never {@code authUserId}'s own, unless they are the owner)
     * @throws IllegalArgumentException if {@code storedFileId} isn't owned by {@code authUserId},
     *     is currently trashed, and {@code authUserId} doesn't hold a valid, non-expired {@link
     *     SharePermission#EDIT} grant on it either
     */
    private StoredFileOwnership requireEditableFileAccess(final String authUserId, final String storedFileId) {
        final Optional<StoredFileOwnership> owned = this.tryOwnedFile(authUserId, storedFileId);
        if (owned.isPresent()) {
            final StoredFileOwnership ownership = owned.get();
            if (ownership.isDeleted()) {
                throw new IllegalArgumentException("@CloudUserService.requireEditableFileAccess: " + storedFileId + " is trashed");
            }
            return ownership;
        }

        final Optional<SharedFileGrant> grant;
        try {
            grant = this.dataFactory.findById(SharedFileGrant.compositeKey(authUserId, storedFileId), SharedFileGrant.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireEditableFileAccess: failed to look up grant for " + storedFileId, e);
        }
        if (grant.isEmpty() || grant.get().isExpired() || grant.get().permissionLevel() != SharePermission.EDIT) {
            throw new IllegalArgumentException(
                    "@CloudUserService.requireEditableFileAccess: " + authUserId + " does not have edit access to " + storedFileId);
        }

        final String ownerAuthUserId = grant.get().getOwnerAuthUserId();
        final Optional<StoredFileOwnership> ownerOwnership;
        try {
            ownerOwnership = this.dataFactory.findById(
                    StoredFileOwnership.compositeKey(ownerAuthUserId, storedFileId), StoredFileOwnership.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.requireEditableFileAccess: failed to resolve owner of " + storedFileId, e);
        }
        if (ownerOwnership.isEmpty() || ownerOwnership.get().isDeleted()) {
            // The owner has since trashed/removed the file - the grant nominally still exists, but
            // there's nothing left to edit. Same message as the "no grant at all" case above -
            // don't leak which.
            throw new IllegalArgumentException(
                    "@CloudUserService.requireEditableFileAccess: " + authUserId + " does not have edit access to " + storedFileId);
        }
        return ownerOwnership.get();
    }

    /**
     * Forwards {@code sourceFileId}'s about-to-be-overwritten content to {@link
     * CloudDriver#getInstance()}'s {@link FileVersioningService}, if {@code
     * cloud-driver-extensions-versioning} has published one - a no-op otherwise. Never throws:
     * a missing/misbehaving versioning sink must never block a real content replacement, matching
     * {@link #recordMetric}'s own defensive shape immediately below.
     *
     * @param sourceFileId the file about to be overwritten
     * @param previousContent its full state immediately before the overwrite
     */
    private static void captureFileVersion(final String sourceFileId, final StoredFile previousContent) {
        try {
            final FileVersioningService versioningService = CloudDriver.getInstance().getServiceContainer().getFileVersioningService();
            if (versioningService != null) versioningService.captureVersion(sourceFileId, previousContent);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * Content types indexed as text (search indexing) -
     * mirrors {@code cloud-driver-platforms-desktop}'s own {@code PreviewSupport.kt#previewKindFor}
     * {@code TEXT} classification (any {@code text/*} content type, plus {@code application/json}/
     * {@code xml}/{@code yaml}/{@code toml}) - v1 scope: OCR for
     * images and PDF/DOCX text extraction are explicit follow-ups, not attempted here.
     */
    private static final Set<String> NON_TEXT_PREFIX_INDEXABLE_CONTENT_TYPES =
            Set.of("application/json", "application/xml", "application/yaml", "application/toml");

    /**
     * Caps how much of a file's own text this class ever hands to {@link SearchIndexService} -
     * an in-memory index (see {@code InMemorySearchIndexService}'s own Javadoc) has no reason to
     * hold an entire, possibly very large, text file's content just to make it searchable; a
     * bounded prefix is more than enough for a filename/content-substring match. 64 KiB.
     */
    private static final int MAX_INDEXED_TEXT_BYTES = 65_536;

    /**
     * Extracts a bounded, UTF-8-decoded text prefix of {@code content} if {@code contentType} is
     * one of {@link #NON_TEXT_PREFIX_INDEXABLE_CONTENT_TYPES}/{@code text/*}, otherwise {@code
     * null} (not indexed at all in v1 - see that constant's own Javadoc).
     *
     * @param contentType the file's {@link StoredFile#contentType()}
     * @param content the file's raw, uncompressed bytes
     * @return a bounded text extract, or {@code null} if this content type isn't text-indexed
     */
    private static String extractIndexableText(final String contentType, final byte[] content) {
        final boolean indexable = contentType.startsWith("text/") || NON_TEXT_PREFIX_INDEXABLE_CONTENT_TYPES.contains(contentType);
        if (!indexable) return null;

        final int length = Math.min(content.length, MAX_INDEXED_TEXT_BYTES);
        return new String(content, 0, length, StandardCharsets.UTF_8);
    }

    /**
     * Indexes (or re-indexes) {@code storedFile} into {@link CloudDriver#getInstance()}'s {@link
     * SearchIndexService}, if {@code cloud-driver-extensions-search} has published one - a no-op
     * otherwise. Never throws: a missing/misbehaving search sink must never block a real upload/
     * content replacement, matching {@link #captureFileVersion}'s own defensive shape.
     *
     * @param authUserId the owning account
     * @param storedFile the file's current state, already persisted
     * @param folderId the folder the file was placed in, or {@code null} for the root
     * @param content the file's raw, uncompressed bytes, to extract indexable text from - {@code
     * null} if this server never had the bytes in hand at all (a direct-transfer/presigned upload,
     * see {@link #completePresignedUpload}), in which case the file is still indexed by name/
     * folder, just with no text extract
     */
    private static void indexFileForSearch(final String authUserId, final StoredFile storedFile, final String folderId, final byte[] content) {
        try {
            final SearchIndexService searchIndexService = CloudDriver.getInstance().getServiceContainer().getSearchIndexService();
            if (searchIndexService == null) return;
            final String extractedText = content == null ? null : extractIndexableText(storedFile.contentType(), content);
            searchIndexService.indexFile(new SearchDocument(authUserId, storedFile.fileId(), storedFile.fileName(), folderId, extractedText));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * Updates only the indexed {@code fileName}/{@code folderId} for an already-indexed file
     * (see {@link SearchIndexService#updateMetadata}) - called by {@link #renameFile}/{@link
     * #moveFile}, neither of which have the file's content in hand. A no-op (including if {@code
     * fileName} is {@code null} - a legacy {@link StoredFileOwnership} row with no cached
     * metadata, see {@link StoredFileOwnership#hasMetadata()}) if the search extension isn't
     * running, matching {@link #indexFileForSearch}'s own defensive shape.
     */
    private static void updateSearchIndexMetadata(final String authUserId, final String storedFileId, final String fileName, final String folderId) {
        if (fileName == null) return;
        try {
            final SearchIndexService searchIndexService = CloudDriver.getInstance().getServiceContainer().getSearchIndexService();
            if (searchIndexService != null) searchIndexService.updateMetadata(authUserId, storedFileId, fileName, folderId);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see indexFileForSearch's own Javadoc.
        }
    }

    /**
     * Removes {@code storedFileId} from the search index, if one is published - called by {@link
     * #deleteFile} (trash) and unconditionally by {@link #hardDeleteFile} (permanent removal,
     * idempotent alongside the trash case - see that method's own comment). A no-op if the search
     * extension isn't running, matching {@link #indexFileForSearch}'s own defensive shape.
     */
    private static void removeFromSearchIndex(final String authUserId, final String storedFileId) {
        try {
            final SearchIndexService searchIndexService = CloudDriver.getInstance().getServiceContainer().getSearchIndexService();
            if (searchIndexService != null) searchIndexService.removeFile(authUserId, storedFileId);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see indexFileForSearch's own Javadoc.
        }
    }

    /**
     * Re-indexes {@code storedFileId} after {@link #restoreFile} brings it back out of the trash -
     * unlike {@link #updateSearchIndexMetadata}, this fetches the file's full, resolved content
     * (via {@link #fileFactory}) to re-extract indexable text, since restoring is a deliberate,
     * occasional user action that can afford the extra cost, unlike a rename/move. A no-op if the
     * search extension isn't running, or if the fetch itself fails for any reason - restoring the
     * file itself must never be blocked by a reindex failure, matching {@link #indexFileForSearch}'s
     * own defensive shape.
     */
    private void reindexRestoredFileForSearch(final String authUserId, final String storedFileId, final String folderId) {
        try {
            final SearchIndexService searchIndexService = CloudDriver.getInstance().getServiceContainer().getSearchIndexService();
            if (searchIndexService == null) return;
            final StoredFile restored = this.fileFactory.findById(storedFileId).orElse(null);
            if (restored == null) return;
            final String extractedText = extractIndexableText(restored.contentType(), restored.content());
            searchIndexService.restoreFile(new SearchDocument(authUserId, storedFileId, restored.fileName(), folderId, extractedText));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException ignored) {
            // Same reasoning - a failed re-fetch must never block the restore itself.
        }
    }

    /**
     * Notifies {@link CloudDriver#getInstance()}'s {@link WebhookService}, if {@code
     * cloud-driver-extensions-webhooks} has published one - a no-op otherwise. Never throws: a
     * missing/misbehaving webhook dispatcher must never block a real file operation, matching
     * {@link #indexFileForSearch}'s own defensive shape. {@link WebhookService#dispatchEvent}
     * itself is cheap (a quick per-account scan + submitting async tasks) - the real HTTP delivery
     * always happens on that service's own background workers, never this calling thread.
     *
     * @param authUserId the account whose webhooks to notify
     * @param eventType the event that occurred
     * @param targetId the {@link StoredFile#fileId()} the event concerns
     */
    private static void dispatchWebhookEvent(final String authUserId, final WebhookEventType eventType, final String targetId) {
        try {
            final WebhookService webhookService = CloudDriver.getInstance().getServiceContainer().getWebhookService();
            if (webhookService != null) webhookService.dispatchEvent(authUserId, eventType, targetId);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }


    /**
     * Embeds {@code storedFile} into {@link CloudDriver#getInstance()}'s {@link
     * IntelligenceService}, if {@code cloud-driver-extensions-intelligence} has published one - a
     * no-op otherwise. Never throws, matching {@link #indexFileForSearch}'s own defensive shape:
     * an unreachable embedding service must never block a real upload/content replacement.
     *
     * <p>Unlike {@link #indexFileForSearch}, no text is extracted here and no content type is
     * filtered out - the raw bytes are handed over as-is and the Python service decides what is
     * embeddable (see {@link IntelligenceDocument}'s own Javadoc for that deliberate split).
     *
     * @param authUserId the owning account
     * @param storedFile the file's current state, already persisted
     * @param content the file's raw, uncompressed bytes, or {@code null} if this server never held
     * them (a direct-transfer/presigned upload), in which case only the file name is embeddable
     */
    private static void indexFileForIntelligence(final String authUserId, final StoredFile storedFile, final byte[] content) {
        try {
            final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
            if (intelligenceService == null) return;
            intelligenceService.indexAsync(new IntelligenceDocument(
                    authUserId, storedFile.fileId(), storedFile.fileName(), storedFile.contentType(), content));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        }
    }

    /**
     * Removes {@code storedFileId}'s vector, if an {@link IntelligenceService} is published -
     * called by {@link #deleteFile} (trash) and unconditionally by {@link #hardDeleteFile}
     * (permanent removal), the same idempotent pair {@link #removeFromSearchIndex} already
     * handles. Takes no {@code authUserId}: the vector store is keyed by file id alone, and its
     * own record of an owner is never authoritative anyway (see {@link IntelligenceService}'s
     * security-invariant section).
     */
    private static void removeFromIntelligenceIndex(final String storedFileId) {
        try {
            final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
            if (intelligenceService != null) intelligenceService.removeAsync(storedFileId);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see indexFileForIntelligence's own Javadoc.
        }
    }

    /**
     * Re-embeds {@code storedFileId} after {@link #restoreFile} brings it back out of the trash,
     * mirroring {@link #reindexRestoredFileForSearch}'s own "a restore is a deliberate, occasional
     * action that can afford a full content re-fetch" reasoning. A no-op if the extension isn't
     * running or the re-fetch fails for any reason.
     */
    private void reindexRestoredFileForIntelligence(final String authUserId, final String storedFileId) {
        try {
            final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
            if (intelligenceService == null) return;
            final StoredFile restored = this.fileFactory.findById(storedFileId).orElse(null);
            if (restored == null) return;
            intelligenceService.indexAsync(new IntelligenceDocument(
                    authUserId, storedFileId, restored.fileName(), restored.contentType(), restored.content()));
        } catch (final RuntimeException ignored) {
            // Best-effort only - see this method's own Javadoc.
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | FileIntegrityException ignored) {
            // Same reasoning - a failed re-fetch must never block the restore itself.
        }
    }

    /**
     * One entry of the authoritative access set {@link #accessibleFiles} resolves - a file id
     * paired with just enough metadata to render a search hit without a second lookup.
     *
     * @param fileName the file's cached display name, or {@code null} for a legacy {@link
     * StoredFileOwnership} row written before metadata was cached on it (see {@link
     * StoredFileOwnership#hasMetadata()}) - resolved lazily, per surviving hit only, by {@link
     * #semanticSearch}
     */
    private record AccessibleFile(@Nullable String fileName, @Nullable String folderId) {
    }

    /**
     * Every file id {@code authUserId} currently has access to - owned and not trashed, plus every
     * file directly shared with them - each paired with its cached display metadata.
     *
     * <p>This is the authoritative pre-filter of {@link IntelligenceService}'s two-stage security
     * invariant (stage 1): the semantic index is never asked "what does this user have?", only
     * "which of <em>these</em> is most similar?". Deliberately excludes trashed files (via {@link
     * #ownedFileOwnerships}, unlike {@link #visibleActivityTargetIds}'s own
     * deliberately-includes-deleted behavior) - a trashed file must not surface in a search result,
     * the same rule {@link #listFileSummaries} already applies to a keyword listing.
     *
     * <p>Cost: the same full {@link StoredFileOwnership} scan {@link #listFileSummaries} already
     * accepts, plus {@link #listSharedWithMe}'s own. Paid once per search request, never per hit.
     */
    private Map<String, AccessibleFile> accessibleFiles(final String authUserId) {
        final Map<String, AccessibleFile> accessible = new HashMap<>();
        this.ownedFileOwnerships(authUserId).forEach(ownership ->
                accessible.put(ownership.getStoredFileId(), new AccessibleFile(ownership.getFileName(), ownership.getFolderId())));
        this.listSharedWithMe(authUserId).forEach(shared ->
                accessible.put(shared.file().fileId(), new AccessibleFile(shared.file().fileName(), shared.file().folderId())));
        return accessible;
    }

    /** See {@link ICloudUserService#accessibleFileIds}'s Javadoc. */
    @NonNull
    @Override
    public Set<String> accessibleFileIds(@NonNull final String authUserId) {
        return Set.copyOf(this.accessibleFiles(authUserId).keySet());
    }

    /**
     * See {@link ICloudUserService#semanticSearch}'s Javadoc - and {@link IntelligenceService}'s
     * own "security invariant" section, which this method is the single implementation of.
     *
     * <p>Both stages are visible in the body below and neither may be removed:
     *
     * <ol>
     *   <li><b>Pre-filter</b> - {@link #accessibleFiles} resolves the caller's real, current access
     *       set from authoritative data, and only those ids are offered to the vector store.</li>
     *   <li><b>Post-check</b> - every returned id is first required to be a member of that same
     *       freshly-resolved set (so a fabricated or stale id from a misbehaving service is dropped
     *       outright), and is then additionally run through {@link #checkFileAccess}, the very same
     *       check every other {@code /files} response performs. That second half is not redundant
     *       in practice: it is what also enforces the content-scan gate, so a still-scanning or
     *       flagged file can never leak into a result list through this route.</li>
     * </ol>
     *
     * <p>The per-hit {@link #checkFileAccess} call is O(1) for an owned file (a composite-key
     * lookup) and pays {@link #requireSharedFileAccess}'s own full scan only for a hit reached
     * through a share - bounded by {@code limit}, never by the size of the account.
     */
    @NonNull
    @Override
    public List<SemanticSearchResult> semanticSearch(@NonNull final String authUserId, @NonNull final String query, final int limit) {
        final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
        if (intelligenceService == null || query.isBlank() || limit <= 0) return List.of();

        // Stage 1 - the pre-filter.
        final Map<String, AccessibleFile> accessible = this.accessibleFiles(authUserId);
        if (accessible.isEmpty()) return List.of();

        final List<SemanticMatch> matches = intelligenceService.search(query, accessible.keySet(), limit);

        // Stage 2 - the post-check. Never assume a returned id was one of the candidates.
        final List<SemanticSearchResult> results = new ArrayList<>();
        for (final SemanticMatch match : matches) {
            final AccessibleFile candidate = accessible.get(match.storedFileId());
            if (candidate == null) continue;
            try {
                this.checkFileAccess(authUserId, match.storedFileId());
            } catch (final RuntimeException accessDenied) {
                continue;
            }
            final String fileName = candidate.fileName() != null ? candidate.fileName()
                    : this.findStoredFileMetadata(match.storedFileId()).map(StoredFile::fileName).orElse(null);
            if (fileName == null) continue; // a hit we cannot even name is not a usable result
            results.add(new SemanticSearchResult(match.storedFileId(), fileName, candidate.folderId(), match.score()));
        }
        return List.copyOf(results);
    }

    /**
     * Refreshes {@code storedFileId}'s recorded owner in the vector store, if an {@link
     * IntelligenceService} is published - a no-op otherwise, and never throwing, matching every
     * other intelligence hook in this class.
     *
     * <h2>Why this exists, and what it deliberately is not</h2>
     *
     * The vector store records an owner as a <b>hint</b>. It was previously never refreshed after
     * indexing, which was flagged as a "missing share-revocation hook" - but for search that was
     * always a non-issue and remains one: a search ranks only the ids the caller's own
     * authoritative pre-filter offers, so the hint has never been able to widen anyone's access,
     * however stale it got.
     *
     * <p>What changed is that duplicate detection now exists and is scoped per account. The hint
     * still cannot leak a file - candidate ids are supplied by the same pre-filter - but a store
     * whose ownership metadata drifts is a store no maintenance job can reason about. Refreshing
     * it here is cheap (no re-embedding, just metadata) and keeps that from happening.
     */
    private static void refreshIntelligenceOwner(final String storedFileId, final String ownerAuthUserId) {
        try {
            final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
            if (intelligenceService != null) intelligenceService.refreshOwnerAsync(storedFileId, ownerAuthUserId);
        } catch (final RuntimeException ignored) {
            // Best-effort only - see indexFileForIntelligence's own Javadoc.
        }
    }

    /**
     * See {@link ICloudUserService#findDuplicateFiles}'s Javadoc - and {@link
     * IntelligenceService}'s own security-invariant section, which this method enforces in the
     * same two stages {@link #semanticSearch} does.
     *
     * <p>Stage 2 is stricter here than for a search: a group whose members do not <em>all</em>
     * survive re-validation is not returned with the survivors, it is rebuilt from them and then
     * dropped entirely if fewer than two remain. Returning a partial group would assert a
     * duplicate relationship that this method can no longer actually vouch for.
     */
    @NonNull
    @Override
    public List<DuplicateFileGroup> findDuplicateFiles(@NonNull final String authUserId, final double minimumSimilarity, final int limit) {
        final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
        if (intelligenceService == null || limit <= 0) return List.of();

        // Stage 1 - the pre-filter.
        final Map<String, AccessibleFile> accessible = this.accessibleFiles(authUserId);
        if (accessible.size() < 2) return List.of();

        final List<DuplicateGroup> groups = intelligenceService.findDuplicates(accessible.keySet(), minimumSimilarity, limit);

        // Stage 2 - the post-check. Never assume a returned id was one of the candidates.
        final List<DuplicateFileGroup> results = new ArrayList<>();
        for (final DuplicateGroup group : groups) {
            final List<DuplicateFileGroup.Entry> entries = new ArrayList<>();
            for (final String storedFileId : group.storedFileIds()) {
                final AccessibleFile candidate = accessible.get(storedFileId);
                if (candidate == null) continue;
                try {
                    this.checkFileAccess(authUserId, storedFileId);
                } catch (final RuntimeException accessDenied) {
                    continue;
                }
                final String fileName = candidate.fileName() != null ? candidate.fileName()
                        : this.findStoredFileMetadata(storedFileId).map(StoredFile::fileName).orElse(null);
                if (fileName == null) continue;
                entries.add(new DuplicateFileGroup.Entry(storedFileId, fileName, candidate.folderId()));
            }
            if (entries.size() >= 2) results.add(new DuplicateFileGroup(List.copyOf(entries), group.similarity()));
        }
        return List.copyOf(results);
    }

    /**
     * See {@link ICloudUserService#suggestFileTags}'s Javadoc.
     *
     * <p>Access is established <b>before</b> the backing service is contacted at all, via the same
     * {@link #requireFileAccess} every {@code /files} response uses - so an unauthorized caller
     * never even causes a lookup against the vector store, and the content-scan gate applies here
     * exactly as it does to a download.
     */
    @NonNull
    @Override
    public List<TagSuggestion> suggestFileTags(@NonNull final String authUserId, @NonNull final String storedFileId, final int limit) {
        this.requireFileAccess(authUserId, storedFileId, "suggestFileTags");
        final IntelligenceService intelligenceService = CloudDriver.getInstance().getServiceContainer().getIntelligenceService();
        if (intelligenceService == null || limit <= 0) return List.of();
        return intelligenceService.suggestTags(storedFileId, limit);
    }

    /**
     * @return {@code true} if {@code cloud-driver-extensions-scan} has published a {@link
     * ContentScanService} - see {@link #uploadFile(String, String, byte[], String)}'s own use of
     * this for why it's checked once, up front, rather than let a missing service simply no-op
     * later (it decides the file's *initial* scan status, not a follow-up action).
     */
    private static boolean isContentScanServicePublished() {
        try {
            return CloudDriver.getInstance().getServiceContainer().getContentScanService() != null;
        } catch (final RuntimeException e) {
            return false;
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
     * decrement the owner's usage total - the file's bytes still occupy s3storage until a purge job
     * (or {@link #resetCloudUser(String)}, via {@link #hardDeleteFile}) actually removes it; see
     * {@link #restoreFile(String, String)} for the reverse.
     *
     * @param authUserId the caller's own id, checked against the ownership record
     * @param storedFileId the file to trash
     * @throws IllegalArgumentException if {@code storedFileId} isn't tracked as belonging to {@code authUserId}
     */
    @Override
    public void deleteFile(@NonNull final String authUserId, @NonNull final String storedFileId) {
        // Sharing: deliberately owner-only - a grantee can read a shared file but never trash it.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (ownership.isDeleted()) {
            return;
        }
        try {
            this.dataFactory.update(ownership.markedDeleted());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.deleteFile: failed to trash " + storedFileId, e);
        }
        // Sharing, fixed 2026-09-02: revoke every outstanding share on this file the
        // moment it's deleted (trashed), not merely relying on getFile's own isDeleted() check to
        // block access - that check alone left the grant itself dangling, so a later restoreFile
        // would silently re-grant every previously-shared recipient access again, without the
        // owner ever having chosen to re-share. See revokeAllFileShares's own Javadoc.
        this.revokeAllFileShares(storedFileId);
        // Same reasoning as revokeAllFileShares immediately above.
        this.revokeAllPublicFileLinks(storedFileId);
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_DELETE, storedFileId, null));
        removeFromSearchIndex(authUserId, storedFileId);
        removeFromIntelligenceIndex(storedFileId);
        // Webhook dispatch - fired once, at soft-delete (when a user actually
        // experiences "my file is gone"), not again at the later permanent purge of the same file.
        dispatchWebhookEvent(authUserId, WebhookEventType.FILE_DELETED, storedFileId);
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
        // Sharing: deliberately owner-only.
        final StoredFileOwnership ownership = this.requireOwnedFile(authUserId, storedFileId);
        if (!ownership.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFile: " + storedFileId + " is not in the trash");
        }
        try {
            this.dataFactory.update(ownership.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFile: failed to restore " + storedFileId, e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FILE_RESTORE, storedFileId, null));
        this.reindexRestoredFileForSearch(authUserId, storedFileId, ownership.getFolderId());
        this.reindexRestoredFileForIntelligence(authUserId, storedFileId);
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
        // Sharing: deliberately owner-only - a grantee with folder-level read access
        // can never create content inside a folder shared with them.
        if (parentFolderId != null) this.requireOwnedFolder(authUserId, parentFolderId);

        final Folder folder = new Folder(UUID.randomUUID().toString(), authUserId, name, parentFolderId);
        try {
            this.dataFactory.register(folder);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.createFolder: failed to create folder '" + name + "'", e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FOLDER_CREATE, folder.getFolderId(), null));
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
        // Sharing: deliberately owner-only, does NOT include folders shared with
        // authUserId - see listFileSummaries's own comment for the same reasoning; use
        // listSharedFoldersWithMe(authUserId) instead.
        try {
            return this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
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
            sorted = this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
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

        // Sharing: deliberately owner-only - a grantee can browse a shared folder but never rename/move it.
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
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FOLDER_UPDATE, folderId, newName));
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
     * Sets {@code folderId}'s display color, via a single {@link DataFactory#update} on the
     * resulting {@link Folder#coloredAs(String)} copy - the same O(1) shape {@link #updateFolder}
     * uses.
     *
     * @param authUserId the requesting user's id, checked against the folder record
     * @param folderId the folder to recolor
     * @param color the new display color, or {@code null} to clear it
     * @throws IllegalArgumentException if {@code folderId} isn't owned by {@code authUserId}
     */
    @Override
    public void updateFolderColor(@NonNull final String authUserId, @NonNull final String folderId, @Nullable final String color) {
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        try {
            this.dataFactory.update(existing.coloredAs(color));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.updateFolderColor: failed to update " + folderId, e);
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

        // Sharing: deliberately owner-only.
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
        // Fixed 2026-09-02 - see deleteFile's own comment on why this must be
        // explicit rather than relying on Folder#isDeleted() alone.
        this.revokeAllFolderShares(folderId);
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FOLDER_DELETE, folderId, null));
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
        // Sharing: deliberately owner-only.
        final Folder existing = this.requireOwnedFolder(authUserId, folderId);
        if (!existing.isDeleted()) {
            throw new IllegalStateException("@CloudUserService.restoreFolder: " + folderId + " is not in the trash");
        }
        try {
            this.dataFactory.update(existing.restored());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CloudUserService.restoreFolder: failed to restore " + folderId, e);
        }
        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.FOLDER_RESTORE, folderId, null));
    }

    /**
     * See {@link ICloudUserService#listFileActivity}'s Javadoc.
     *
     * @throws IllegalArgumentException if {@code storedFileId} isn't owned by or shared with {@code authUserId}
     */
    @NonNull
    @Override
    public CursorPage<AuditEvent> listFileActivity(@NonNull final String authUserId, @NonNull final String storedFileId,
                                                     @Nullable final String cursor, final int limit) {
        this.checkFileAccess(authUserId, storedFileId);
        return this.activityForTarget(storedFileId, cursor, limit);
    }

    /**
     * See {@link ICloudUserService#listFolderActivity}'s Javadoc.
     *
     * @throws IllegalArgumentException if {@code folderId} isn't owned by or shared with {@code authUserId}
     */
    @NonNull
    @Override
    public CursorPage<AuditEvent> listFolderActivity(@NonNull final String authUserId, @NonNull final String folderId,
                                                        @Nullable final String cursor, final int limit) {
        this.requireFolderViewAccess(authUserId, folderId);
        return this.activityForTarget(folderId, cursor, limit);
    }

    /** See {@link ICloudUserService#listActivity}'s Javadoc. */
    @NonNull
    @Override
    public CursorPage<AuditEvent> listActivity(@NonNull final String authUserId, @Nullable final String cursor, final int limit) {
        final Set<String> visibleTargetIds = this.visibleActivityTargetIds(authUserId);
        final List<AuditEvent> allEvents;
        try {
            allEvents = this.dataFactory.getEntities(AuditEvent.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.listActivity: failed to scan audit events for " + authUserId, e);
        }
        final List<AuditEvent> sorted = allEvents.stream()
                .filter(event -> event.getTargetId() != null && visibleTargetIds.contains(event.getTargetId()))
                .sorted(Comparator.comparing(CloudUserService::activityCursorKey))
                .toList();
        return paginate(sorted, cursor, limit, CloudUserService::activityCursorKey);
    }

    /**
     * Owner-or-share view-access check for a folder, mirroring {@link #checkFileAccess}'s own
     * "try owned first, fall back to shared" shape for a file - tries {@link #requireOwnedFolder}
     * first, falling back to {@link #requireSharedFolderAccess} only if that fails.
     *
     * @throws IllegalArgumentException if {@code folderId} isn't owned by or shared with {@code authUserId}
     */
    private void requireFolderViewAccess(final String authUserId, final String folderId) {
        try {
            this.requireOwnedFolder(authUserId, folderId);
        } catch (final IllegalArgumentException notOwned) {
            this.requireSharedFolderAccess(authUserId, folderId);
        }
    }

    /**
     * Every {@link AuditEvent} whose {@link AuditEvent#getTargetId()} equals {@code targetId},
     * newest first, paginated - the shared full-scan-then-sort-then-slice shape {@link
     * #listActivity} also uses, scoped to a single id instead of a whole visible-target set.
     */
    private CursorPage<AuditEvent> activityForTarget(final String targetId, final String cursor, final int limit) {
        final List<AuditEvent> targetEvents;
        try {
            targetEvents = this.dataFactory.getEntitiesByIndex(AuditEvent.class, AuditEvent.INDEX_TARGET_ID, targetId);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.activityForTarget: failed to look up audit events for " + targetId, e);
        }
        final List<AuditEvent> sorted = targetEvents.stream()
                .sorted(Comparator.comparing(CloudUserService::activityCursorKey))
                .toList();
        return paginate(sorted, cursor, limit, CloudUserService::activityCursorKey);
    }

    /**
     * Every file/folder id {@code authUserId} currently owns or has been shared, directly - the
     * scope {@link #listActivity} filters the global {@link AuditEvent} table against. Trashed
     * files/folders are intentionally included ({@link #ownedFileOwnerships} already excludes
     * them, so this specifically re-includes files via {@link #ownedFileOwnershipsIncludingDeleted}
     * instead) - an activity feed showing "file X was deleted" would otherwise lose exactly the
     * one event that explains why the file disappeared from a live listing.
     */
    private Set<String> visibleActivityTargetIds(final String authUserId) {
        final Set<String> targetIds = new HashSet<>();

        this.ownedFileOwnershipsIncludingDeleted(authUserId).forEach(ownership -> targetIds.add(ownership.getStoredFileId()));
        this.listSharedWithMe(authUserId).forEach(shared -> targetIds.add(shared.file().fileId()));

        try {
            this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
                    .forEach(folder -> targetIds.add(folder.getFolderId()));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@CloudUserService.visibleActivityTargetIds: failed to scan folders for " + authUserId, e);
        }
        this.listSharedFoldersWithMe(authUserId).forEach(shared -> targetIds.add(shared.folder().getFolderId()));

        return targetIds;
    }

    /**
     * A lexicographically-ascending-sortable key that orders {@link AuditEvent}s <em>newest
     * first</em> - {@code Long.MAX_VALUE - timestampEpochMillis} zero-padded, so sorting ascending
     * by this string is equivalent to sorting descending by the real timestamp (ties broken
     * ascending by {@link AuditEvent#getId()}, an arbitrary but stable order). Lets {@link
     * #listActivity}/{@link #activityForTarget} reuse {@link #paginate} completely unchanged -
     * that helper only ever assumes an ascending sort key, which every one of its other callers
     * happens to want in the same direction as display order, but an activity feed's natural
     * display order is newest-first, the opposite.
     */
    private static String activityCursorKey(final AuditEvent event) {
        return String.format("%019d:%s", Long.MAX_VALUE - event.getTimestampEpochMillis(), event.getId());
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
            deleted = this.dataFactory.getEntitiesByIndex(Folder.class, Folder.INDEX_OWNER_ID, authUserId).stream()
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
     * cloud-driver-auth}) must never depend on. Reading {@code configuration.json} directly from this module is an
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
            return this.dataFactory.getEntitiesByIndex(StoredFileOwnership.class, StoredFileOwnership.INDEX_AUTH_USER_ID, authUserId).stream()
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
