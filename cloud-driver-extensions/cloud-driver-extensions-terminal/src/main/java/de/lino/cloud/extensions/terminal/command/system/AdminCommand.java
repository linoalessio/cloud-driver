package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.ArmedConfirmation;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import de.lino.cloud.extensions.terminal.command.CloudUserCommand;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Grants/revokes {@link AuthUser#isAdmin()} for an account, by e-mail address - the only place
 * in this codebase that can set that flag, since it is deliberately never reachable via any REST
 * route (see {@code DefaultRestFactory}'s {@code /admin/authUsers} routes' own Javadoc for why:
 * exposing this over HTTP, even behind a check, would be a privilege-escalation hole the moment
 * that check itself had a bug). Modeled on {@link CloudUserCommand}'s shape (email-keyed lookup,
 * {@link Terminal#displayApproved} status lines).
 *
 * <p>A grant is armed by one invocation and performed by a second carrying {@code confirm}: the
 * flag it sets unlocks every account's record, the whole audit trail and the server metrics, so it
 * must not be reachable by one mistyped line. A revoke takes effect on the first invocation and
 * deliberately stays that way - shutting a privileged account out is the containment action, and
 * it must never take two steps. Both are recorded in the audit trail by the service that performs
 * them.
 */
public class AdminCommand implements Command {

    /**
     * The arm-then-confirm guard a grant goes through, keyed on the exact command line so arming
     * a grant for one address can never confirm one for another.
     */
    private static final ArmedConfirmation CONFIRMATION = new ArmedConfirmation(Duration.ofSeconds(15));

    /** @return {@code "admin"} */
    @Override
    public @NotNull String name() {
        return "admin";
    }

    /** @return {@code "isAdmin"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("isAdmin");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Grant or revoke admin privileges for an account";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("admin grant <email>", "Arm giving one account admin privileges (does nothing on its own)"),
                CommandUsage.of("admin grant <email> confirm", "Confirm the armed grant, within 15s of arming it"),
                CommandUsage.of("admin revoke <email>", "Take one account's admin privileges away, at once")
        );
    }

    /**
     * Grants (armed, then confirmed) or revokes (at once) the admin flag for one account,
     * answering immediately when the account already is what the invocation asks for.
     *
     * @param arguments {@code grant}/{@code revoke}, the account's e-mail address, and - for a
     *     confirming grant - the word {@code confirm}
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (!arguments.hasLength(1) || (!arguments.hasCommand(0, "grant") && !arguments.hasCommand(0, "revoke"))) {
            this.sendUsage();
            return;
        }

        final boolean grant = arguments.hasCommand(0, "grant");
        final String emailAddress = arguments.command(1);

        final IAuthService authService = CloudDriver.getInstance().getServiceContainer().getAuthService();
        if (authService == null) {
            terminal.displayApproved("&cThe REST/auth subsystem isn't running yet - no accounts to look up.");
            return;
        }

        final Optional<AuthUser> authUser = authService.getAuthUsers().stream()
                .filter(candidate -> candidate.getEmailAddress().equalsIgnoreCase(emailAddress))
                .findFirst();

        if (authUser.isEmpty()) {
            terminal.displayApproved("Account '&b%s&7' does not exist", emailAddress);
            return;
        }

        // Answered before the guard: an operator asking for something that is already true must
        // not end up arming a grant, and must not leave a stale armed slot behind either.
        if (authUser.get().isAdmin() == grant) {
            terminal.displayApproved("Account '&b%s&7' is already %s", emailAddress, grant ? "&aan admin" : "&cnot an admin");
            return;
        }

        final String canonicalEmail = authUser.get().getEmailAddress();
        if (grant && !CONFIRMATION.armOrConfirm(terminal, arguments, 2, "admin grant " + canonicalEmail,
                "gives '" + canonicalEmail + "' the admin flag - read access to every account's record, the whole audit trail and the server metrics")) {
            return;
        }

        authService.setAdmin(authUser.get().getId(), grant);
        if (grant) {
            terminal.displayApproved("Account '&b%s&7' is now &aan admin", emailAddress);
        } else {
            terminal.displayApproved("Account '&b%s&7' is &cno longer &7an admin", emailAddress);
        }
    }

}
