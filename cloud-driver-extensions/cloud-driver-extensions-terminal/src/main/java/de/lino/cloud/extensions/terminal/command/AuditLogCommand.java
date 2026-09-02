package de.lino.cloud.extensions.terminal.command;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Read-only browser over the persisted audit trail ({@link AuditEvent}) - see {@code
 * architecture/SERVICES.md} item 11 and {@code CLAUDE.md}'s "Audit log service" section for the
 * full schema/call-site picture. Modeled on {@link StatisticsCommand}'s read-only shape - this
 * command never writes anything, only lists what {@code AuthService}/{@code CloudUserService}
 * have already recorded via {@code AuditLogService#record}.
 */
public class AuditLogCommand implements Command {

    /** How many entries {@code auditLog} (no arguments) or {@code auditLog <email>} prints, most-recent-first. */
    private static final int DEFAULT_LIMIT = 20;

    /** Formats {@link AuditEvent#getTimestampEpochMillis()} for display, in the system default time zone. */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public @NotNull String name() {
        return "auditLog";
    }

    @Override
    public @NotNull List<String> aliases() {
        return List.of("audit");
    }

    @Override
    public @NotNull String description() {
        return "Browse the persisted security audit trail (logins, registration, password/e-mail changes, file/account deletes)";
    }

    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final DataFactory dataFactory = CloudDriver.getInstance().getFactoryContainer().getDataFactory();

        final List<AuditEvent> allEvents;
        try {
            allEvents = dataFactory.getEntities(AuditEvent.class).stream()
                    .sorted(Comparator.comparingLong(AuditEvent::getTimestampEpochMillis).reversed())
                    .toList();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            terminal.displayApproved("&cFailed to read the audit trail: &7%s", e.getMessage());
            return;
        }

        final List<AuditEvent> toDisplay;
        if (arguments.isEmpty()) {
            toDisplay = allEvents.stream().limit(DEFAULT_LIMIT).toList();
        } else if (arguments.hasCommand(0, "all")) {
            toDisplay = allEvents;
        } else {

            final String emailAddress = arguments.command(0);
            final Optional<String> actorAuthUserId = this.resolveAuthUserId(emailAddress);

            if (actorAuthUserId.isEmpty()) {
                terminal.displayApproved("Account '&b%s&7' does not exist", emailAddress);
                return;
            }

            toDisplay = allEvents.stream()
                    .filter(event -> actorAuthUserId.get().equals(event.getActorAuthUserId()))
                    .limit(DEFAULT_LIMIT)
                    .toList();
        }

        terminal.emptyLine();
        terminal.displayApproved("Audit trail (&b%s&7 of &b%s&7 total entries):", toDisplay.size(), allEvents.size());
        if (toDisplay.isEmpty()) terminal.displayApproved("&8(no matching entries)");

        for (final AuditEvent event : toDisplay) {
            final String timestamp = Instant.ofEpochMilli(event.getTimestampEpochMillis()).atZone(ZoneId.systemDefault()).format(TIMESTAMP_FORMAT);
            final String actor = event.getActorAuthUserId() == null ? "-" : this.resolveEmail(event.getActorAuthUserId());
            final String target = event.getTargetId() == null ? "-" : event.getTargetId();
            terminal.displayApproved("&8- &7%s &8| &b%s &8| actor: &7%s &8| target: &7%s", timestamp, event.getAction(), actor, target);
        }
        
        terminal.emptyLine();

    }

    /** Resolves {@code emailAddress} to a registered {@link AuthUser#getId()}, or {@link Optional#empty()} if no account is registered under it. */
    private Optional<String> resolveAuthUserId(final String emailAddress) {
        final IAuthService authService = CloudDriver.getInstance().getServiceContainer().getAuthService();
        if (authService == null) {
            return Optional.empty();
        }
        return authService.getAuthUsers().stream()
                .filter(candidate -> candidate.getEmailAddress().equalsIgnoreCase(emailAddress))
                .map(AuthUser::getId)
                .findFirst();
    }

    /** Resolves {@code authUserId} back to its account's e-mail address for display, or the raw id if the account no longer exists/the auth subsystem isn't running. */
    private String resolveEmail(final String authUserId) {
        final IAuthService authService = CloudDriver.getInstance().getServiceContainer().getAuthService();
        if (authService == null) {
            return authUserId;
        }
        return authService.getAuthUser(authUserId).map(AuthUser::getEmailAddress).orElse(authUserId);
    }

}
