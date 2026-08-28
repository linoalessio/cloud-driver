package de.lino.cloud.bootstrap;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.auth.AuthService;
import de.lino.cloud.auth.jwt.JjwtSigner;
import de.lino.cloud.plugin.security.password.Argon2idPasswordHasher;

import java.io.Console;
import java.io.IOException;
import java.util.Arrays;

/**
 * Operator-run, one-off account creation - deliberately not a public HTTP endpoint, see {@link
 * de.lino.cloud.api.jwt.auth.IAuthService#register}'s own Javadoc. Run against the shaded jar:
 * {@code java -cp cloud-driver-bootstrap-*.jar de.lino.cloud.bootstrap.CreateUserCli <email>}
 * (works unmodified against the existing jar, since {@code maven-shade-plugin} bundles every
 * class regardless of which one's {@code main} is invoked via {@code -cp}).
 *
 * <p>Reads the password via {@link Console#readPassword} - never a CLI argument, so it never
 * lands in shell history or a process listing - which requires a real, interactive terminal
 * (unlike, e.g., an IDE's Run tool window, which pipes stdin/stdout rather than allocating one
 * - see this repo's own {@code terminal} package Javadoc for the same restriction on {@code
 * jline}); {@link System#console()} returns {@code null} otherwise, in which case this exits
 * immediately with a message rather than attempting the account creation blind. The password
 * {@code char[]} is zeroed in a {@code finally} block once used, regardless of outcome.
 *
 * <p>Immediately logs the new account in and prints the resulting JWT, so a single run produces
 * both a working {@link de.lino.cloud.api.jwt.user.AuthUser} and a token ready to use against a
 * JWT-gated {@code RestFactory} route (e.g. {@code Authorization: Bearer <jwt>}, or the {@code
 * ?token=<jwt>} query-parameter fallback for a plain browser address bar - see {@code
 * DefaultRestFactory#resolveBearerToken}) - reuse {@link LoginSample} later to obtain a fresh
 * token for the same account without creating it again.
 */
public final class CreateUserCli {

    /**
     * Reads a password from the real console, then registers and immediately logs in a new
     * {@link de.lino.cloud.api.jwt.user.AuthUser}, printing the resulting JWT.
     *
     * @param args exactly one argument, the new account's email address
     * @throws IOException if reading local database/configuration files during {@link
     *     CloudBootstrap#initiateCloudDriver()} fails
     */
    public static void main(final String[] args) throws IOException {

        if (args.length != 1) {
            System.err.println("Usage: java -cp cloud-driver-bootstrap-*.jar de.lino.cloud.bootstrap.CreateUserCli <email>");
            return;
        }

        final String emailAddress = args[0];

        final Console console = System.console();
        if (console == null) {
            System.err.println("@CreateUserCli.main: no real console available - run this from an actual terminal "
                    + "(not an IDE's Run tool window, not a piped/non-interactive process).");
            return;
        }

        final char[] password = console.readPassword("Password for %s: ", emailAddress);
        try {
            final CloudDriver cloudDriver = CloudBootstrap.initiateCloudDriver().orElseThrow();
            final DataFactory dataFactory = cloudDriver.getFactoryContainer().getDataFactory();

            final String signingKey = cloudDriver.getConfiguration().getString("jwt-signing-key");
            final PasswordHasher passwordHasher = new Argon2idPasswordHasher();
            final JwtSigner jwtSigner = new JjwtSigner(signingKey);
            final AuthService authService = new AuthService(dataFactory, passwordHasher, jwtSigner);
            
            authService.register(emailAddress, password);
            System.out.println("Created AuthUser: " + emailAddress);

            final String token = authService.login(emailAddress, password);
            System.out.println("JWT: " + token);

        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@CreateUserCli.main: failed to create account for " + emailAddress, e);
        } catch (final InvalidCredentialsException e) {
            System.err.println("@CreateUserCli.main: " + e.getMessage());
        } finally {
            Arrays.fill(password, '\0');
        }
    }

}
