package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.password.PasswordHasher;
import lombok.NonNull;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.time.Duration;
import java.util.Hashtable;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AuthService implements IAuthService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final long ACCESS_TOKEN_TTL_SECONDS = Duration.ofHours(12).getSeconds(); // 12h

    private final DataFactory dataFactory;
    private final PasswordHasher hasher;
    private final JwtSigner signer;

    public AuthService(@NonNull final DataFactory dataFactory, @NonNull final PasswordHasher hasher, @NonNull final JwtSigner signer) {
        this.dataFactory = dataFactory;
        this.hasher = hasher;
        this.signer = signer;
    }

    @Override
    public void register(@NonNull final String emailAddress, final char @NonNull [] rawPassword) throws DatabaseClientException, KeyWrapException {

        if (!EMAIL_PATTERN.matcher(emailAddress).matches())
            throw new InvalidCredentialsException("Invalid email address: " + emailAddress);

        final String domain = emailAddress.substring(emailAddress.indexOf('@') + 1);
        if (!domainHasMxRecord(domain))
            throw new InvalidCredentialsException("Email domain cannot receive mail (no MX record): " + domain);

        final AuthUser user = new AuthUser(UUID.randomUUID().toString(), emailAddress, this.hasher.hash(rawPassword));
        this.dataFactory.register(user);
    }

    /**
     * Reports whether {@code domain} has at least one MX (mail exchange) DNS record - a
     * lightweight deliverability check that catches an obviously-fake/typo'd domain (e.g.
     * {@code @gmial.com}) without sending any mail. Does <b>not</b> prove the specific mailbox
     * exists - only actually sending a confirmation mail and having the recipient act on it
     * (double opt-in) proves that, which this deliberately doesn't do.
     */
    private static boolean domainHasMxRecord(final String domain) {
        final Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        try {
            final Attribute mxRecords = new InitialDirContext(env).getAttributes(domain, new String[]{"MX"}).get("MX");
            return mxRecords != null && mxRecords.size() > 0;
        } catch (final NamingException e) {
            return false;
        }
    }

    @NonNull
    @Override
    public String login(@NonNull final String emailAddress, final char @NonNull [] rawPassword) {

        final AuthUser user;
        try {
            user = this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(candidate -> candidate.getEmailAddress().equals(emailAddress))
                    .findFirst()
                    .orElseThrow(() -> new InvalidCredentialsException("invalid credentials"));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.login: failed to look up user '" + emailAddress + "'", e);
        }

        if (!this.hasher.verify(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException("invalid credentials");
        }

        return this.signer.sign(user.getId(), ACCESS_TOKEN_TTL_SECONDS);
    }

    @NonNull
    @Override
    public String validate(@NonNull final String jwt) throws InvalidJwtException {
        return this.signer.verify(jwt);
    }

}
