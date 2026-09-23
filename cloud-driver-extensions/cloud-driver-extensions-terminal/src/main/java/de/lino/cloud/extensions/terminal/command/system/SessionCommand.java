package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.auth.entity.RefreshToken;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Lists and revokes an account's long-lived sessions.
 *
 * <h2>What this can and cannot do</h2>
 *
 * A session is two tokens: a short-lived access JWT (12 hours) and a long-lived refresh token (30
 * days). Only the refresh token is stored server-side, so only it can be listed - the access JWT
 * is stateless and never persisted.
 *
 * <p><b>Revoking signs the account out at once.</b> Every access token carries the account's
 * session generation as a signed claim, and a forced sign-out advances it, so every token already
 * issued is refused on its very next request rather than living out its twelve hours. The refresh
 * tokens are revoked in the same step, and the account's live-update sockets are closed, so
 * nothing the account holds can be renewed or kept open.
 *
 * <p>The way back in is an ordinary sign-in: no password is changed and no data is touched.
 */
public class SessionCommand implements Command {

    /** Timestamp format for the listing. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** @return {@code "session"} */
    @Override
    public @NotNull String name() {
        return "session";
    }

    /** @return {@code "sessions"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("sessions");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "List an account's refresh tokens, or revoke all of them (forced sign-out)";
    }

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("session list <email>", "That account's refresh tokens, never their values"),
                CommandUsage.of("session revoke <email>", "End every session - signs the account out everywhere, at once")
        );
    }

    /**
     * Dispatches to {@code list <email>} or {@code revoke <email>}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final IAuthService authService = CloudDriver.getInstance().getServiceContainer().getAuthService();

        if (authService == null) {
            terminal.displayApproved("&cAuthService is not published &7- the REST extension has not started yet.");
            return;
        }

        if (!arguments.hasLength(1)) {
            this.sendUsage();
            return;
        }

        final String email = arguments.command(1);
        final Optional<AuthUser> authUser = this.findByEmail(authService, email);
        if (authUser.isEmpty()) {
            terminal.displayApproved("No account found for '&b%s&7'", email);
            return;
        }

        if (arguments.hasCommand(0, "list")) {
            this.list(terminal, email, this.tokensFor(authUser.get().getId()));
            return;
        }

        if (arguments.hasCommand(0, "revoke")) {
            this.revoke(terminal, authService, email, authUser.get().getId());
            return;
        }

        this.sendUsage();
    }

    /** Prints one account's refresh tokens, without ever printing a token value. */
    private void list(final Terminal terminal, final String email, final List<RefreshToken> tokens) {
        terminal.emptyLine();
        terminal.displayApproved("Refresh tokens for &b%s&7: &b%s", email, tokens.size());
        for (final RefreshToken token : tokens) {
            final String state = token.isRevoked() ? "&8revoked" : token.isExpired() ? "&8expired" : "&aactive";
            // Only a short prefix, never the value: a refresh token is a live credential, and
            // terminal output is routinely captured into logs and screenshots.
            terminal.displayApproved("&8- %s &7expires &b%s &8| %s...",
                    state, TIMESTAMP.format(Instant.ofEpochMilli(token.getExpiresAtEpochMillis())),
                    token.getToken().substring(0, Math.min(8, token.getToken().length())));
        }
        if (tokens.isEmpty()) terminal.displayApproved("&8  (none - this account has no persisted sessions)");
        terminal.emptyLine();
    }

    /**
     * Ends every session of one account - refresh tokens revoked, already-issued access tokens
     * refused, live-update sockets closed - in one call.
     *
     * @param terminal where the outcome is printed
     * @param authService the published account service
     * @param email the address the operator named, for the output
     * @param authUserId the resolved account whose sessions to end
     */
    private void revoke(final Terminal terminal, final IAuthService authService, final String email, final String authUserId) {
        final int revoked;
        try {
            revoked = authService.endAllSessions(authUserId);
        } catch (final RuntimeException failed) {
            terminal.displayApproved("&cCould not end the sessions of &b%s&c: %s", email, failed.getMessage());
            return;
        }

        terminal.displayApproved("Revoked &b%s &7refresh token(s) for &b%s&7.", revoked, email);
        terminal.displayApproved("&7The sign-out takes effect on that account's &bnext request&7 - every access token "
                + "already issued is refused from now on.");
    }

    /** Resolves an account by e-mail, case-insensitively - the same lookup shape the other commands use. */
    private Optional<AuthUser> findByEmail(final IAuthService authService, final String email) {
        return authService.getAuthUsers().stream()
                .filter(user -> user.getEmailAddress() != null && user.getEmailAddress().equalsIgnoreCase(email))
                .findFirst();
    }

    /**
     * Every {@link RefreshToken} belonging to one account.
     *
     * <p>An indexed lookup through {@link RefreshToken#INDEX_AUTH_USER_ID}, not a scan - the same
     * index a credential change uses to end an account's sessions. An account with no sessions at
     * all resolves to an empty list rather than an error.
     *
     * @param authUserId the account whose sessions to list
     * @return that account's refresh tokens, or an empty list if they cannot be read
     */
    private List<RefreshToken> tokensFor(final String authUserId) {
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            return dataFactory.getEntitiesByIndex(RefreshToken.class, RefreshToken.INDEX_AUTH_USER_ID, authUserId);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException | RuntimeException failed) {
            return List.of();
        }
    }

}
