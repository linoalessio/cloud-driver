package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.api.terminal.service.CommandUsage;
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

    /** @return how this command is invoked */
    @Override
    public @NotNull List<CommandUsage> usages() {
        return List.of(
                CommandUsage.of("share list <email>", "What that account shared with other accounts"),
                CommandUsage.of("share links [email]", "Its public links, or every public link on this deployment"),
                CommandUsage.of("share revoke-link <email> <fileId> <token>", "Arm killing one leaked public link (does nothing on its own)"),
                CommandUsage.of("share revoke-link <email> <fileId> <token> confirm", "Confirm it, within 15s of arming"),
                CommandUsage.of("share revoke-links <email>", "Arm killing every public link that account holds"),
                CommandUsage.of("share revoke-links <email> confirm", "Confirm it, within 15s of arming")
        );
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

        if (arguments.hasCommand(0, "revoke-link") && arguments.hasLength(3)) {
            this.revokeLink(terminal, arguments, arguments.command(1), arguments.command(2), arguments.command(3));
            return;
        }

        if (arguments.hasCommand(0, "revoke-links") && arguments.hasLength(1)) {
            this.revokeAllLinks(terminal, arguments, arguments.command(1));
            return;
        }

        this.sendUsage();
    }

    /**
     * Revokes one public link.
     *
     * <p>This command is otherwise read-only by design, on the reasoning that revoking a share is
     * the owner's decision and they have a interface for it. That reasoning covers the ordinary
     * case and not the one an operator is actually called about: a link that has leaked. A public
     * link is the only way to read content here without an account at all, and until now the
     * console could show that such links were active while being unable to do anything about one.
     *
     * <p>Delegates to the same service method the owner's own interface calls, so the ownership
     * check and the audit entry stay on the normal path.
     */
    private void revokeLink(final Terminal terminal, final CommandArguments arguments,
                             final String email, final String fileId, final String token) {
        final String ownerAuthUserId = this.resolveAuthUserId(terminal, email);
        if (ownerAuthUserId == null) return;
        if (!confirmRevocation(terminal, arguments, 4, "share revoke-link " + email + " " + fileId + " " + token,
                "revokes one public link on file " + fileId + " held by " + email)) {
            return;
        }
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cThe REST/auth subsystem isn't running yet.");
            return;
        }
        try {
            cloudUserService.revokePublicFileLink(ownerAuthUserId, fileId, token);
            terminal.displayApproved("Public link on file &b%s &7(&b%s&7) &crevoked&7.", fileId, email);
        } catch (final RuntimeException failure) {
            terminal.displayApproved("&cCould not revoke that link: %s", failure.getMessage());
        }
    }

    /** Revokes every public link one account holds - see {@link #revokeLink}. */
    private void revokeAllLinks(final Terminal terminal, final CommandArguments arguments, final String email) {
        final String ownerAuthUserId = this.resolveAuthUserId(terminal, email);
        if (ownerAuthUserId == null) return;

        final List<PublicShareLink> links = this.entities(PublicShareLink.class).stream()
                .filter(link -> ownerAuthUserId.equals(link.getOwnerAuthUserId()))
                .toList();
        if (links.isEmpty()) {
            terminal.displayApproved("&b%s &7holds no public links.", email);
            return;
        }
        if (!confirmRevocation(terminal, arguments, 2, "share revoke-links " + email,
                "revokes all " + links.size() + " public link(s) held by " + email)) {
            return;
        }
        final ICloudUserService cloudUserService = CloudDriver.getInstance().getServiceContainer().getCloudUserService();
        if (cloudUserService == null) {
            terminal.displayApproved("&cThe REST/auth subsystem isn't running yet.");
            return;
        }
        int revoked = 0;
        for (final PublicShareLink link : links) {
            try {
                cloudUserService.revokePublicFileLink(ownerAuthUserId, link.getStoredFileId(), link.getToken());
                revoked++;
            } catch (final RuntimeException failure) {
                terminal.displayApproved("&cCould not revoke the link on file %s: %s", link.getStoredFileId(), failure.getMessage());
            }
        }
        terminal.displayApproved("&b%s &7public link(s) &crevoked &7for &b%s&7.", revoked, email);
    }

    /** What revocation is currently armed, as its full command line, or {@code null}. */
    private static final java.util.concurrent.atomic.AtomicReference<String> ARMED =
            new java.util.concurrent.atomic.AtomicReference<>();

    /** When the armed revocation stops being confirmable, in epoch millis, or {@code null}. */
    private static final java.util.concurrent.atomic.AtomicReference<Long> ARMED_UNTIL =
            new java.util.concurrent.atomic.AtomicReference<>();

    /**
     * Arm-then-confirm guard, matching the shape every other destructive command here uses.
     *
     * <p>Revoking is an operator acting on a user's own data, so it is never a single keystroke -
     * and arming is keyed on the exact command line, so arming one revocation can never confirm a
     * different one.
     *
     * @param terminal where to print the warning
     * @param arguments the invocation, checked for the confirming token
     * @param confirmIndex the positional index the confirming token would occupy
     * @param commandLine the exact form to re-run to confirm
     * @param whatItDoes a plain description, shown while arming
     * @return {@code true} if the caller should proceed
     */
    private static boolean confirmRevocation(final Terminal terminal, final CommandArguments arguments,
                                              final int confirmIndex, final String commandLine, final String whatItDoes) {
        final boolean confirming = arguments.hasLength(confirmIndex) && arguments.hasCommand(confirmIndex, "confirm");
        final Long armedUntil = ARMED_UNTIL.get();
        if (confirming && commandLine.equals(ARMED.get()) && armedUntil != null && armedUntil > System.currentTimeMillis()) {
            ARMED.set(null);
            ARMED_UNTIL.set(null);
            return true;
        }
        if (confirming) {
            terminal.displayApproved("&cNothing armed for that, or the confirmation window has passed - run it again without 'confirm' first.");
            ARMED.set(null);
            ARMED_UNTIL.set(null);
            return false;
        }
        ARMED.set(commandLine);
        ARMED_UNTIL.set(System.currentTimeMillis() + 15_000L);
        terminal.displayApproved("&c&lThis %s.", whatItDoes);
        terminal.displayApproved("Anyone currently holding such a URL loses access immediately. To confirm, run");
        terminal.displayApproved("&c%s confirm &7within &b15 seconds&7.", commandLine);
        return false;
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
