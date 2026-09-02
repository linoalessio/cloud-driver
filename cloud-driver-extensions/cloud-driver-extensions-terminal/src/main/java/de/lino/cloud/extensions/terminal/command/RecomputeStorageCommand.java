package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.api.utility.Constraints;
import de.lino.cloud.extensions.terminal.command.system.HardResetCommand;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * One-off, operator-triggered backfill for {@link ICloudUser#getCurrentUploadedBytes()} - see
 * {@link ICloudUserService#recomputeUploadedBytes}'s own Javadoc for why this exists (accounts
 * that already had files before per-account upload quotas started tracking usage incrementally
 * silently under-report until this is run at least once). Modeled on {@link CloudUserCommand}'s
 * shape (email-keyed lookup, {@link Terminal#displayApproved} status lines) and {@link
 * HardResetCommand}'s "operator-triggered, not automatic" precedent - never wired to run on
 * startup or on any schedule.
 */
public class RecomputeStorageCommand implements Command {

    @Override
    public @NotNull String name() {
        return "recomputeStorage";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("recompute");
    }

    @Override
    public @NotNull String description() {
        return "Recompute an account's (or every account's) currentUploadedBytes from its actual owned files";
    }

    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.isEmpty()) {
            this.sendHelp();
            return;
        }

        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cThe REST/auth subsystem isn't running yet - no accounts to look up.");
            return;
        }

        if (arguments.hasCommand(0, "all")) {
            terminal.emptyLine();
            final List<ICloudUser> cloudUsers = cloudUserService.getCloudUsers();
            terminal.displayApproved("Recomputing storage usage for &b%s&7 account(s)...", cloudUsers.size());
            for (final ICloudUser cloudUser : cloudUsers) {
                final long total = cloudUserService.recomputeUploadedBytes(cloudUser.getAuthUserId());
                terminal.displayApproved("&8- &7%s: &b%s", cloudUser.getAuthUser().getEmailAddress(), Constraints.resolveBytesToUnit(total));
            }
            terminal.emptyLine();
            return;
        }

        final String emailAddress = arguments.command(0);
        final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(emailAddress);

        if (cloudUser.isEmpty()) {
            terminal.displayApproved("Cloud user '&b%s&7' does not exist", emailAddress);
            return;
        }

        final long total = cloudUserService.recomputeUploadedBytes(cloudUser.get().getAuthUserId());
        terminal.displayApproved("Cloud user '&b%s&7' storage usage recomputed: &b%s",
                cloudUser.get().getAuthUser().getEmailAddress(), Constraints.resolveBytesToUnit(total));
    }

    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&frecomputeStorage <email>");
        terminal.displayApproved("&frecomputeStorage all");
    }

}
