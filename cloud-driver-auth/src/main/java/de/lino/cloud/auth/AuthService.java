package de.lino.cloud.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.pending.PendingEmailChange;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingRegistration;
import lombok.NonNull;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
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

    /** How long a JWT issued by {@link #login}/{@link #confirmRegistration} remains valid: 12 hours. */
    private static final long ACCESS_TOKEN_TTL_SECONDS = Duration.ofHours(12).getSeconds(); // 12h

    /** How long a verification code issued by {@link #register} remains valid: 10 minutes. */
    private static final long VERIFICATION_CODE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    /** How long a verification code issued by {@link #requestPasswordReset} remains valid: 10 minutes. */
    private static final long PASSWORD_RESET_CODE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    /** How long a verification code issued by {@link #requestEmailChange} remains valid: 10 minutes. */
    private static final long EMAIL_CHANGE_CODE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    /** Source of randomness for {@link #generateVerificationCode()}. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Persists/looks up {@link AuthUser}/{@link PendingRegistration} rows. */
    private final DataFactory dataFactory;

    /** Hashes a new password and verifies a login candidate against a stored hash. */
    private final PasswordHasher hasher;

    /** Issues and verifies the JWTs returned by {@link #login}/{@link #confirmRegistration}/{@link #validate}. */
    private final JwtSigner signer;

    /** Delivers the verification code {@link #register} generates. */
    private final EmailSender emailSender;

    /**
     * Creates/looks up the {@link de.lino.cloud.auth.entity.CloudUser} row for a newly-confirmed
     * {@link AuthUser} - see {@link #confirmRegistration}'s Javadoc for why this call exists.
     */
    private final ICloudUserService cloudUserService;

    /**
     * Creates an {@code AuthService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link AuthUser}/{@link PendingRegistration} rows
     * @param hasher hashes a new password and verifies a login candidate against a stored hash
     * @param signer issues and verifies the JWTs returned by {@link #login}/{@link #confirmRegistration}/{@link #validate}
     * @param emailSender delivers the verification code {@link #register} generates
     * @param cloudUserService creates/looks up the {@link de.lino.cloud.auth.entity.CloudUser} row {@link #confirmRegistration} eagerly creates for a newly-confirmed account
     */
    public AuthService(@NonNull final DataFactory dataFactory, @NonNull final PasswordHasher hasher,
                        @NonNull final JwtSigner signer, @NonNull final EmailSender emailSender,
                        @NonNull final ICloudUserService cloudUserService) {
        this.dataFactory = dataFactory;
        this.hasher = hasher;
        this.signer = signer;
        this.emailSender = emailSender;
        this.cloudUserService = cloudUserService;
    }

    /**
     * Starts registration under {@code emailAddress}, after checking that it looks like a real,
     * deliverable address (syntax via {@link #EMAIL_PATTERN}, then a live MX-record lookup via
     * {@link #domainHasMxRecord}) and that no {@link AuthUser} already exists under it - see
     * {@link IAuthService#register}'s Javadoc for how/whether this is exposed over HTTP; this
     * method itself has no opinion on that. Does <b>not</b> create the {@link AuthUser} yet -
     * persists a {@link PendingRegistration} (hashed password + a freshly generated numeric
     * code, valid for {@link #VERIFICATION_CODE_TTL_MILLIS}) and e-mails that code to {@code
     * emailAddress}; {@link #confirmRegistration} is what actually creates the account.
     *
     * <p>The duplicate check exists because {@code emailAddress} is not {@link AuthUser}'s
     * primary key (see {@link #login}'s own Javadoc on why): without it, two accounts could
     * exist under the same email with different generated ids, and {@link #login}'s {@code
     * findFirst()} lookup would then match whichever one happens to come first -
     * non-deterministically, from a caller's perspective. Not a race-proof check (a concurrent
     * double-submit could still slip both past this read before either write lands), but
     * sufficient for the normal, sequential case a self-service register form produces. A
     * repeated {@link #register} call for the same address before it's confirmed is not treated
     * as a duplicate - {@link PendingRegistration#keysOf()} is keyed on {@code emailAddress}
     * itself, so it simply overwrites the previous attempt with a fresh code/expiry.
     *
     * @param emailAddress the new account's email address, also its login identifier
     * @param rawPassword the chosen password; hashed via {@link PasswordHasher#hash} before
     *     persistence, never stored or retained in plain form
     * @throws InvalidCredentialsException if {@code emailAddress} fails the syntax check or its
     *     domain has no MX record
     * @throws EmailAlreadyRegisteredException if an {@link AuthUser} already exists under {@code emailAddress}
     * @throws DatabaseClientException if persisting the pending registration fails
     * @throws KeyWrapException if the pending registration's data-encryption key cannot be wrapped by the KMS/HSM
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

        final String verificationCode = generateVerificationCode();
        final long expiresAt = System.currentTimeMillis() + VERIFICATION_CODE_TTL_MILLIS;
        final PendingRegistration pending = new PendingRegistration(emailAddress, this.hasher.hash(rawPassword), verificationCode, expiresAt);
        this.dataFactory.register(pending);

        try {

            final String[] emailMessage = new String[] {
                    String.format("Hello %s,", emailAddress)
                    , ""
                    , "We have noticed that you wanted to register a new cloud user account."
                    , "To verify your registration, enter the following account."
                    , ""
                    , String.format("Your verification code is '%s'", verificationCode)
                    , ""
                    , "If you have not tried to register a new account, just ignore this email."
                    , "The register attempt will be deleted within 10 minutes when the account will not be confirmed."
            };

            this.emailSender.send(emailAddress, "Registration confirmation", String.join("\n", emailMessage));

        } catch (final EmailDeliveryException e) {
            throw new RuntimeException("@AuthService.register: failed to send verification email to " + emailAddress, e);
        }

    }

    /** @return a fresh, zero-padded 6-digit numeric code, e.g. {@code "042917"} */
    private static String generateVerificationCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /**
     * Completes a registration previously started by {@link #register}: looks up the {@link
     * PendingRegistration} stored under {@code emailAddress}, rejects it (via {@link
     * InvalidVerificationCodeException}, the same message either way, matching {@link
     * #login}'s "don't leak" idiom) if it doesn't exist, has expired, or {@code code} doesn't
     * match its {@link PendingRegistration#getVerificationCode()} - an expired row is deleted
     * as part of that rejection, rather than left to be overwritten by a later {@link
     * #register} call. On success, creates the real {@link AuthUser} from the pending row's
     * already-hashed password, deletes the pending row, eagerly creates that account's {@link
     * de.lino.cloud.auth.entity.CloudUser} row via {@link ICloudUserService#getOrCreate} (rather
     * than leaving it to be lazily created on the account's first upload/folder-create - see
     * {@link ICloudUserService#getOrCreate}'s own Javadoc - a freshly-registered account used to
     * be invisible to {@code stats}/{@code cu list} in the terminal package until it uploaded
     * something, which read as a bug rather than the intended lazy-creation behavior), and
     * returns a signed JWT the same way {@link #login} does.
     *
     * @param emailAddress the e-mail address {@link #register} was called with
     * @param code the verification code e-mailed to {@code emailAddress}
     * @return a freshly signed JWT asserting the newly created {@link AuthUser#getId()}
     * @throws InvalidVerificationCodeException if there is no pending registration under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match
     * @throws DatabaseClientException if creating the account fails
     * @throws KeyWrapException if the new account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    @Override
    public String confirmRegistration(@NonNull final String emailAddress, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingRegistration> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(emailAddress, PendingRegistration.class);
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmRegistration: failed to look up pending registration for " + emailAddress, e);
        }

        final PendingRegistration pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(emailAddress, PendingRegistration.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!pending.getVerificationCode().equals(code)) {
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser user = new AuthUser(UUID.randomUUID().toString(), emailAddress, pending.getPasswordHash());
        this.dataFactory.register(user);
        this.dataFactory.delete(emailAddress, PendingRegistration.class);
        this.cloudUserService.getOrCreate(user.getId());

        return this.signer.sign(user.getId(), ACCESS_TOKEN_TTL_SECONDS);
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

    /**
     * Lists every currently-registered {@link AuthUser}.
     *
     * @return every currently-registered {@link AuthUser}
     */
    @NonNull
    @Override
    public List<AuthUser> getAuthUsers() {
        try {
            return List.copyOf(this.dataFactory.getEntities(AuthUser.class));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.getAuthUsers: failed to list AuthUser records", e);
        }
    }

    /**
     * Looks up a single {@link AuthUser} by its plain id - a direct, O(1) {@link
     * DataFactory#findById} point lookup (unlike {@link #login}, which has to scan every {@link
     * AuthUser} since {@code emailAddress}, not {@code id}, is the lookup key there), since {@code
     * authUserId} already <em>is</em> {@link AuthUser}'s primary key.
     *
     * @param authUserId the {@link AuthUser#getId()} to look up
     * @return the matching {@link AuthUser}, or {@link Optional#empty()} if no account exists under that id
     */
    @NonNull
    @Override
    public Optional<AuthUser> getAuthUser(@NonNull final String authUserId) {
        try {
            return this.dataFactory.findById(authUserId, AuthUser.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.getAuthUser: failed to look up AuthUser " + authUserId, e);
        }
    }

    /**
     * Starts a password reset under {@code emailAddress}: looks up the matching {@link AuthUser}
     * the same way {@link #login} does, and - only if one exists - persists a {@link
     * PendingPasswordReset} (a freshly generated code, valid for {@link
     * #PASSWORD_RESET_CODE_TTL_MILLIS}) and e-mails it. Returns identically whether or not an
     * account exists under {@code emailAddress} - unlike {@link #register}'s deliberately
     * leaky {@link EmailAlreadyRegisteredException}, confirming account existence here would let
     * a caller enumerate valid accounts to target for credential stuffing, so this method never
     * distinguishes the two cases from the outside.
     *
     * @param emailAddress the account's identifying e-mail address
     * @throws DatabaseClientException if persisting the pending reset fails
     * @throws KeyWrapException if the pending reset's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void requestPasswordReset(@NonNull final String emailAddress) throws DatabaseClientException, KeyWrapException {

        final boolean accountExists;
        try {
            accountExists = this.dataFactory.getEntities(AuthUser.class).stream()
                    .anyMatch(candidate -> candidate.getEmailAddress().equals(emailAddress));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.requestPasswordReset: failed to look up account for " + emailAddress, e);
        }

        if (!accountExists) {
            return;
        }

        final String verificationCode = generateVerificationCode();
        final long expiresAt = System.currentTimeMillis() + PASSWORD_RESET_CODE_TTL_MILLIS;
        final PendingPasswordReset pending = new PendingPasswordReset(emailAddress, verificationCode, expiresAt);
        this.dataFactory.register(pending);

        try {

            final String[] emailMessage = new String[] {
                    String.format("Hello %s,", emailAddress)
                    , ""
                    , "We have received a request to reset the password for this account."
                    , ""
                    , String.format("Your password reset code is '%s'", verificationCode)
                    , ""
                    , "If you did not request a password reset, just ignore this email - your password will not change."
                    , "This code will expire within 10 minutes."
            };

            this.emailSender.send(emailAddress, "Password reset", String.join("\n", emailMessage));

        } catch (final EmailDeliveryException e) {
            throw new RuntimeException("@AuthService.requestPasswordReset: failed to send reset email to " + emailAddress, e);
        }

    }

    /**
     * Completes a password reset previously started by {@link #requestPasswordReset}: looks up
     * the {@link PendingPasswordReset} stored under {@code emailAddress}, rejects it (via {@link
     * InvalidVerificationCodeException}, the same message either way, matching {@link
     * #confirmRegistration}'s idiom) if it doesn't exist, has expired, or {@code code} doesn't
     * match its {@link PendingPasswordReset#getVerificationCode()} - an expired row is deleted as
     * part of that rejection. On success, re-hashes {@code newPassword} onto the matching {@link
     * AuthUser} (looked up by email, same scan {@link #login} uses), deletes the pending row, and
     * returns a signed JWT the same way {@link #confirmRegistration} does.
     *
     * @param emailAddress the e-mail address {@link #requestPasswordReset} was called with
     * @param code the verification code e-mailed to {@code emailAddress}
     * @param newPassword the caller's chosen new password, hashed via {@link PasswordHasher#hash}
     *     before persistence, never stored or retained in plain form
     * @return a freshly signed JWT asserting the matched {@link AuthUser#getId()}
     * @throws InvalidVerificationCodeException if there is no pending reset under {@code
     *     emailAddress}, it has expired, {@code code} doesn't match, or (defense-in-depth) the
     *     account itself no longer exists
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    @Override
    public String confirmPasswordReset(@NonNull final String emailAddress, @NonNull final String code, final char @NonNull [] newPassword)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingPasswordReset> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(emailAddress, PendingPasswordReset.class);
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmPasswordReset: failed to look up pending reset for " + emailAddress, e);
        }

        final PendingPasswordReset pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(emailAddress, PendingPasswordReset.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!pending.getVerificationCode().equals(code)) {
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser existing;
        try {
            existing = this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(candidate -> candidate.getEmailAddress().equals(emailAddress))
                    .findFirst()
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmPasswordReset: failed to look up account for " + emailAddress, e);
        }

        final AuthUser updated = new AuthUser(existing.getId(), existing.getEmailAddress(), this.hasher.hash(newPassword));
        this.dataFactory.update(updated);
        this.dataFactory.delete(emailAddress, PendingPasswordReset.class);

        return this.signer.sign(updated.getId(), ACCESS_TOKEN_TTL_SECONDS);
    }

    /**
     * Starts an e-mail change for {@code authUserId}: validates {@code newEmailAddress} the same
     * way {@link #register} validates a fresh address (syntax, then a live MX-record lookup), and
     * - unlike {@link #requestPasswordReset}'s deliberately leaky-nothing contract - confirms
     * whether it's already taken by throwing {@link EmailAlreadyRegisteredException}, since {@code
     * authUserId} is already an authenticated account holder here, not an anonymous caller this
     * could hand a login-enumeration oracle to. Does <b>not</b> change the account's address yet -
     * persists a {@link PendingEmailChange} (fresh code, valid for {@link
     * #EMAIL_CHANGE_CODE_TTL_MILLIS}) and e-mails it to {@code newEmailAddress}; {@link
     * #confirmEmailChange} is what actually applies the change.
     *
     * @param authUserId the already-authenticated account requesting the change
     * @param newEmailAddress the address this account would move to on confirmation
     * @throws InvalidCredentialsException if {@code newEmailAddress} fails the syntax check or its domain has no MX record
     * @throws EmailAlreadyRegisteredException if another account already exists under {@code newEmailAddress}
     * @throws DatabaseClientException if persisting the pending change fails
     * @throws KeyWrapException if the pending change's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void requestEmailChange(@NonNull final String authUserId, @NonNull final String newEmailAddress)
            throws DatabaseClientException, KeyWrapException {

        if (!EMAIL_PATTERN.matcher(newEmailAddress).matches())
            throw new InvalidCredentialsException("Invalid email address: " + newEmailAddress);

        final String domain = newEmailAddress.substring(newEmailAddress.indexOf('@') + 1);
        if (!domainHasMxRecord(domain))
            throw new InvalidCredentialsException("Email domain cannot receive mail (no MX record): " + domain);

        final boolean alreadyRegistered;
        try {
            alreadyRegistered = this.dataFactory.getEntities(AuthUser.class).stream()
                    .anyMatch(candidate -> candidate.getEmailAddress().equals(newEmailAddress));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.requestEmailChange: failed to check for an existing account under " + newEmailAddress, e);
        }
        if (alreadyRegistered) {
            throw new EmailAlreadyRegisteredException(newEmailAddress);
        }

        final String verificationCode = generateVerificationCode();
        final long expiresAt = System.currentTimeMillis() + EMAIL_CHANGE_CODE_TTL_MILLIS;
        final PendingEmailChange pending = new PendingEmailChange(authUserId, newEmailAddress, verificationCode, expiresAt);
        this.dataFactory.register(pending);

        try {

            final String[] emailMessage = new String[] {
                    String.format("Hello %s,", newEmailAddress)
                    , ""
                    , "We have received a request to change the e-mail address for this account to this one."
                    , ""
                    , String.format("Your verification code is '%s'", verificationCode)
                    , ""
                    , "If you did not request this change, just ignore this email - your account's e-mail address will not change."
                    , "This code will expire within 10 minutes."
            };

            this.emailSender.send(newEmailAddress, "Confirm your new e-mail address", String.join("\n", emailMessage));

        } catch (final EmailDeliveryException e) {
            throw new RuntimeException("@AuthService.requestEmailChange: failed to send verification email to " + newEmailAddress, e);
        }

    }

    /**
     * Completes an e-mail change previously started by {@link #requestEmailChange}: looks up the
     * {@link PendingEmailChange} stored under {@code authUserId}, rejects it (via {@link
     * InvalidVerificationCodeException}, the same message either way, matching {@link
     * #confirmRegistration}'s idiom) if it doesn't exist, has expired, or {@code code} doesn't
     * match its {@link PendingEmailChange#getVerificationCode()} - an expired row is deleted as
     * part of that rejection. On success, replaces the matching {@link AuthUser}'s address with
     * the pending change's {@link PendingEmailChange#getNewEmailAddress()} and deletes the pending
     * row. Returns nothing to sign - a JWT's subject is the account's id, never its e-mail
     * address, so the caller's existing token remains valid across this change.
     *
     * @param authUserId the already-authenticated account confirming the change
     * @param code the verification code e-mailed to the pending change's new address
     * @throws InvalidVerificationCodeException if there is no pending change under {@code
     *     authUserId}, it has expired, {@code code} doesn't match, or (defense-in-depth) the
     *     account itself no longer exists
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void confirmEmailChange(@NonNull final String authUserId, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingEmailChange> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(authUserId, PendingEmailChange.class);
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmEmailChange: failed to look up pending change for " + authUserId, e);
        }

        final PendingEmailChange pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(authUserId, PendingEmailChange.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!pending.getVerificationCode().equals(code)) {
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmEmailChange: failed to look up account " + authUserId, e);
        }

        final AuthUser updated = new AuthUser(existing.getId(), pending.getNewEmailAddress(), existing.getPasswordHash());
        this.dataFactory.update(updated);
        this.dataFactory.delete(authUserId, PendingEmailChange.class);
    }

}
