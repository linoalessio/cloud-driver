package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Verifies outgoing mail without having to register a real account to trigger it.
 *
 * <h2>Why this is worth its own command</h2>
 *
 * Which {@link EmailSender} a deployment ends up with is decided once, at startup, by a silent
 * three-way fallback: AWS SES if a region is configured, otherwise SMTP if a host is, otherwise a
 * {@code LoggingEmailSender} that writes the message to a log and delivers nothing. Nothing fails,
 * nothing warns, and the server looks entirely healthy either way - the first evidence anyone gets
 * is a user reporting that no verification code arrived.
 *
 * <p>That is not hypothetical here. A real incident had every registration to a new address fail
 * with a bare {@code 500}, whose actual cause (an AWS account still in the SES sandbox, where
 * every recipient must itself be a verified identity) was visible only in a stack trace. This
 * command reproduces exactly the same send path in one step and prints the real error - which
 * would have identified that in seconds.
 *
 * <p><b>{@code mail test} really sends a message.</b> It is not a dry run: only an actual delivery
 * exercises credentials, the sender identity, recipient verification and the transport together,
 * and any one of those failing alone is enough to break registration.
 */
public class MailCommand implements Command {

    /** @return {@code "mail"} */
    @Override
    public @NotNull String name() {
        return "mail";
    }

    /** @return {@code "email"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("email");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Show which e-mail sender was resolved, and send a real test message";
    }

    /**
     * Dispatches to {@code status} (the default) or {@code test <address>}.
     *
     * @param arguments the sub-command and its own arguments
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final EmailSender emailSender = CloudDriver.getInstance().getServiceContainer().getEmailSender();

        if (emailSender == null) {
            terminal.displayApproved("&cNo EmailSender is published &7- the REST extension has not started yet.");
            return;
        }

        if (arguments.isEmpty() || arguments.hasCommand(0, "status")) {
            this.status(terminal, emailSender);
            return;
        }

        if (arguments.hasCommand(0, "test") && arguments.hasLength(1)) {
            this.sendTest(terminal, emailSender, arguments.command(1));
            return;
        }

        terminal.displayApproved("&fmail status");
        terminal.displayApproved("&fmail test <address>");

    }

    /** Reports which implementation was resolved, and says plainly when that one delivers nothing. */
    private void status(final Terminal terminal, final EmailSender emailSender) {
        final String implementation = emailSender.getClass().getSimpleName();
        final boolean deliversNothing = implementation.contains("Logging");

        terminal.emptyLine();
        terminal.displayApproved("Resolved e-mail sender: &b%s", implementation);
        if (deliversNothing) {
            terminal.displayApproved("&c! &7This sender writes messages to the log and &cdelivers nothing&7.");
            terminal.displayApproved("&7Set &faws-ses-region&7/&faws-ses-from-address&7 or the &fsmtp-*&7 keys in configuration.json.");
        } else {
            terminal.displayApproved("&7Run &fmail test <address> &7to confirm delivery actually works end to end.");
        }
        terminal.emptyLine();
    }

    /**
     * Sends a real message and reports the outcome.
     *
     * <p>Prints the exception's own message on failure rather than a generic one: the useful
     * detail is almost always in there verbatim (an unverified SES identity, a rejected SMTP
     * credential, an unknown configuration set), and paraphrasing it would discard the one thing
     * an operator actually needs.
     */
    private void sendTest(final Terminal terminal, final EmailSender emailSender, final String address) {
        terminal.displayApproved("Sending a test message to &b%s&7...", address);
        try {
            emailSender.send(
                    address,
                    "cloud-driver test message",
                    "<p>This is a test message from <strong>cloud-driver</strong>.</p>"
                            + "<p>If you are reading it, outgoing mail is configured correctly.</p>",
                    "This is a test message from cloud-driver. If you are reading it, outgoing mail is configured correctly."
            );
            terminal.displayApproved("&aSent&7. If nothing arrives, the failure is downstream (spam filter, or a silent provider drop).");
        } catch (final EmailDeliveryException failed) {
            terminal.displayApproved("&cDelivery failed&7: %s", failed.getMessage());
            if (failed.getCause() != null) {
                terminal.displayApproved("&8  cause: %s", failed.getCause().getMessage());
            }
        } catch (final RuntimeException failed) {
            terminal.displayApproved("&cDelivery failed&7 (%s): %s", failed.getClass().getSimpleName(), failed.getMessage());
        }
    }

}
