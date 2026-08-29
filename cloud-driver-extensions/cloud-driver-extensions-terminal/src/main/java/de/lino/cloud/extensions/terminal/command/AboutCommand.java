package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.ExtensionFactory;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Displays uptime, uploaded file count, and total storage used. */
public class AboutCommand implements Command {

    /** @return {@code "about"} */
    @Override
    public @NotNull String name() {
        return "about";
    }

    /** @return {@code "ab"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("ab");
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
        final IAuthService authService = CloudDriver.getInstance().getServiceContainer().getAuthService();

        final String cloudRunningFor = Constraints.resolveMilliSecondsToUnit(System.currentTimeMillis() - Constraints.CLOUD_START_TIME_STAMP.get());
        final String usedStorage = Constraints.resolveBytesToUnit(fileFactory.getEntitiesAsync().join().stream().mapToLong(StoredFile::sizeBytes).sum());

        final String totalFiles = String.valueOf(fileFactory.getEntitiesAsync().join().size());
        // cloudUserService/authService are only published once CloudRestExtension has actually
        // built and started the JWT-authenticated REST API (see IServiceContainer's Javadoc) -
        // still null if that extension is disabled (no "jwt-signing-key" configured) or simply
        // hasn't started yet (no extension.json dependency ties "about" to "cloud-driver-rest").
        final String totalCloudUsers = cloudUserService != null ? String.valueOf(cloudUserService.getCloudUsers().size()) : "N/A";
        final String totalAuthUsers = authService != null ? String.valueOf(authService.getAuthUsers().size()) : "N/A";

        final String totalExtensions = String.valueOf(extensionFactory.getExtensions().size());

        terminal.emptyLine();
        terminal.displayApproved("Cloud running for: &b" + cloudRunningFor);
        terminal.displayApproved("Extensions: &b" + totalExtensions);
        terminal.displayApproved("Cloud users: &b" + totalCloudUsers);
        terminal.displayApproved("Auth users: &b" + totalAuthUsers);
        terminal.displayApproved("Uploaded files: &b" + totalFiles);
        terminal.displayApproved("Used storage: &b" + usedStorage);
        terminal.emptyLine();

    }

}
