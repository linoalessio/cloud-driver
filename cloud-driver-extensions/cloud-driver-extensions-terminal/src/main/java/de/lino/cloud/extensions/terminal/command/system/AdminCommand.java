package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.extensions.terminal.command.CloudUserCommand;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;

/**
 * Grants/revokes {@link AuthUser#isAdmin()} for an account, by e-mail address - the only place
 * in this codebase that can set that flag, since it is deliberately never reachable via any REST
 * route (see {@code DefaultRestFactory}'s {@code /admin/authUsers} routes' own Javadoc for why:
 * exposing this over HTTP, even behind a check, would be a privilege-escalation hole the moment
 * that check itself had a bug). Modeled on {@link CloudUserCommand}'s shape (email-keyed lookup,
 * {@link Terminal#displayApproved} status lines).
 */
public class AdminCommand implements Command {

    @Override
    public @NotNull String name() {
        return "admin";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("isAdmin");
    }

    @Override
    public @NotNull String description() {
        return "Grant or revoke admin privileges for an account";
    }

    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (!arguments.hasLength(1) || (!arguments.hasCommand(0, "grant") && !arguments.hasCommand(0, "revoke"))) {
            this.sendHelp();
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

        authService.setAdmin(authUser.get().getId(), grant);
        if (grant) {
            terminal.displayApproved("Account '&b%s&7' is now &aan admin", emailAddress);
        } else {
            terminal.displayApproved("Account '&b%s&7' is &cno longer &7an admin", emailAddress);
        }
    }

    private void sendHelp() {
        final Terminal terminal = this.terminal();
        terminal.displayApproved("&fadmin grant <email>");
        terminal.displayApproved("&fadmin revoke <email>");
    }

}
