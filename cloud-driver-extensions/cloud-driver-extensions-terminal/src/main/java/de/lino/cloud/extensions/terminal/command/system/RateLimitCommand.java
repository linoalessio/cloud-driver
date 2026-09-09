package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.ratelimit.RateLimitAdmin;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Inspects and clears the running REST layer's rate-limit windows.
 *
 * <h2>Why this exists</h2>
 *
 * Until this command there was no way whatsoever to clear an exhausted window: an affected caller
 * had to wait the window out, or the whole server had to be restarted. That is a genuinely bad
 * pair of options, and it has already been forced - a real user was locked out of logging in after
 * ordinary Dashboard use consumed the shared {@code /auth/*} budget, and restarting a production
 * server to unblock one account is not a proportionate remedy.
 *
 * <p>Resets cover both backing stores (Redis and the in-process fallback), because a caller that
 * hit Redis while it was healthy and then fell back during a blip has genuinely left a window in
 * both - clearing only the live one would be a silent half-success.
 */
public class RateLimitCommand implements Command {

    /** @return {@code "rateLimit"} */
    @Override
    public @NotNull String name() {
        return "rateLimit";
    }

    /** @return {@code "rl"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("rl");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Inspect rate-limit windows, and clear them for one caller or for everyone";
    }

    /**
     * Dispatches to {@code status} (the default) or {@code reset [identity]}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final RateLimitAdmin rateLimitAdmin = CloudDriver.getInstance().getServiceContainer().getRateLimitAdmin();

        if (rateLimitAdmin == null) {
            terminal.displayApproved("&cRate-limit control is not published &7- the REST extension has not started yet.");
            return;
        }

        if (arguments.hasCommand(0, "reset")) {
            final String identity = arguments.hasLength(1) ? arguments.command(1) : null;
            final int cleared = rateLimitAdmin.reset(identity);
            if (identity == null) {
                terminal.displayApproved("Cleared &b%s &7rate-limit window(s) for &eevery caller&7.", cleared);
            } else {
                terminal.displayApproved("Cleared &b%s &7rate-limit window(s) for '&b%s&7'.", cleared, identity);
                if (cleared == 0) {
                    // Not an error: an identity with no active window is the normal state, and the
                    // key shape differs between the two limiters, so "nothing cleared" usually
                    // just means the caller was never actually limited.
                    terminal.displayApproved("&8  (that caller had no active window - nothing was being limited)");
                }
            }
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            final RateLimitAdmin.RateLimitStatus status = rateLimitAdmin.status();
            terminal.emptyLine();
            terminal.displayApproved("Counting backend:  %s", status.redisBacked() ? "&aRedis &8(survives restarts)" : "&ein-process &8(reset on restart)");
            terminal.displayApproved("In-process /auth windows: &b%s", status.authWindows());
            terminal.displayApproved("In-process READ windows:  &b%s", status.apiReadWindows());
            if (status.redisBacked()) {
                terminal.displayApproved("&8Redis-held windows are not counted here - enumerating them would need a keyspace scan.");
            }
            terminal.emptyLine();
            return;
        }

        terminal.displayApproved("&frateLimit status");
        terminal.displayApproved("&frateLimit reset [identity]   &8(identity = account id or IP; omit to clear everything)");
    }

}
