package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.FileFactory;
import de.lino.cloud.api.file.meta.FileMetadata;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.UnitParser;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
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

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("cloudUser list", "Every account with its storage use"),
                CommandUsage.of("cloudUser info <email>", "Everything known about one account"),
                CommandUsage.of("cloudUser reset <email>", "Arm clearing one account's files and storage counter (does nothing on its own)"),
                CommandUsage.of("cloudUser reset <email> confirm", "Confirm the armed clear, within 15s of arming it"),
                CommandUsage.of("cloudUser delete <email>", "Arm deleting one account and everything it owns (does nothing on its own)"),
                CommandUsage.of("cloudUser delete <email> confirm", "Confirm the armed delete, within 15s of arming it"),
                CommandUsage.of("cloudUser limit <email> <bytes> <unit>", "Set the storage quota (unit: B, KB, MB, GB)")
        );
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
            this.sendUsage();
            return;
        }

        final Terminal terminal = this.terminal();
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        // Optional facet: null until the REST extension publishes one, and it deliberately
        // publishes nothing when no JWT signing key is configured, so a deployment can still boot
        // every other subsystem. Every sibling command checks this; without it all five
        // sub-commands below threw a null-pointer exception at the operator instead of saying so.
        if (cloudUserService == null) {
            terminal.displayApproved("&cThe REST/auth subsystem isn't running yet - no accounts to look up.");
            return;
        }

        if (arguments.hasCommand(0, "list")) {

            // One line per account, so on a real deployment this outgrows the window - paged,
            // not printed straight through, or the first accounts scroll away unread.
            final List<String> lines = new ArrayList<>();
            lines.add(String.format("Registered cloud users (&b%s&7): ", cloudUserService.getCloudUsers().size()));

            cloudUserService.getCloudUsers().forEach(cloudUser -> {
                final String totalStorage = UnitParser.parseByteUnit(cloudUser.getCurrentUploadedBytes());
                lines.add(String.format("&8- &7Email: &b%s &8| &7Uploaded files (&b%s&7): &b%s", cloudUser.getAuthUser().getEmailAddress(), totalStorage, cloudUser.getStoredFiles().size()));
            });

            terminal.emptyLine();
            terminal.displayPaged("cloudUser list", lines);

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
            if (!confirmDestructiveAction(terminal, "reset", emailAddress, arguments,
                    "destroys every file and folder '" + emailAddress + "' owns (&b" + clearedStorage + "&7), bypassing the trash")) {
                return;
            }
            cloudUserService.resetCloudUser(cloudUser.get().getAuthUserId());
            terminal.displayApproved("Cloud user '&b%s&7' successfully &ccleared &7(&b%s&7)", cloudUser.get().getAuthUser().getEmailAddress(), clearedStorage);

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
            if (!confirmDestructiveAction(terminal, "delete", emailAddress, arguments,
                    "deletes the account '" + emailAddress + "' and everything it owns (&b" + clearedStorage + "&7)")) {
                return;
            }
            cloudUserService.deleteCloudUser(cloudUser.get().getAuthUserId());
            terminal.displayApproved("Cloud user '&b%s&7' successfully &cdeleted &7(&b%s&7)", cloudUser.get().getAuthUser().getEmailAddress(), clearedStorage);

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

        this.sendUsage();


    }

    /** What destructive action is currently armed, as {@code "<action>:<target>"}, or {@code null}. */
    private static final java.util.concurrent.atomic.AtomicReference<String> ARMED_ACTION =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** When the armed action stops being confirmable, in epoch millis, or {@code null}. */
    private static final java.util.concurrent.atomic.AtomicReference<Long> ARMED_UNTIL =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** How long an armed destructive action stays confirmable. */
    private static final java.time.Duration ARM_WINDOW = java.time.Duration.ofSeconds(15);

    /**
     * Arm-then-confirm guard for an irreversible sub-command, matching the shape {@code hardReset}
     * and {@code s3 purge} already use.
     *
     * <p>The first invocation prints what will be destroyed and arms; a second invocation carrying
     * {@code confirm}, within the window and naming the same target, performs it. Arming is keyed
     * on the action and its target, so arming against one account can never confirm an action
     * against another.
     *
     * @param terminal where to print the warning
     * @param action the sub-command name, e.g. {@code "reset"}
     * @param target the account this would act on
     * @param arguments the invocation, checked for the confirming token
     * @param whatItDoes a plain description of the destruction, shown while arming
     * @return {@code true} if the caller should proceed
     */
    private static boolean confirmDestructiveAction(final Terminal terminal, final String action, final String target,
                                                     final CommandArguments arguments, final String whatItDoes) {
        final String armedKey = action + ":" + target;
        final Long armedUntil = ARMED_UNTIL.get();
        final boolean confirming = arguments.hasCommand(2, "confirm");

        if (confirming && armedKey.equals(ARMED_ACTION.get()) && armedUntil != null && armedUntil > System.currentTimeMillis()) {
            ARMED_ACTION.set(null);
            ARMED_UNTIL.set(null);
            return true;
        }
        if (confirming) {
            terminal.displayApproved("&cNothing armed for that account, or the confirmation window has passed - run it again without 'confirm' first.");
            ARMED_ACTION.set(null);
            ARMED_UNTIL.set(null);
            return false;
        }

        ARMED_ACTION.set(armedKey);
        ARMED_UNTIL.set(System.currentTimeMillis() + ARM_WINDOW.toMillis());
        terminal.displayApproved("&c&lThis %s", whatItDoes);
        terminal.displayApproved("&7It cannot be undone. To confirm, run &ccloudUser %s %s confirm &7within &b%s seconds&7.",
                action, target, ARM_WINDOW.toSeconds());
        return false;
    }

}
