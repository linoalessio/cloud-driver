package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.StoredFile;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.UnitParser;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Lists and manages {@link de.lino.cloud.api.user.ICloudUser} accounts from the terminal:
 * {@code cloudUser list} prints every registered account's email, uploaded storage, and file
 * count; {@code cloudUser info <email>} prints one account's detail; {@code cloudUser reset
 * <email>} wipes that account's files/folders via {@link ICloudUserService#resetCloudUser};
 * {@code cloudUser delete <email>} deletes the account entirely via {@link
 * ICloudUserService#deleteCloudUser}; {@code cloudUser update <email> <bytes>} changes that
 * account's upload quota via {@link ICloudUserService#updateCloudUserBytesLimit}, rejecting a
 * non-positive value and a value that would push the whole server's already-stored bytes past
 * the {@code "cloud-server-max-bytes-available"} configuration limit.
 */
public class CloudUserCommand implements Command {

    /** @return {@code "cloudUser"} */
    @Override
    public @NotNull String name() {
        return "cloudUser";
    }

    /** @return {@code "cu"}, {@code "user"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("cu", "user");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Get information about a specific cloud user";
    }

    /**
     * Dispatches to one of {@code list}/{@code info}/{@code reset}/{@code delete}/{@code
     * update} based on {@code arguments}' first token, printing a usage message if it is empty
     * or unrecognized.
     *
     * @param arguments the sub-command and its own arguments, split on whitespace
     */
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
                final String totalStorage = UnitParser.parseByteUnit(cloudUser.getCurrentUploadedBytes());
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
            terminal.displayApproved("Uploaded storage: &b%s&8 / &b%s", UnitParser.parseByteUnit(cloudUser.get().getCurrentUploadedBytes()), UnitParser.parseByteUnit(cloudUser.get().getMaxBytesToUpload()));
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

            final String clearedStorage = UnitParser.parseByteUnit(cloudUser.get().getCurrentUploadedBytes());
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

            final String clearedStorage = UnitParser.parseByteUnit(cloudUser.get().getCurrentUploadedBytes());
            terminal.displayApproved("Cloud user '&b%s&7' successfully &cdeleted &7(&b%s&7)", cloudUser.get().getAuthUser().getEmailAddress(), clearedStorage);
            cloudUserService.deleteCloudUser(cloudUser.get().getAuthUserId());

            return;
        }

        if (arguments.hasCommand(0, "update") && arguments.hasLength(2)) {

            try {

                final String emailAddress = arguments.command(1);
                final long bytes = Long.parseLong(arguments.command(2));
                final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

                if (cloudUser.isEmpty()) {
                    terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
                    return;
                }

                if (bytes <= 0) {
                    terminal.displayApproved("New bytes value cannot be below or equal 0");
                    return;
                }

                final FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();
                final long uploadedBytesToDatabase = fileFactory.getEntitiesAsync().join().stream().mapToLong(StoredFile::sizeBytes).sum();
                final long exisingBytesInServer = CloudDriver.getInstance().getConfiguration().getLong("cloud-server-max-bytes-available");

                if ((uploadedBytesToDatabase + bytes) >= exisingBytesInServer) {
                    terminal.displayApproved("The cloud server has &breached &7its &cmaximum storage capacity&7.");
                    return;
                }

                cloudUserService.updateCloudUserBytesLimit(cloudUser.get().getAuthUserId(), bytes);
                terminal.displayApproved("Cloud user '&b%s&7' can now upload up to &a%s", cloudUser.get().getAuthUser().getEmailAddress(), UnitParser.parseByteUnit(bytes));


            } catch (final NumberFormatException e) {
                terminal.displayApproved("Please enter a valid bytes value");
                return;
            }

            return;
        }

        this.sendHelp();


    }

    /** Prints this command's usage syntax to the terminal. */
    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&fcloudUser list");
        terminal.displayApproved("&fcloudUser update <email> <bytes>");
        terminal.displayApproved("&fcloudUser <info:delete:reset> <email>");
    }

}
