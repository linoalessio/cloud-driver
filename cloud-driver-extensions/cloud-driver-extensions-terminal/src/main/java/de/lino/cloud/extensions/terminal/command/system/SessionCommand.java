package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
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
 * days). Only the refresh token is stored server-side, so only it can be revoked - the access JWT
 * is stateless by design and is validated by signature alone.
 *
 * <p>The practical consequence, and it must not be glossed over: <b>revoking does not sign
 * anybody out instantly.</b> An already-issued access token keeps working until it expires, up to
 * twelve hours later. What revocation guarantees is that no <em>new</em> access token can be
 * minted - so the session ends within that window and cannot be extended past it.
 *
 * <p>The one case where that gap is already closed is a deleted account: bearer validation
 * additionally confirms the account still exists, so a token for a deleted account is rejected
 * immediately. For a still-existing account being locked out, the twelve-hour ceiling applies.
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
            terminal.displayApproved("&fsession list <email>");
            terminal.displayApproved("&fsession revoke <email>");
            return;
        }

        final String email = arguments.command(1);
        final Optional<AuthUser> authUser = this.findByEmail(authService, email);
        if (authUser.isEmpty()) {
            terminal.displayApproved("No account found for '&b%s&7'", email);
            return;
        }

        final List<RefreshToken> tokens = this.tokensFor(authUser.get().getId());

        if (arguments.hasCommand(0, "list")) {
            this.list(terminal, email, tokens);
            return;
        }

        if (arguments.hasCommand(0, "revoke")) {
            this.revoke(terminal, authService, email, tokens);
            return;
        }

        terminal.displayApproved("&fsession list <email>");
        terminal.displayApproved("&fsession revoke <email>");
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

    /** Revokes every still-usable refresh token for one account. */
    private void revoke(final Terminal terminal, final IAuthService authService, final String email, final List<RefreshToken> tokens) {
        int revoked = 0;
        for (final RefreshToken token : tokens) {
            if (token.isRevoked() || token.isExpired()) continue;
            try {
                authService.revokeRefreshToken(token.getToken());
                revoked++;
            } catch (final RuntimeException ignored) {
                // revokeRefreshToken is already idempotent on an absent/revoked token, so a failure
                // here is a genuine persistence problem - counted as not-revoked rather than
                // aborting the rest, so one bad row cannot leave the other sessions alive.
            }
        }

        terminal.displayApproved("Revoked &b%s &7refresh token(s) for &b%s&7.", revoked, email);
        terminal.displayApproved("&e! &7Already-issued access tokens stay valid for up to &b12 hours&7 - see this command's own docs.");
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
     * <p>A full section scan filtered in memory: {@code RefreshToken} is primary-keyed on the token
     * itself (so that a refresh is an O(1) point lookup, which is the operation that actually
     * happens constantly), which leaves no index on the account id. The same trade-off every other
     * non-primary-key lookup in this codebase accepts, and this one runs by hand, rarely.
     */
    private List<RefreshToken> tokensFor(final String authUserId) {
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            return dataFactory.getEntitiesAsync(RefreshToken.class).join().stream()
                    .filter(token -> authUserId.equals(token.getAuthUserId()))
                    .toList();
        } catch (final RuntimeException failed) {
            return List.of();
        }
    }

}
