package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class CloudUserCommand implements Command {

    @Override
    public @NotNull String name() {
        return "cloudUser";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("cu", "user");
    }

    @Override
    public @NotNull String description() {
        return "Get information about a specific cloud user";
    }

    @Override
    public void execute(@NotNull CommandArguments arguments) {

        if (arguments.isEmpty()) {
            this.sendHelp();
            return;
        }

        final Terminal terminal = this.terminal();
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();

        if (arguments.hasCommand(0, "list")) {

            terminal.emptyLine();
            terminal.displayApproved("Registered cloud users (&b%s&7): ", cloudUserService.getCloudUsers().size());

            cloudUserService.getCloudUsers().forEach(cloudUser -> {
                final String totalStorage = Constraints.resolveBytesToUnit(cloudUser.getStoredFiles().stream().mapToLong(StoredFile::sizeBytes).sum());
                terminal.displayApproved("&8- &7Email: &b%s &8| &7Uploaded files (&b%s&7): &b%s", cloudUser.getAuthUser().getEmailAddress(), totalStorage, cloudUser.getStoredFiles().size());
            });
            terminal.emptyLine();

            return;
        }

        if (arguments.hasCommand(0, "info") && arguments.hasLength(1)) {

            final String emailAddress = arguments.command(1);
            final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

            if (cloudUser.isEmpty()) {
                terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
                return;
            }

            terminal.emptyLine();
            terminal.displayApproved("Cloud user: &b%s", cloudUser.get().getAuthUser().getEmailAddress());
            terminal.displayApproved("AuthId: &b%s", cloudUser.get().getAuthUserId());
            terminal.displayApproved("Uploaded files: &b%s", cloudUser.get().getStoredFiles().size());
            terminal.displayApproved("Uploaded storage: &b%s", Constraints.resolveBytesToUnit(cloudUser.get().getStoredFiles().stream().mapToLong(StoredFile::sizeBytes).sum()));
            terminal.emptyLine();

            return;
        }

        if (arguments.hasCommand(0, "reset") && arguments.hasLength(1)) {

            final String emailAddress = arguments.command(1);
            final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

            if (cloudUser.isEmpty()) {
                terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
                return;
            }

            final String clearedStorage = Constraints.resolveBytesToUnit(cloudUser.get().getStoredFiles().stream().mapToLong(StoredFile::sizeBytes).sum());
            terminal.displayApproved("Cloud user '&b%s&7' successfully &ccleared &7(&b%s&7)", cloudUser.get().getAuthUser().getEmailAddress(), clearedStorage);
            cloudUserService.resetCloudUser(cloudUser.get().getAuthUserId());

            return;
        }

        if (arguments.hasCommand(0, "delete") && arguments.hasLength(1)) {

            final String emailAddress = arguments.command(1);
            final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

            if (cloudUser.isEmpty()) {
                terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
                return;
            }

            final String clearedStorage = Constraints.resolveBytesToUnit(cloudUser.get().getStoredFiles().stream().mapToLong(StoredFile::sizeBytes).sum());
            terminal.displayApproved("Cloud user '&b%s&7' successfully &cdeleted &7(&b%s&7)", cloudUser.get().getAuthUser().getEmailAddress(), clearedStorage);
            cloudUserService.deleteCloudUser(cloudUser.get().getAuthUserId());

            return;
        }

        this.sendHelp();


    }

    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&fcloudUser list");
        terminal.displayApproved("&fcloudUser <info> <name>");
        terminal.displayApproved("&fcloudUser <delete:reset> <name>");
    }

}
