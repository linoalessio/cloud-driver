package de.lino.cloud.plugin.icloud;

import de.lino.cloud.api.file.Folder;
import de.lino.cloud.api.icloud.IcloudAuthenticationException;
import de.lino.cloud.api.icloud.IcloudBridge;
import de.lino.cloud.api.icloud.IcloudBridgeException;
import de.lino.cloud.api.icloud.IcloudImportHandle;
import de.lino.cloud.api.icloud.IcloudImportService;
import de.lino.cloud.api.icloud.IcloudImportStatus;
import de.lino.cloud.api.icloud.IcloudLoginResult;
import de.lino.cloud.api.icloud.IcloudTreeEntry;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.task.MultiTaskingFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The one real {@link IcloudImportService} implementation: walks a real Apple iCloud Drive account
 * (via {@link IcloudBridge}) and recreates it, once, into the requesting account's own storage via
 * {@link ICloudUserService#createFolder}/{@link ICloudUserService#uploadFile} - everything under one
 * top-level {@value #IMPORT_ROOT_FOLDER_NAME} folder, created (or reused, by name, if a previous
 * import already created one) at the account root.
 *
 * <p>Job state ({@link ImportJob}) lives only in {@link #jobs}, an in-memory {@link
 * ConcurrentHashMap} - see {@link IcloudImportService}'s own Javadoc for why this is deliberate, not
 * a missing feature. Every file is imported sequentially within one job (not fanned out
 * concurrently) - simplicity over throughput for this first pass; a known, accepted trade-off, not
 * attempted to be optimized here.
 */
public final class DefaultIcloudImportService implements IcloudImportService {

    /** The fixed top-level folder name every import lands under - reused across runs if it already exists. */
    private static final String IMPORT_ROOT_FOLDER_NAME = "iCloud Import";

    private final ICloudUserService cloudUserService;
    private final IcloudBridge bridge;

    /** Every currently-tracked import job, keyed by job id - see this class's own Javadoc for why this is in-memory only. */
    private final ConcurrentHashMap<String, ImportJob> jobs = new ConcurrentHashMap<>();

    public DefaultIcloudImportService(final ICloudUserService cloudUserService, final IcloudBridge bridge) {
        this.cloudUserService = cloudUserService;
        this.bridge = bridge;
    }

    /** {@inheritDoc} */
    @Override
    public IcloudImportHandle startImport(final String authUserId, final String appleId, final char[] password) {
        final String jobId = UUID.randomUUID().toString();
        final ImportJob job = new ImportJob(authUserId, appleId);
        this.jobs.put(jobId, job);
        final Path sessionDir = Constraints.ICLOUD_IMPORT_SCRATCH_PATH.resolve(jobId);

        MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                Files.createDirectories(sessionDir);
                final IcloudLoginResult loginResult = this.bridge.login(appleId, password, sessionDir);
                if (loginResult.requiresTwoFactor()) {
                    // sessionDir is deliberately left in place here - confirmTwoFactor still needs
                    // the partially-authenticated session pyicloud persisted into it.
                    job.status.set(IcloudImportStatus.AWAITING_TWO_FACTOR);
                    return;
                }
                this.runImportAndFinish(job, sessionDir);
            } catch (final Exception e) {
                this.fail(job, e.getMessage());
                deleteScratchDirQuietly(sessionDir);
            } finally {
                Arrays.fill(password, '\0');
            }
        });

        return job.toHandle(jobId);
    }

    /** {@inheritDoc} */
    @Override
    public IcloudImportHandle confirmTwoFactor(final String authUserId, final String jobId, final String code) {
        final ImportJob job = this.requireOwnedJob(authUserId, jobId);
        if (job.status.get() != IcloudImportStatus.AWAITING_TWO_FACTOR) {
            throw new IllegalArgumentException("@DefaultIcloudImportService.confirmTwoFactor: job " + jobId + " is not awaiting a two-factor code");
        }
        final Path sessionDir = Constraints.ICLOUD_IMPORT_SCRATCH_PATH.resolve(jobId);
        job.status.set(IcloudImportStatus.RUNNING);

        MultiTaskingFactory.getInstance().runAsync(() -> {
            try {
                this.bridge.confirmTwoFactorCode(job.appleId, code, sessionDir);
                this.runImportAndFinish(job, sessionDir);
            } catch (final Exception e) {
                this.fail(job, e.getMessage());
                deleteScratchDirQuietly(sessionDir);
            }
        });

        return job.toHandle(jobId);
    }

    /** {@inheritDoc} */
    @Override
    public IcloudImportHandle getStatus(final String authUserId, final String jobId) {
        return this.requireOwnedJob(authUserId, jobId).toHandle(jobId);
    }

    /**
     * Walks the whole iCloud Drive tree, recreates every folder locally (top-down, so a child
     * folder's parent already exists by the time it's needed), uploads every file, and marks the
     * job {@link IcloudImportStatus#SUCCEEDED} - cleaning up {@code sessionDir} once done. Any
     * failure propagates to the caller, which marks the job {@link IcloudImportStatus#FAILED} and
     * cleans up {@code sessionDir} itself.
     */
    private void runImportAndFinish(final ImportJob job, final Path sessionDir) throws IcloudBridgeException, IOException {
        job.status.set(IcloudImportStatus.RUNNING);

        final List<IcloudTreeEntry> entries = this.bridge.listTree(job.appleId, sessionDir);
        final List<IcloudTreeEntry> files = entries.stream().filter(entry -> !entry.directory()).toList();
        job.totalFiles.set(files.size());

        final Map<String, String> folderIdsByPath = new HashMap<>();
        final String rootFolderId = this.findOrCreateFolder(job.authUserId, IMPORT_ROOT_FOLDER_NAME, null);
        folderIdsByPath.put("", rootFolderId);

        final List<IcloudTreeEntry> directories = entries.stream()
                .filter(IcloudTreeEntry::directory)
                .sorted(Comparator.comparingInt(entry -> depthOf(entry.path())))
                .toList();
        for (final IcloudTreeEntry directory : directories) {
            final String parentFolderId = folderIdsByPath.getOrDefault(parentPathOf(directory.path()), rootFolderId);
            final String folderId = this.findOrCreateFolder(job.authUserId, nameOf(directory.path()), parentFolderId);
            folderIdsByPath.put(directory.path(), folderId);
        }

        for (final IcloudTreeEntry file : files) {
            final String folderId = folderIdsByPath.getOrDefault(parentPathOf(file.path()), rootFolderId);
            final Path scratchFile = sessionDir.resolve(UUID.randomUUID() + "_" + nameOf(file.path()));
            try {
                this.bridge.downloadFile(job.appleId, sessionDir, file.path(), scratchFile);
                final byte[] content = Files.readAllBytes(scratchFile);
                this.cloudUserService.uploadFile(job.authUserId, nameOf(file.path()), content, folderId);
            } finally {
                Files.deleteIfExists(scratchFile);
            }
            job.filesImported.incrementAndGet();
        }

        job.status.set(IcloudImportStatus.SUCCEEDED);
        deleteScratchDirQuietly(sessionDir);
    }

    /** Finds an existing folder named {@code name} directly under {@code parentFolderId}, or creates one. */
    private String findOrCreateFolder(final String authUserId, final String name, final String parentFolderId) {
        return this.cloudUserService.listFolders(authUserId, parentFolderId).stream()
                .filter(folder -> folder.getName().equals(name))
                .map(Folder::getFolderId)
                .findFirst()
                .orElseGet(() -> this.cloudUserService.createFolder(authUserId, name, parentFolderId).getFolderId());
    }

    private ImportJob requireOwnedJob(final String authUserId, final String jobId) {
        final ImportJob job = this.jobs.get(jobId);
        if (job == null || !job.authUserId.equals(authUserId)) {
            throw new IllegalArgumentException("No iCloud import job with id " + jobId);
        }
        return job;
    }

    private void fail(final ImportJob job, final String message) {
        job.errorMessage.set(message);
        job.status.set(IcloudImportStatus.FAILED);
    }

    /** @return the path segment before the last {@code /}, or {@code ""} for a top-level entry. */
    private static String parentPathOf(final String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? "" : path.substring(0, lastSlash);
    }

    /** @return the path segment after the last {@code /}, or the whole path for a top-level entry. */
    private static String nameOf(final String path) {
        final int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? path : path.substring(lastSlash + 1);
    }

    /** @return how many {@code /}-separated segments deep {@code path} is - used to process directories top-down. */
    private static int depthOf(final String path) {
        return (int) path.chars().filter(character -> character == '/').count();
    }

    /** Best-effort recursive delete of a job's scratch directory - never throws, a leftover directory is not fatal. */
    private static void deleteScratchDirQuietly(final Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (final IOException ignored) {
                    // Best-effort - a leftover scratch file is not worth failing the job over.
                }
            });
        } catch (final IOException ignored) {
            // Directory may never have been created (e.g. login failed before Files.createDirectories ran).
        }
    }

    /** One import job's mutable, in-memory state. */
    private static final class ImportJob {
        private final String authUserId;
        private final String appleId;
        private final AtomicReference<IcloudImportStatus> status = new AtomicReference<>(IcloudImportStatus.RUNNING);
        private final AtomicInteger filesImported = new AtomicInteger(0);
        private final AtomicInteger totalFiles = new AtomicInteger(0);
        private final AtomicReference<String> errorMessage = new AtomicReference<>();

        private ImportJob(final String authUserId, final String appleId) {
            this.authUserId = authUserId;
            this.appleId = appleId;
        }

        private IcloudImportHandle toHandle(final String jobId) {
            return new IcloudImportHandle(jobId, this.status.get(), this.filesImported.get(), this.totalFiles.get(), this.errorMessage.get());
        }
    }

}
