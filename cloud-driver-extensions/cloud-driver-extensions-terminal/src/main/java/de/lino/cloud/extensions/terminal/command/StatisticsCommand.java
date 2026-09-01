package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Displays uptime, uploaded file count, and total storage used. */
public class StatisticsCommand implements Command {

    /** @return {@code "about"} */
    @Override
    public @NotNull String name() {
        return "statistics";
    }

    /** @return {@code "ab"} */
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
     * Prints uptime, uploaded file count, and used storage.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();

        final ExtensionFactory extensionFactory = CloudDriver.getInstance().getFactoryContainer().getExtensionFactory();
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();

        final String cloudRunningFor = Constraints.resolveMilliSecondsToUnit(System.currentTimeMillis() - Constraints.CLOUD_START_TIME_STAMP.get());
        final String usedStorage = Constraints.resolveBytesToUnit(fileFactory.getEntitiesAsync().join().stream().mapToLong(StoredFile::sizeBytes).sum());
        final String totalCloudServerStorage = Constraints.resolveBytesToUnit(CloudDriver.getInstance().getConfiguration().getLong("cloud-server-max-bytes-available"));

        final String totalFiles = String.valueOf(fileFactory.getEntitiesAsync().join().size());
        final String totalCloudUsers = cloudUserService != null ? String.valueOf(cloudUserService.getCloudUsers().size()) : "N/A";

        final String totalExtensions = String.valueOf(extensionFactory.getExtensions().size());
        final String cloudVersion = extensionFactory.findByName("cloud-driver-bootstrap").orElseThrow().getExtensionProperties().getExtensionVersion();

        terminal.emptyLine();
        terminal.displayApproved("Cloud running for (&bv%s&7): &b%s", cloudVersion, cloudRunningFor);
        terminal.displayApproved("Server storage: &b%s", totalCloudServerStorage);
        terminal.displayApproved("Extensions: &b%s", totalExtensions);
        terminal.displayApproved("Cloud users: &b%s", totalCloudUsers);
        terminal.displayApproved("Uploaded files &7(&b%s&7): &b%s", usedStorage, totalFiles);
        terminal.emptyLine();

    }

}
