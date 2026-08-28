package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
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

/**
 * Default {@link IAuthService} implementation: verifies end-user login (email address +
 * password) against {@link AuthUser} entities persisted through a {@link DataFactory}, and
 * issues/validates the JWTs that authenticate every subsequent request from that client.
 *
 * <p>Framework-agnostic on purpose - this class has no Javalin dependency of its own, so it
 * throws plain {@link InvalidCredentialsException}/{@link InvalidJwtException} rather than an
 * HTTP-specific type; a caller wiring this into an HTTP layer (e.g. {@code
 * cloud-driver-plugin}'s {@code DefaultRestFactory}) translates those into the appropriate
 * response itself. Every field is immutable and assigned once at construction, so a single
 * instance is safe to share across concurrent callers.
 */
public final class AuthService implements IAuthService {

    /** RFC-5322-ish email syntax check - deliberately not exhaustive, just enough to reject an obvious typo. */
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    /** How long a JWT issued by {@link #login} remains valid: 12 hours. */
    private static final long ACCESS_TOKEN_TTL_SECONDS = Duration.ofHours(12).getSeconds(); // 12h

    private final DataFactory dataFactory;
    private final PasswordHasher hasher;
    private final JwtSigner signer;

    /**
     * Creates an {@code AuthService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link AuthUser} accounts
     * @param hasher hashes a new password and verifies a login candidate against a stored hash
     * @param signer issues and verifies the JWTs returned by {@link #login}/{@link #validate}
     */
    public AuthService(@NonNull final DataFactory dataFactory, @NonNull final PasswordHasher hasher, @NonNull final JwtSigner signer) {
        this.dataFactory = dataFactory;
        this.hasher = hasher;
        this.signer = signer;
    }

    /**
     * Creates and persists a new {@link AuthUser} account under {@code emailAddress}, after
     * checking that it looks like a real, deliverable address (syntax via {@link
     * #EMAIL_PATTERN}, then a live MX-record lookup via {@link #domainHasMxRecord}) and that no
     * account already exists under it - see {@link IAuthService#register}'s Javadoc for how/
     * whether new accounts are exposed over HTTP; this method itself has no opinion on that.
     *
     * <p>The duplicate check exists because {@code emailAddress} is not this entity's primary
     * key (see {@link #login}'s own Javadoc on why): without it, two accounts could exist under
     * the same email with different generated ids, and {@link #login}'s {@code findFirst()}
     * lookup would then match whichever one happens to come first - non-deterministically, from
     * a caller's perspective. Not a race-proof check (a concurrent double-submit could still
     * slip both past this read before either write lands), but sufficient for the normal,
     * sequential case a self-service register form produces.
     *
     * @param emailAddress the new account's email address, also its login identifier
     * @param rawPassword the chosen password; hashed via {@link PasswordHasher#hash} before
     *     persistence, never stored or retained in plain form
     * @throws InvalidCredentialsException if {@code emailAddress} fails the syntax check or its
     *     domain has no MX record
     * @throws EmailAlreadyRegisteredException if an {@link AuthUser} already exists under {@code emailAddress}
     * @throws DatabaseClientException if persisting the new account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void register(@NonNull final String emailAddress, final char @NonNull [] rawPassword) throws DatabaseClientException, KeyWrapException {

        if (!EMAIL_PATTERN.matcher(emailAddress).matches())
            throw new InvalidCredentialsException("Invalid email address: " + emailAddress);

        final String domain = emailAddress.substring(emailAddress.indexOf('@') + 1);
        if (!domainHasMxRecord(domain))
            throw new InvalidCredentialsException("Email domain cannot receive mail (no MX record): " + domain);

        final boolean alreadyRegistered;
        try {
            alreadyRegistered = this.dataFactory.getEntities(AuthUser.class).stream()
                    .anyMatch(candidate -> candidate.getEmailAddress().equals(emailAddress));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.register: failed to check for an existing account under " + emailAddress, e);
        }
        if (alreadyRegistered) {
            throw new EmailAlreadyRegisteredException(emailAddress);
        }

        final AuthUser user = new AuthUser(UUID.randomUUID().toString(), emailAddress, this.hasher.hash(rawPassword));
        this.dataFactory.register(user);
    }

    /**
     * Reports whether {@code domain} has at least one MX (mail exchange) DNS record.
     *
     * <p>A lightweight deliverability check that catches an obviously-fake/typo'd domain (e.g.
     * {@code @gmial.com}) without sending any mail. Does <b>not</b> prove the specific mailbox
     * exists - only actually sending a confirmation mail and having the recipient act on it
     * (double opt-in) proves that, which this deliberately doesn't do. Performs a blocking DNS
     * lookup with no explicit timeout configured on the underlying {@link InitialDirContext} -
     * see the findings noted alongside this module's README for the implications of that.
     *
     * @param domain the domain part of the candidate email address (after the {@code @})
     * @return {@code true} if {@code domain} has at least one MX record, {@code false} if it
     *     has none or the lookup itself fails
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

    /**
     * Verifies {@code emailAddress}/{@code rawPassword} against the matching {@link AuthUser},
     * returning a signed JWT (valid for {@link #ACCESS_TOKEN_TTL_SECONDS}) on success.
     *
     * <p>Looks the account up by scanning every {@link AuthUser} via {@link
     * DataFactory#getEntities} rather than a direct keyed lookup, since {@code emailAddress} is
     * not this entity's primary key - see the findings noted alongside this module's README for
     * the scalability implication of that on the login hot path. Deliberately throws the same
     * {@link InvalidCredentialsException} message whether the account doesn't exist or the
     * password doesn't match, so a caller can never use this to enumerate valid email addresses.
     *
     * @param emailAddress the login identifier to look up
     * @param rawPassword the candidate password, verified via {@link PasswordHasher#verify}
     * @return a signed JWT asserting the matched {@link AuthUser#getId()}
     * @throws InvalidCredentialsException if no account matches {@code emailAddress}, or the
     *     password doesn't match
     */
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

    /**
     * Validates a JWT previously issued by {@link #login}, returning the embedded user id.
     *
     * @param jwt the token to validate, as received in an {@code Authorization: Bearer} header
     * @return the {@link AuthUser#getId()} embedded in {@code jwt}
     * @throws InvalidJwtException if the token's signature is invalid, it is malformed, or it has expired
     */
    @NonNull
    @Override
    public String validate(@NonNull final String jwt) throws InvalidJwtException {
        return this.signer.verify(jwt);
    }

}
