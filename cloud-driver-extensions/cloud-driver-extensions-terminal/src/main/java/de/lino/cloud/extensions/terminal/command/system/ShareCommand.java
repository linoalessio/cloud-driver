package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.user.ICloudUser;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.PublicShareLink;
import de.lino.cloud.auth.entity.SharedFileGrant;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Visibility into what an account has shared - with other accounts, and with the public internet.
 *
 * <h2>Why public links get their own emphasis here</h2>
 *
 * A public share link is the only unauthenticated read path in this entire system: anyone holding
 * the URL can download the file, with no account, no token and no rate limiting beyond the token's
 * own unguessability. That is a deliberate feature, but it means an account's public links are the
 * highest-consequence sharing state it has - and until this command there was no operator-side way
 * to see them at all, only to see them one file at a time as their owner.
 *
 * <p>Read-only, deliberately: revoking a share is a decision for the file's owner, who has a
 * proper UI for it, not something to do to them from a server console.
 */
public class ShareCommand implements Command {

    /** Timestamp format for expiry lines. */
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** @return {@code "share"} */
    @Override
    public @NotNull String name() {
        return "share";
    }

    /** @return {@code "shares"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("shares");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Show what an account has shared with other accounts, and its public (unauthenticated) links";
    }

    /**
     * Dispatches to {@code list <email>} (the default form) or {@code links [email]}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();

        if (arguments.hasCommand(0, "links")) {
            this.links(terminal, arguments.hasLength(1) ? arguments.command(1) : null);
            return;
        }

        if (arguments.hasCommand(0, "list") && arguments.hasLength(1)) {
            this.list(terminal, arguments.command(1));
            return;
        }

        terminal.displayApproved("&fshare list <email>");
        terminal.displayApproved("&fshare links [email]   &8(omit the address for every public link on this deployment)");
    }

    /** Lists every account-to-account grant one account has made. */
    private void list(final Terminal terminal, final String email) {
        final String ownerAuthUserId = this.resolveAuthUserId(terminal, email);
        if (ownerAuthUserId == null) return;

        final List<SharedFileGrant> grants = this.entities(SharedFileGrant.class).stream()
                .filter(grant -> ownerAuthUserId.equals(grant.getOwnerAuthUserId()))
                .toList();

        terminal.emptyLine();
        terminal.displayApproved("File shares made by &b%s&7: &b%s", email, grants.size());
        for (final SharedFileGrant grant : grants) {
            final String expiry = grant.getExpiresAtEpochMillis() == null
                    ? "never"
                    : TIMESTAMP.format(Instant.ofEpochMilli(grant.getExpiresAtEpochMillis()));
            terminal.displayApproved("&8- &7file &b%s &8| &7%s &8| expires %s%s",
                    grant.getStoredFileId(), grant.getPermissionLevel(), expiry,
                    grant.isExpired() ? " &8(expired)" : "");
        }
        if (grants.isEmpty()) terminal.displayApproved("&8  (none)");
        terminal.emptyLine();
    }

    /** Lists public links for one account, or across the whole deployment. */
    private void links(final Terminal terminal, final String email) {
        final String ownerAuthUserId;
        if (email == null) {
            ownerAuthUserId = null;
        } else {
            ownerAuthUserId = this.resolveAuthUserId(terminal, email);
            if (ownerAuthUserId == null) return;
        }

        final List<PublicShareLink> links = this.entities(PublicShareLink.class).stream()
                .filter(link -> ownerAuthUserId == null || ownerAuthUserId.equals(link.getOwnerAuthUserId()))
                .toList();

        terminal.emptyLine();
        terminal.displayApproved("Public links%s: &b%s", email == null ? " (deployment-wide)" : " for &b" + email, links.size());
        long active = 0;
        for (final PublicShareLink link : links) {
            final boolean expired = link.isExpired();
            if (!expired) active++;
            final String expiry = link.getExpiresAtEpochMillis() == null
                    ? "never"
                    : TIMESTAMP.format(Instant.ofEpochMilli(link.getExpiresAtEpochMillis()));
            terminal.displayApproved("&8- &7file &b%s &8| expires %s%s",
                    link.getStoredFileId(), expiry, expired ? " &8(expired)" : "");
        }
        if (links.isEmpty()) {
            terminal.displayApproved("&8  (none)");
        } else if (active > 0) {
            terminal.displayApproved("&e! &b%s &7link(s) are &eactive right now &7- anyone holding the URL can download without an account.", active);
        }
        terminal.emptyLine();
    }

    /** Resolves an account id from an e-mail, printing the failure itself. */
    private String resolveAuthUserId(final Terminal terminal, final String email) {
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cCloudUserService is not published&7.");
            return null;
        }
        final Optional<ICloudUser> cloudUser = cloudUserService.getCloudUserByEmail(email);
        if (cloudUser.isEmpty()) {
            terminal.displayApproved("Cloud user '&b%s&7' does not exist", email);
            return null;
        }
        return cloudUser.get().getAuthUserId();
    }

    /**
     * Every row of one entity type, or an empty list if they cannot be read.
     *
     * <p>A full scan, like every other non-primary-key lookup over these grant tables - neither
     * {@code SharedFileGrant} nor {@code PublicShareLink} is keyed by owner. Acceptable for an
     * operator command invoked by hand; it is the same trade-off {@code listFileShares} already
     * makes on the request path.
     */
    private <T extends de.lino.database.database.entity.Serialized> List<T> entities(final Class<T> type) {
        try {
            final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();
            return dataFactory.getEntitiesAsync(type).join();
        } catch (final RuntimeException failed) {
            return List.of();
        }
    }

}
