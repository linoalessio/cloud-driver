package de.lino.cloud.extensions.terminal.command.system;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.terminal.Terminal;
import de.lino.cloud.api.terminal.service.Command;
import de.lino.cloud.plugin.security.secrets.SecretRedactor;
import de.lino.database.json.JsonDocument;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Prints the configuration this process is actually running with, secrets redacted.
 *
 * <h2>Why print something an operator could just {@code cat}</h2>
 *
 * Because what the file says and what the process is running with are not the same question, and
 * this codebase has already been caught out by the difference. A live-only edit on the server
 * silently reverted the next time a deploy ran, because the deploy script overwrites the remote
 * file with the repository's own copy verbatim - so the running behaviour and the file an operator
 * had just edited disagreed, with nothing anywhere reporting it.
 *
 * <p>It also makes the <em>absent</em> keys legible, which a raw file cannot. Several optional
 * keys have consequential defaults - most notably, an unset upload quota means a strict 1 MiB per
 * account, not "unlimited" - and a key simply not being in the file gives no hint of that.
 *
 * <h2>Redaction</h2>
 *
 * Values are redacted by key name, not by looking at the value, and the match is a substring
 * check against a small deny-list of secret-ish words. Redaction is deliberately conservative:
 * a wrongly-redacted value costs an operator one look at the file, while a wrongly-printed one
 * puts a live credential into terminal scrollback and any log capturing it. {@code SecretRedactor}
 * is additionally applied to whatever survives, as defence in depth.
 */
public class ConfigCommand implements Command {

    /**
     * Key-name fragments whose values are never printed. Matched case-insensitively as substrings,
     * so a future {@code "smtp-password"}-alike is covered without anyone remembering to add it.
     */
    private static final Set<String> SECRET_KEY_FRAGMENTS = Set.of(
            "password", "secret", "key", "token", "credential"
    );

    /**
     * Exceptions to {@link #SECRET_KEY_FRAGMENTS} - key names that contain a secret-ish word but
     * hold no secret. Without these, the most operationally important keys in the file (which AWS
     * key, which S3 prefix) would be hidden for no reason.
     */
    private static final Set<String> NEVER_SECRET_KEYS = Set.of(
            "aws-kms-key-id", "aws-s3-key-prefix"
    );

    /** @return {@code "config"} */
    @Override
    public @NotNull String name() {
        return "config";
    }

    /** @return {@code "cfg"}, {@code "configuration"} */
    @Override
    public @NotNull List<String> aliases() {
        return List.of("cfg", "configuration");
    }

    /** @return this command's description */
    @Override
    public @NotNull String description() {
        return "Print the effective configuration this process is running with, secrets redacted";
    }

    /**
     * Prints every configured key, then the notable unset ones.
     *
     * @param arguments unused
     */
    @Override
    public void execute(@NotNull final CommandArguments arguments) {

        final Terminal terminal = this.terminal();
        final JsonDocument configuration;
        try {
            configuration = CloudDriver.getInstance().getConfiguration();
        } catch (final RuntimeException unreadable) {
            terminal.displayApproved("&cCould not read configuration.json&7: %s", unreadable.getMessage());
            return;
        }

        terminal.emptyLine();
        terminal.displayApproved("&8--- &fEffective configuration &8---");

        final Set<String> keys = new TreeSet<>(configuration.getKeys());
        if (keys.isEmpty()) {
            terminal.displayApproved("&8  (configuration.json is empty)");
        }
        for (final String key : keys) {
            terminal.displayApproved("&8- &7%-38s &f%s", key, this.renderValue(key, configuration));
        }

        this.reportNotableDefaults(terminal, configuration);
        terminal.emptyLine();
    }

    /** A key's value, or a redaction marker for anything secret-shaped. */
    private String renderValue(final String key, final JsonDocument configuration) {
        if (this.isSecret(key)) return "&8<redacted>";
        try {
            final String value = configuration.getString(key);
            return SecretRedactor.redact(value);
        } catch (final RuntimeException notAString) {
            // Non-string values (numbers, booleans) do not need redacting and are safe as-is.
            try {
                return String.valueOf(configuration.getLong(key));
            } catch (final RuntimeException notANumber) {
                return "&8<unprintable>";
            }
        }
    }

    /** Whether {@code key}'s value must never be printed. */
    private boolean isSecret(final String key) {
        final String lowered = key.toLowerCase(Locale.ROOT);
        if (NEVER_SECRET_KEYS.contains(lowered)) return false;
        return SECRET_KEY_FRAGMENTS.stream().anyMatch(lowered::contains);
    }

    /**
     * Calls out unset keys whose default is consequential enough that its absence is worth
     * knowing - the ones where "not in the file" does not mean "feature off".
     */
    private void reportNotableDefaults(final Terminal terminal, final JsonDocument configuration) {
        terminal.emptyLine();
        terminal.displayApproved("&8--- &fNotable defaults &7(key absent) &8---");

        boolean printedAny = false;
        if (!configuration.contains("cloud-user-max-bytes-to-upload")) {
            terminal.displayApproved("&8- &e! &7Upload quota unset - every account is capped at &c1 MiB&7, not unlimited.");
            printedAny = true;
        }
        if (!configuration.contains("aws-s3-bucket")) {
            terminal.displayApproved("&8- &7No S3 bucket - file content is stored inline in Postgres.");
            printedAny = true;
        }
        if (!configuration.contains("intelligence-shared-secret")) {
            terminal.displayApproved("&8- &7No intelligence secret - semantic search cannot load at all.");
            printedAny = true;
        }
        if (!configuration.contains("trust-proxy-headers")) {
            terminal.displayApproved("&8- &7Proxy headers untrusted - rate limiting keys on the direct peer address.");
            printedAny = true;
        }
        if (!printedAny) {
            terminal.displayApproved("&8  (every notable key is explicitly set)");
        }
    }

}
