package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.api.utility.UnitParser;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Displays cloud driver-wide statistics: uptime and version, configured server s3storage
 * capacity, registered extension count, registered cloud-user count, and total uploaded file
 * count/s3storage used.
 */
public class StatisticsCommand implements Command {

    /** @return {@code "statistics"} */
    @Override
    public @NotNull String name() {
        return "statistics";
    }

    /** @return {@code "stats"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("stats");
    }

    /** @return this service's description */
    @Override
    public @NotNull String description() {
        return "Basic statistic information about the cloud driver";
    }

    /**
     * Prints the host {@link CloudDriver}'s uptime and version, configured maximum server
     * s3storage, registered extension count, registered cloud-user count (falling back to
     * {@code "N/A"} if {@link de.lino.cloud.api.factory.service.IServiceContainer
     * #getCloudUserService()} is not yet available - i.e. {@code cloud-driver-rest} has not
     * started), and total uploaded file count/s3storage used.
     *
     * <p><b>Fixed a real bug (2026-09-02):</b> this used to call {@code fileFactory
     * .getEntitiesAsync().join()} twice - once for the size sum, once for the count - each call
     * independently re-fetching, decrypting, and checksum-verifying (base64-decode plus, for a
     * compressed file, DEFLATE-inflate) every uploaded file's full content. Against an account
     * holding real file content, that's a real, large allocation paid twice for no reason; combined
     * with {@code DefaultFileFactory#verifyAll}'s (at the time still-unbounded) per-file concurrent
     * decode, this command was one of the two real call sites behind a live {@code
     * OutOfMemoryError} on {@code strato}. Now calls it once and reuses the same list for both.
     *
     * <p><b>Fixed again (2026-09-10), for speed this time:</b> once S3-backed content went live,
     * even the single {@code getEntitiesAsync()} call meant re-downloading, decrypting, and
     * checksum-verifying the whole corpus from the object store just to count rows and sum sizes.
     * Now goes through {@link FileFactory#getEntitiesMetadataAsync()}, which answers both from row
     * metadata alone - see {@code DefaultFileFactory#getEntitiesMetadata()}'s own Javadoc,
     * including the one-time legacy-row backfill that makes the first invocation against an old
     * corpus as slow as before and every later one fast.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();

        final ExtensionFactory extensionFactory = CloudDriver.getInstance().getFactoryContainer().getExtensionFactory();
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();

        final List<FileMetadata> allFiles = fileFactory.getEntitiesMetadataAsync().join();

        final String cloudRunningFor = UnitParser.parseTimeUnit(System.currentTimeMillis() - Constraints.CLOUD_START_TIME_STAMP.get());
        final String usedStorage = UnitParser.parseByteUnit(allFiles.stream().mapToLong(FileMetadata::sizeBytes).sum());
        final String totalCloudServerStorage = UnitParser.parseByteUnit(CloudDriver.getInstance().getConfiguration().getLong("cloud-server-max-bytes-available"));

        final String totalFiles = String.valueOf(allFiles.size());
        final String totalCloudUsers = cloudUserService != null ? String.valueOf(cloudUserService.getCloudUsers().size()) : "N/A";

        final String totalExtensions = String.valueOf(extensionFactory.getExtensions().size());
        final String cloudVersion = extensionFactory.findByName("cloud-driver-bootstrap").orElseThrow().getExtensionProperties().getExtensionVersion();

        terminal.emptyLine();
        terminal.displayApproved("Cloud running for (&bv%s&7): &b%s", cloudVersion, cloudRunningFor);
        terminal.displayApproved("Server s3storage: &b%s", totalCloudServerStorage);
        terminal.displayApproved("Extensions: &b%s", totalExtensions);
        terminal.displayApproved("Cloud users: &b%s", totalCloudUsers);
        terminal.displayApproved("Uploaded files &7(&b%s&7): &b%s", usedStorage, totalFiles);
        terminal.emptyLine();

    }

}
