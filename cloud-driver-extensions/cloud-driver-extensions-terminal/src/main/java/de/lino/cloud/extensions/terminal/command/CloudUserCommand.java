package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.meta.FileMetadata;
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
 * {@code cloudUser list} prints every registered account's email, uploaded s3storage, and file
 * count; {@code cloudUser info <email>} prints one account's detail; {@code cloudUser reset
 * <email>} wipes that account's files/folders via {@link ICloudUserService#resetCloudUser};
 * {@code cloudUser delete <email>} deletes the account entirely via {@link
 * ICloudUserService#deleteCloudUser}; {@code cloudUser limit <email> <bytes> <unit>} (unit one
 * of {@code B}/{@code KB}/{@code MB}/{@code GB}, case-insensitively) changes that account's
 * upload quota via {@link ICloudUserService#updateCloudUserBytesLimit}, rejecting a
 * non-positive value, an unknown unit, and a value that would push the whole server's
 * already-stored bytes past the {@code "cloud-server-max-bytes-available"} configuration limit.
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
     * limit} based on {@code arguments}' first token, printing a usage message if it is empty
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
            terminal.displayApproved("Uploaded s3storage: &b%s&8 / &b%s", UnitParser.parseByteUnit(cloudUser.get().getCurrentUploadedBytes()), UnitParser.parseByteUnit(cloudUser.get().getMaxBytesToUpload()));
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

        if (arguments.hasCommand(0, "limit") && arguments.hasLength(3)) {

            try {

                final String emailAddress = arguments.command(1);
                final long value = Long.parseLong(arguments.command(2));
                final long bytes = UnitParser.parseUnitToBytes(value, arguments.command(3));
                final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

                if (cloudUser.isEmpty()) {
                    terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
                    return;
                }

                if (bytes <= 0) {
                    terminal.displayApproved("New limit cannot be below or equal 0");
                    return;
                }

                final FileFactory fileFactory = CloudDriver.getInstance().getFactoryContainer().getFileFactory();
                // Metadata-only listing - summing sizes must not re-download the whole S3-backed
                // corpus, see DefaultFileFactory#getEntitiesMetadata (the same 2026-09-10 fix as
                // StatisticsCommand's).
                final long uploadedBytesToDatabase = fileFactory.getEntitiesMetadataAsync().join().stream().mapToLong(FileMetadata::sizeBytes).sum();
                final long exisingBytesInServer = CloudDriver.getInstance().getConfiguration().getLong("cloud-server-max-bytes-available");

                if ((uploadedBytesToDatabase + bytes) >= exisingBytesInServer) {
                    terminal.displayApproved("The cloud server has &breached &7its &cmaximum s3storage capacity&7.");
                    return;
                }

                cloudUserService.updateCloudUserBytesLimit(cloudUser.get().getAuthUserId(), bytes);
                terminal.displayApproved("Cloud user '&b%s&7' can now upload up to &a%s", cloudUser.get().getAuthUser().getEmailAddress(), UnitParser.parseByteUnit(bytes));

            } catch (final NumberFormatException e) {
                terminal.displayApproved("Please enter a valid numeric value");
            } catch (final IllegalArgumentException e) {
                // NumberFormatException is a subclass, so this catch only ever sees
                // UnitParser.parseUnitToBytes rejecting the unit token.
                terminal.displayApproved("Unknown unit '&b%s&7' - valid units: &bB&7, &bKB&7, &bMB&7, &bGB", arguments.command(3));
            }

            return;
        }

        this.sendHelp();


    }

    /** Prints this command's usage syntax to the terminal. */
    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&fcloudUser list");
        terminal.displayApproved("&fcloudUser limit <email> <bytes> <unit> &8(&7unit: &bB&7, &bKB&7, &bMB&7, &bGB&8)");
        terminal.displayApproved("&fcloudUser <info:delete:reset> <email>");
    }

}
