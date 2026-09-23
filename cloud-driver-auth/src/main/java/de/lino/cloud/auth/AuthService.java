package de.lino.cloud.auth;

import de.lino.cloud.api.CloudDriver;
import de.lino.cloud.api.audit.AuditAction;
import de.lino.cloud.api.audit.AuditEvent;
import de.lino.cloud.api.audit.AuditLogService;
import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidPasswordFormatException;
import de.lino.cloud.api.jwt.InvalidRefreshTokenException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.JwtSigner;
import de.lino.cloud.api.jwt.VerifiedAccessToken;
import de.lino.cloud.api.jwt.auth.AuthTokens;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.hash.LookupKeyDigest;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.RefreshToken;
import de.lino.cloud.auth.mail.EmailTemplates;
import de.lino.cloud.auth.pending.PendingEmailChange;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingRegistration;
import lombok.NonNull;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
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

    /** The minimum length {@link #requirePasswordFormat} accepts for a caller-chosen password. */
    private static final int MIN_PASSWORD_LENGTH = 8;

    /**
     * Characters a caller-chosen password may never contain, checked by {@link
     * #requirePasswordFormat} - not delimiters this class itself uses internally, but excluded
     * defensively since a password containing one could collide with a delimiter/quoting
     * convention elsewhere in the system (CSV/log exports, the terminal package's
     * whitespace-split command parsing, etc.).
     */
    private static final String FORBIDDEN_PASSWORD_CHARACTERS = ";,:`";

    /**
     * The symbol characters {@link #requirePasswordFormat} accepts as satisfying its "at least
     * one symbol" requirement - ordinary printable ASCII punctuation, deliberately excluding
     * every character in {@link #FORBIDDEN_PASSWORD_CHARACTERS}.
     */
    private static final String ALLOWED_PASSWORD_SYMBOLS = "!\"#$%&'()*+-./<=>?@[\\]^_{|}~";

    /** How long a JWT issued by {@link #login}/{@link #confirmRegistration} remains valid: 12 hours. */
    private static final long ACCESS_TOKEN_TTL_SECONDS = Duration.ofHours(12).getSeconds(); // 12h

    /**
     * How long a {@link RefreshToken} issued by {@link #issueTokens} remains valid before {@link
     * #refresh} rejects it outright: 30 days. Deliberately much longer than {@link
     * #ACCESS_TOKEN_TTL_SECONDS} - the whole point of a refresh token is to let a long-running
     * client (e.g. a desktop app) stay signed in across many access-token expiries without a
     * fresh password login; 30 days comfortably covers a client used at least monthly while still
     * bounding how long a stolen-but-unused refresh token remains exploitable.
     */
    private static final long REFRESH_TOKEN_TTL_MILLIS = Duration.ofDays(30).toMillis();

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
     * Records security-relevant actions (login success/failure, registration, password reset,
     * e-mail change) to the persisted audit trail - see {@code AuditLogService}'s own Javadoc.
     * Never throws, so every call site below invokes it directly with no defensive try/catch of
     * its own.
     */
    private final AuditLogService auditLogService;

    /**
     * Creates an {@code AuthService} backed by the given collaborators.
     *
     * @param dataFactory persists/looks up {@link AuthUser}/{@link PendingRegistration} rows
     * @param hasher hashes a new password and verifies a login candidate against a stored hash
     * @param signer issues and verifies the JWTs returned by {@link #login}/{@link #confirmRegistration}/{@link #validate}
     * @param emailSender delivers the verification code {@link #register} generates
     * @param cloudUserService creates/looks up the {@link de.lino.cloud.auth.entity.CloudUser} row {@link #confirmRegistration} eagerly creates for a newly-confirmed account
     * @param auditLogService records this class's security-relevant actions to the persisted audit trail
     */
    public AuthService(@NonNull final DataFactory dataFactory, @NonNull final PasswordHasher hasher,
                        @NonNull final JwtSigner signer, @NonNull final EmailSender emailSender,
                        @NonNull final ICloudUserService cloudUserService, @NonNull final AuditLogService auditLogService) {
        this.dataFactory = dataFactory;
        this.hasher = hasher;
        this.signer = signer;
        this.emailSender = emailSender;
        this.cloudUserService = cloudUserService;
        this.auditLogService = auditLogService;
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
     * as a duplicate - {@link PendingRegistration#keysOf()} is keyed on the digest of {@code
     * emailAddress}, which is still one key per address, so it simply overwrites the previous
     * attempt with a fresh code/expiry.
     *
     * @param emailAddress the new account's email address, also its login identifier
     * @param rawPassword the chosen password; hashed via {@link PasswordHasher#hash} before
     *     persistence, never stored or retained in plain form
     * @throws InvalidPasswordFormatException if {@code rawPassword} doesn't meet {@link
     *     #requirePasswordFormat}'s requirement - checked first, before any DNS/database access,
     *     since it's the cheapest of this method's checks and needs neither
     * @throws InvalidCredentialsException if {@code emailAddress} fails the syntax check or its
     *     domain has no MX record
     * @throws EmailAlreadyRegisteredException if an {@link AuthUser} already exists under {@code emailAddress}
     * @throws DatabaseClientException if persisting the pending registration fails
     * @throws KeyWrapException if the pending registration's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void register(@NonNull final String emailAddress, final char @NonNull [] rawPassword) throws DatabaseClientException, KeyWrapException {

        requirePasswordFormat(rawPassword);

        if (!EMAIL_PATTERN.matcher(emailAddress).matches())
            throw new InvalidCredentialsException("Invalid email address: " + emailAddress);

        final String domain = emailAddress.substring(emailAddress.indexOf('@') + 1);
        if (!domainHasMxRecord(domain))
            throw new InvalidCredentialsException("Email domain cannot receive mail (no MX record): " + domain);

        final boolean alreadyRegistered;
        try {
            alreadyRegistered = this.dataFactory.getEntitiesByIndex(AuthUser.class, AuthUser.INDEX_EMAIL_ADDRESS, emailAddress).stream()
                    .findAny().isPresent();
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
            this.sendVerificationEmail(
                    emailAddress,
                    "Confirm your registration",
                    "Confirm your registration.",
                    "We noticed you wanted to register a new Cloud Driver account. Enter the code below to verify your registration.",
                    verificationCode,
                    "If you did not try to register an account, just ignore this e-mail - the registration attempt will be deleted within 10 minutes if it isn't confirmed."
            );
        } catch (final EmailDeliveryException e) {
            throw new RuntimeException("@AuthService.register: failed to send verification email to " + emailAddress, e);
        }

        this.auditLogService.record(new AuditEvent(null, AuditAction.REGISTER, emailAddress, null));
    }

    /**
     * Generates a fresh verification code for use by {@link #register}, {@link
     * #requestPasswordReset}, or {@link #requestEmailChange}.
     *
     * @return a fresh, zero-padded 6-digit numeric code, e.g. {@code "042917"}
     */
    private static String generateVerificationCode() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

    /**
     * Builds (via {@link EmailTemplates}) and sends a verification e-mail to {@code toAddress} -
     * the shared send path for {@link #register}/{@link #requestPasswordReset}/{@link
     * #requestEmailChange}, each supplying its own headline/intro/notice text.
     *
     * @param toAddress the recipient
     * @param subject the e-mail subject
     * @param headline the large heading {@link EmailTemplates#buildVerificationHtml} renders
     * @param introText the paragraph explaining why this e-mail was sent
     * @param code the verification code
     * @param noticeText the closing paragraph (expiry/"ignore this if it wasn't you")
     * @throws EmailDeliveryException if delivering to {@code toAddress} fails
     */
    private void sendVerificationEmail(final String toAddress, final String subject, final String headline,
                                        final String introText, final String code, final String noticeText)
            throws EmailDeliveryException {
        final String html = EmailTemplates.buildVerificationHtml(headline, toAddress, introText, code, noticeText);
        final String plainText = EmailTemplates.buildVerificationPlainText(toAddress, introText, code, noticeText);

        this.emailSender.send(toAddress, subject, html, plainText);
    }

    /**
     * Builds (via {@link EmailTemplates}) and sends a notification e-mail to {@code toAddress} -
     * the shared send path for the messages that tell an address something has already happened,
     * rather than asking it to prove it controls anything. No verification code, so no code
     * callout is rendered at all.
     *
     * @param toAddress the recipient
     * @param subject the e-mail subject
     * @param headline the large heading {@link EmailTemplates#buildNoticeHtml} renders
     * @param introText the paragraph explaining what happened
     * @param noticeText the closing paragraph (what to do if this was not the recipient)
     * @throws EmailDeliveryException if delivering to {@code toAddress} fails
     */
    private void sendNoticeEmail(final String toAddress, final String subject, final String headline,
                                  final String introText, final String noticeText)
            throws EmailDeliveryException {
        final String html = EmailTemplates.buildNoticeHtml(headline, toAddress, introText, noticeText);
        final String plainText = EmailTemplates.buildNoticePlainText(toAddress, introText, noticeText);

        this.emailSender.send(toAddress, subject, html, plainText);
    }

    /**
     * Enforces the password format rule shared by {@link #register} and {@link
     * #confirmPasswordReset}: at least {@link #MIN_PASSWORD_LENGTH} characters, containing at
     * least one digit, one lowercase letter, one uppercase letter, and one symbol (from {@link
     * #ALLOWED_PASSWORD_SYMBOLS}), and containing none of {@link #FORBIDDEN_PASSWORD_CHARACTERS}
     * anywhere.
     *
     * <p>Scans {@code rawPassword} directly, character by character, rather than first copying it
     * into a {@link String} (as a regex-based check would need to) - an immutable {@code String}
     * can never be cleared from memory the way a {@code char[]} can, so this avoids creating a
     * second, longer-lived copy of the caller's chosen password purely to validate its shape.
     *
     * @param rawPassword the candidate password to validate
     * @throws InvalidPasswordFormatException if {@code rawPassword} is too short, contains a
     *     forbidden character, or is missing one of the required character categories - the
     *     message never echoes {@code rawPassword} itself
     */
    private static void requirePasswordFormat(final char[] rawPassword) {
        if (rawPassword.length < MIN_PASSWORD_LENGTH) {
            throw new InvalidPasswordFormatException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters long");
        }

        boolean hasDigit = false;
        boolean hasLowercase = false;
        boolean hasUppercase = false;
        boolean hasSymbol = false;

        for (final char character : rawPassword) {
            if (FORBIDDEN_PASSWORD_CHARACTERS.indexOf(character) >= 0) {
                throw new InvalidPasswordFormatException("Password must not contain ';', ',', ':', or '`'");
            }
            if (Character.isDigit(character)) {
                hasDigit = true;
            } else if (Character.isLowerCase(character)) {
                hasLowercase = true;
            } else if (Character.isUpperCase(character)) {
                hasUppercase = true;
            } else if (ALLOWED_PASSWORD_SYMBOLS.indexOf(character) >= 0) {
                hasSymbol = true;
            }
        }

        if (!hasDigit || !hasLowercase || !hasUppercase || !hasSymbol) {
            throw new InvalidPasswordFormatException(
                    "Password must contain at least one number, one lowercase letter, one uppercase letter, and one symbol");
        }
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
     * @return a freshly issued {@link AuthTokens} pair asserting the newly created {@link AuthUser#getId()}
     * @throws InvalidVerificationCodeException if there is no pending registration under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match
     * @throws DatabaseClientException if creating the account fails
     * @throws KeyWrapException if the new account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    @Override
    public AuthTokens confirmRegistration(@NonNull final String emailAddress, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingRegistration> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(PendingRegistration.keyOf(emailAddress), PendingRegistration.class);
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmRegistration: failed to look up pending registration for " + emailAddress, e);
        }

        final PendingRegistration pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(PendingRegistration.keyOf(emailAddress), PendingRegistration.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!verificationCodeMatches(pending.getVerificationCode(), code)) {
            // Count the miss, and burn the row once it has been guessed at enough times. Without
            // this a wrong guess costs nothing and the code stays alive for its full lifetime,
            // which a six-digit space cannot survive once the request rate limiter is out of the
            // way. The same exception either way, so an attacker cannot tell a wrong code from an
            // exhausted one.
            registerFailedVerificationAttempt(pending.withFailedAttempt(), PendingRegistration.keyOf(emailAddress), PendingRegistration.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser user = new AuthUser(UUID.randomUUID().toString(), emailAddress, pending.getPasswordHash());
        this.dataFactory.register(user);
        this.dataFactory.delete(PendingRegistration.keyOf(emailAddress), PendingRegistration.class);
        this.cloudUserService.getOrCreate(user.getId());

        return this.issueTokens(user.getId());
    }

    /**
     * Signs a fresh access JWT and mints/persists a fresh {@link RefreshToken} for {@code
     * authUserId}, bundling both into the {@link AuthTokens} pair {@link #login}/{@link
     * #confirmRegistration}/{@link #confirmPasswordReset}/{@link #refresh} all return. The one
     * place any of those four methods actually issues tokens, so the pairing is never built
     * inconsistently between them.
     *
     * <p>The account's session generation is read here rather than taken from the caller, so the
     * pair is always signed with whatever is persisted at that instant. That is what removes the
     * ordering hazard around {@link #endAllSessions}: a caller that ends the sessions and then
     * issues a fresh pair cannot accidentally sign the pair at the generation it just retired.
     *
     * @param authUserId the account id to issue tokens for
     * @return a freshly issued {@link AuthTokens} pair
     * @throws IllegalStateException if no account exists under {@code authUserId}
     * @throws DatabaseClientException if persisting the new {@link RefreshToken} fails
     * @throws KeyWrapException if the new {@link RefreshToken}'s data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    private AuthTokens issueTokens(@NonNull final String authUserId) throws DatabaseClientException, KeyWrapException {
        final int tokenVersion = this.getAuthUser(authUserId)
                .map(AuthUser::getTokenVersion)
                .orElseThrow(() -> new IllegalStateException("@AuthService.issueTokens: no account under " + authUserId));
        final String accessToken = this.signer.sign(authUserId, tokenVersion, ACCESS_TOKEN_TTL_SECONDS);
        final RefreshToken refreshToken = new RefreshToken(authUserId, System.currentTimeMillis() + REFRESH_TOKEN_TTL_MILLIS);
        this.dataFactory.register(refreshToken);
        return new AuthTokens(accessToken, refreshToken.getToken());
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
     * @return a freshly issued {@link AuthTokens} pair asserting the matched account's id
     * @throws InvalidCredentialsException if no account matches {@code emailAddress}, or the
     *     password doesn't match
     */
    @NonNull
    @Override
    public AuthTokens login(@NonNull final String emailAddress, final char @NonNull [] rawPassword) {

        final Optional<AuthUser> userOpt;
        try {
            userOpt = this.dataFactory.getEntitiesByIndex(AuthUser.class, AuthUser.INDEX_EMAIL_ADDRESS, emailAddress).stream()
                    .findFirst();
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.login: failed to look up user '" + emailAddress + "'", e);
        }

        if (userOpt.isEmpty()) {
            this.auditLogService.record(new AuditEvent(null, AuditAction.LOGIN_FAILURE, emailAddress, null));
            throw new InvalidCredentialsException("invalid credentials");
        }
        final AuthUser user = userOpt.get();

        if (!this.hasher.verify(rawPassword, user.getPasswordHash())) {
            // actorAuthUserId deliberately null here too - a wrong password doesn't prove the
            // caller actually controls this account, so this entry shouldn't read as "this account
            // acted", only "this account was targeted" (targetId).
            this.auditLogService.record(new AuditEvent(null, AuditAction.LOGIN_FAILURE, emailAddress, null));
            throw new InvalidCredentialsException("invalid credentials");
        }

        // Checked after the password, deliberately: answering differently before it would tell an
        // anonymous caller which addresses have suspended accounts.
        if (user.isSuspended()) {
            this.auditLogService.record(new AuditEvent(null, AuditAction.LOGIN_FAILURE, emailAddress, "account suspended"));
            throw new InvalidCredentialsException("invalid credentials");
        }

        try {
            final AuthTokens tokens = this.issueTokens(user.getId());
            this.auditLogService.record(new AuditEvent(user.getId(), AuditAction.LOGIN_SUCCESS, emailAddress, null));
            return tokens;
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.login: failed to issue tokens for '" + emailAddress + "'", e);
        }
    }

    /**
     * Validates a JWT previously issued by {@link #login}, returning the embedded user id.
     *
     * <p>Signature and expiry are only the first half. The embedded id is looked back up, and the
     * token's session generation is compared against the account's current one, so a token whose
     * account has been deleted and a token issued before that account's sessions were ended are
     * both refused here rather than in one particular caller. Every caller inherits the check,
     * the live-update handshake included - which is why it lives at this level.
     *
     * <p>The same exception and the same message for every rejection reason, deliberately: a
     * caller must not learn which one applied.
     *
     * @param jwt the token to validate, as received in an {@code Authorization: Bearer} header
     * @return the {@link AuthUser#getId()} embedded in {@code jwt}
     * @throws InvalidJwtException if the token's signature is invalid, it is malformed, it has
     *     expired, its account no longer exists, or that account's sessions have been ended since
     *     the token was signed
     */
    @NonNull
    @Override
    public String validate(@NonNull final String jwt) throws InvalidJwtException {
        final VerifiedAccessToken verified = this.signer.verify(jwt);
        final AuthUser account = this.getAuthUser(verified.subject())
                .orElseThrow(() -> new InvalidJwtException("@AuthService.validate: invalid or expired token"));
        if (account.getTokenVersion() != verified.tokenVersion()) {
            throw new InvalidJwtException("@AuthService.validate: invalid or expired token");
        }
        return account.getId();
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
            accountExists = this.dataFactory.getEntitiesByIndex(AuthUser.class, AuthUser.INDEX_EMAIL_ADDRESS, emailAddress).stream()
                    .findAny().isPresent();
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
            this.sendVerificationEmail(
                    emailAddress,
                    "Reset your password",
                    "Reset your password.",
                    "We received a request to reset the password for this account. Enter the code below to continue.",
                    verificationCode,
                    "If you did not request a password reset, just ignore this e-mail - your password will not change. This code will expire within 10 minutes."
            );
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
     * @return a freshly issued {@link AuthTokens} pair asserting the matched {@link AuthUser#getId()}
     * @throws InvalidPasswordFormatException if {@code newPassword} doesn't meet {@link
     *     #requirePasswordFormat}'s requirement - checked first, before any database access
     * @throws InvalidVerificationCodeException if there is no pending reset under {@code
     *     emailAddress}, it has expired, {@code code} doesn't match, or (defense-in-depth) the
     *     account itself no longer exists
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    @Override
    public AuthTokens confirmPasswordReset(@NonNull final String emailAddress, @NonNull final String code, final char @NonNull [] newPassword)
            throws DatabaseClientException, KeyWrapException {

        requirePasswordFormat(newPassword);

        final Optional<PendingPasswordReset> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(PendingPasswordReset.keyOf(emailAddress), PendingPasswordReset.class);
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmPasswordReset: failed to look up pending reset for " + emailAddress, e);
        }

        final PendingPasswordReset pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(PendingPasswordReset.keyOf(emailAddress), PendingPasswordReset.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!verificationCodeMatches(pending.getVerificationCode(), code)) {
            // Count the miss, and burn the row once it has been guessed at enough times. Without
            // this a wrong guess costs nothing and the code stays alive for its full lifetime,
            // which a six-digit space cannot survive once the request rate limiter is out of the
            // way. The same exception either way, so an attacker cannot tell a wrong code from an
            // exhausted one.
            registerFailedVerificationAttempt(pending.withFailedAttempt(), PendingPasswordReset.keyOf(emailAddress), PendingPasswordReset.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser existing;
        try {
            existing = this.dataFactory.getEntitiesByIndex(AuthUser.class, AuthUser.INDEX_EMAIL_ADDRESS, emailAddress).stream()
                    .findFirst()
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmPasswordReset: failed to look up account for " + emailAddress, e);
        }

        final AuthUser updated = new AuthUser(existing.getId(), existing.getEmailAddress(), this.hasher.hash(newPassword),
                existing.isAdmin(), existing.isSuspended(), existing.getTokenVersion());
        this.dataFactory.update(updated);
        this.dataFactory.delete(PendingPasswordReset.keyOf(emailAddress), PendingPasswordReset.class);

        // Every existing session ends here, before the new pair is issued - otherwise the reset
        // changes the password without evicting whoever prompted it. Order matters: ending the
        // sessions after issuing would log the user out of the reset they just completed, since
        // issueTokens signs the generation this call advances.
        final int revokedSessions = this.endAllSessions(updated.getId());

        final AuthTokens tokens = this.issueTokens(updated.getId());
        this.auditLogService.record(new AuditEvent(updated.getId(), AuditAction.PASSWORD_RESET, emailAddress,
                revokedSessions + " session(s) revoked"));

        try {
            this.sendNoticeEmail(
                    emailAddress,
                    "Your password was changed",
                    "Your password was changed.",
                    "Your password has just been changed and every other device signed in to this account has been signed out.",
                    "If this was not you, reset your password again immediately - the new password is the only thing "
                            + "protecting this account now."
            );
        } catch (final EmailDeliveryException | RuntimeException noticeFailed) {
            // Best-effort: the reset is already persisted, so a delivery failure must not undo it.
            CloudDriver.getInstance().getLogger().log(Level.WARNING,
                    "@AuthService.confirmPasswordReset: failed to notify " + emailAddress + " of a completed reset", noticeFailed);
        }

        return tokens;
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
     * <p>The caller's current password is re-verified first, before anything is validated,
     * persisted or e-mailed: a bearer token alone must never be able to move an account to an
     * address its holder controls. The previous address is notified of the pending change, so the
     * real owner is told about a request that was not theirs while they can still act on it.
     *
     * @param authUserId the already-authenticated account requesting the change
     * @param newEmailAddress the address this account would move to on confirmation
     * @param currentPassword the caller's current password, re-verified before anything else
     *     happens; cleared as soon as it has been used
     * @throws InvalidCredentialsException if {@code newEmailAddress} fails the syntax check or its domain has no MX record
     * @throws EmailAlreadyRegisteredException if another account already exists under {@code newEmailAddress}
     * @throws DatabaseClientException if persisting the pending change fails
     * @throws KeyWrapException if the pending change's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @Override
    public void requestEmailChange(@NonNull final String authUserId, @NonNull final String newEmailAddress,
                                    final char @NonNull [] currentPassword)
            throws DatabaseClientException, KeyWrapException {

        // Re-authentication first, before anything is validated, persisted or e-mailed. An access
        // token alone must not be enough to move an account to an address the holder controls:
        // that turns a temporary credential - one read from a proxy log, or taken from an unlocked
        // machine - into permanent ownership, since a password reset on the new address then hands
        // over the account entirely while the real owner's own reset requests match nothing.
        // Deliberately checked before the already-registered test below, so a token holder cannot
        // use this route to enumerate which addresses have accounts.
        final AuthUser account;
        try {
            account = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new InvalidCredentialsException("Invalid credentials"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.requestEmailChange: failed to look up account " + authUserId, e);
        }
        final boolean passwordMatches = this.hasher.verify(currentPassword, account.getPasswordHash());
        // Cleared as soon as it has been used. Defence in depth only: the REST layer parsed the
        // body into an immutable String first, and that copy cannot be cleared at all.
        Arrays.fill(currentPassword, '\0');
        if (!passwordMatches) {
            // A failed re-authentication is recorded the same way a failed login is, and with the
            // same null actor: a wrong password does not prove the caller controls this account.
            this.auditLogService.record(new AuditEvent(null, AuditAction.LOGIN_FAILURE, account.getEmailAddress(),
                    "email change re-authentication"));
            throw new InvalidCredentialsException("Invalid credentials");
        }

        if (!EMAIL_PATTERN.matcher(newEmailAddress).matches())
            throw new InvalidCredentialsException("Invalid email address: " + newEmailAddress);

        final String domain = newEmailAddress.substring(newEmailAddress.indexOf('@') + 1);
        if (!domainHasMxRecord(domain))
            throw new InvalidCredentialsException("Email domain cannot receive mail (no MX record): " + domain);

        final boolean alreadyRegistered;
        try {
            alreadyRegistered = this.dataFactory.getEntitiesByIndex(AuthUser.class, AuthUser.INDEX_EMAIL_ADDRESS, newEmailAddress).stream()
                    .findAny().isPresent();
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
            this.sendVerificationEmail(
                    newEmailAddress,
                    "Confirm your new e-mail address",
                    "Confirm your new e-mail address.",
                    "We received a request to change this account's e-mail address to this one. Enter the code below to confirm.",
                    verificationCode,
                    "If you did not request this change, just ignore this e-mail - your account's e-mail address will not change. This code will expire within 10 minutes."
            );
        } catch (final EmailDeliveryException e) {
            throw new RuntimeException("@AuthService.requestEmailChange: failed to send verification email to " + newEmailAddress, e);
        }

        // The outgoing address is the only channel the real owner still controls if this request
        // was not theirs, so it is told what is happening while it can still act on it.
        try {
            this.sendNoticeEmail(
                    account.getEmailAddress(),
                    "Your e-mail address is being changed",
                    "Your e-mail address is being changed.",
                    "A request was made to move this account to " + newEmailAddress + ". If that was you, confirm it using the "
                            + "code sent to the new address - there is nothing to do here.",
                    "If this was not you, change your password immediately: whoever made this request can sign in right now."
            );
        } catch (final EmailDeliveryException | RuntimeException noticeFailed) {
            // Best-effort: failing to warn the old address must not block a legitimate change.
            CloudDriver.getInstance().getLogger().log(Level.WARNING,
                    "@AuthService.requestEmailChange: failed to notify " + account.getEmailAddress() + " of a pending change", noticeFailed);
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
     * row. Applying the change ends every session: an address change moves where every future
     * recovery mail goes, so each device must sign in again with the new address. Nothing is
     * returned to sign, so a client's next refresh answers with a rejection and it must prompt
     * for a login rather than treat that as an error. Both the previous and the new address are
     * notified that the move completed, best-effort.
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

        if (!verificationCodeMatches(pending.getVerificationCode(), code)) {
            // Count the miss, and burn the row once it has been guessed at enough times. Without
            // this a wrong guess costs nothing and the code stays alive for its full lifetime,
            // which a six-digit space cannot survive once the request rate limiter is out of the
            // way. The same exception either way, so an attacker cannot tell a wrong code from an
            // exhausted one.
            registerFailedVerificationAttempt(pending.withFailedAttempt(), authUserId, PendingEmailChange.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmEmailChange: failed to look up account " + authUserId, e);
        }

        // Captured before the account is rewritten - this is the last moment the outgoing address
        // is still readable, and it is the only channel the real owner may still control.
        final String previousEmailAddress = existing.getEmailAddress();

        final AuthUser updated = new AuthUser(existing.getId(), pending.getNewEmailAddress(), existing.getPasswordHash(),
                existing.isAdmin(), existing.isSuspended(), existing.getTokenVersion());
        this.dataFactory.update(updated);
        this.dataFactory.delete(authUserId, PendingEmailChange.class);

        // An address change moves where every future recovery mail goes, so it ends existing
        // sessions for the same reason a password reset does.
        final int revokedSessions = this.endAllSessions(authUserId);

        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.EMAIL_CHANGE, pending.getNewEmailAddress(),
                revokedSessions + " session(s) revoked"));

        this.sendEmailChangeCompletedNotice(previousEmailAddress,
                "This account now uses " + pending.getNewEmailAddress() + ". You have been signed out on every device and "
                        + "must sign in again with the new address.",
                "If this was not you, reset your password immediately - recovery e-mail for this account no longer "
                        + "arrives here.");
        this.sendEmailChangeCompletedNotice(pending.getNewEmailAddress(),
                "This account has moved from " + previousEmailAddress + " to this address. Every device has been signed "
                        + "out and must sign in again with the new address.",
                "If this was not you, reset your password immediately.");
    }

    /**
     * Best-effort notice that an e-mail change completed, sent to one address.
     *
     * <p>Never fails the change: it is already persisted by the time this runs, so a delivery
     * failure is logged and nothing else.
     *
     * @param toAddress the address to tell
     * @param introText the paragraph explaining what happened
     * @param noticeText the closing paragraph (what to do if this was not the recipient)
     */
    private void sendEmailChangeCompletedNotice(final String toAddress, final String introText, final String noticeText) {
        try {
            this.sendNoticeEmail(toAddress, "Your e-mail address has been changed",
                    "Your e-mail address has been changed.", introText, noticeText);
        } catch (final EmailDeliveryException | RuntimeException noticeFailed) {
            CloudDriver.getInstance().getLogger().log(Level.WARNING,
                    "@AuthService.confirmEmailChange: failed to notify " + toAddress + " of a completed change", noticeFailed);
        }
    }

    /**
     * Suspends or unsuspends {@code authUserId} - locking the account out without destroying it.
     *
     * <p>Suspending ends every outstanding session - refresh tokens revoked and every
     * already-issued access token refused on its next request - so the lock takes effect
     * immediately rather than whenever the current access token happens to expire. The account
     * keeps every file and folder it owns; unsuspending restores access with nothing lost, and
     * deliberately does not revive the tokens the suspension killed.
     *
     * @param authUserId the account to suspend or unsuspend
     * @param suspended {@code true} to suspend, {@code false} to lift it
     * @throws IllegalArgumentException if no account exists under {@code authUserId}
     */
    public void setSuspended(@NonNull final String authUserId, final boolean suspended) {
        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new IllegalArgumentException("no AuthUser with id " + authUserId));
            this.dataFactory.update(existing.withSuspended(suspended));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.setSuspended: failed to update the suspension flag for " + authUserId, e);
        }
        if (suspended) {
            // Suspending has to end what is already running, or the account stays usable until its
            // current access token expires - which is exactly the gap this exists to close. Not
            // done again on unsuspend: leaving the generation where it is keeps every
            // pre-suspension token dead once the lock is lifted.
            this.endAllSessions(authUserId);
        }
        this.auditLogService.record(new AuditEvent(authUserId,
                suspended ? AuditAction.ACCOUNT_SUSPEND : AuditAction.ACCOUNT_UNSUSPEND, existing.getEmailAddress(), null));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setAdmin(@NonNull final String authUserId, final boolean isAdmin) {
        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new IllegalArgumentException("no AuthUser with id " + authUserId));
            this.dataFactory.update(existing.withAdmin(isAdmin));
            // Recorded here rather than in the calling command: this is the service-level fact,
            // so it stays recorded however this method is reached.
            this.auditLogService.record(new AuditEvent(authUserId,
                    isAdmin ? AuditAction.ADMIN_GRANT : AuditAction.ADMIN_REVOKE, existing.getEmailAddress(),
                    "set from the operator console"));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.setAdmin: failed to update admin flag for " + authUserId, e);
        }
    }

    /**
     * Exchanges {@code refreshToken} for a fresh {@link AuthTokens} pair - see {@link
     * IAuthService#refresh}'s own Javadoc for the rotate-on-every-use contract this implements.
     * Rejects (via {@link InvalidRefreshTokenException}, the same message for every case, matching
     * this class's other "don't leak which" methods) a token that doesn't exist, has expired, has
     * already been revoked/rotated away, or whose account no longer exists - the last check exists
     * so a {@link RefreshToken} row surviving an account's deletion (nothing in this codebase
     * cascades that delete onto outstanding refresh tokens today) can never mint a fresh access
     * token for an id nothing backs anymore.
     *
     * <p>The actual atomicity guard against two near-simultaneous calls presenting the same token
     * is {@link DataFactory#delete}'s own "no such id" failure: this method deletes {@code
     * refreshToken}'s row <em>before</em> issuing new tokens, so only the first of two racing
     * calls can ever succeed at that delete - the second observes the row already gone and is
     * rejected the same way a genuinely unknown token would be. {@code DataFactory} offers no
     * compare-and-swap/conditional-update primitive this could instead be built on, and this
     * single-process deployment has no cross-process transaction to lean on either - this is the
     * strongest guarantee actually available on this stack.
     *
     * @param refreshToken a refresh token previously returned by {@link #login}/{@link
     *     #confirmRegistration}/{@link #confirmPasswordReset}/a prior call to this method
     * @return a freshly issued {@link AuthTokens} pair
     * @throws InvalidRefreshTokenException if {@code refreshToken} doesn't exist, has expired, has
     *     already been used/revoked, or its account no longer exists
     * @throws DatabaseClientException if persisting the rotation fails
     * @throws KeyWrapException if the new refresh token's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    @Override
    public AuthTokens refresh(@NonNull final String refreshToken) throws DatabaseClientException, KeyWrapException {

        final StoredRefreshToken found = this.findRefreshToken(refreshToken)
                .orElseThrow(() -> new InvalidRefreshTokenException("invalid or expired refresh token"));
        final RefreshToken pending = found.token();

        if (pending.isExpired() || pending.isRevoked()) {
            this.deleteRefreshTokenQuietly(found.rowId());
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        final boolean accountStillExists;
        try {
            accountStillExists = this.dataFactory.findById(pending.getAuthUserId(), AuthUser.class).isPresent();
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.refresh: failed to look up account " + pending.getAuthUserId(), e);
        }
        if (!accountStillExists) {
            this.deleteRefreshTokenQuietly(found.rowId());
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        try {
            this.dataFactory.delete(found.rowId(), RefreshToken.class);
        } catch (final DatabaseClientException alreadyRotatedAway) {
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        return this.issueTokens(pending.getAuthUserId());
    }

    /**
     * Revokes every refresh token issued to {@code authUserId}, returning how many were ended.
     *
     * <p>The eviction step a credential change performs. Without it, resetting a password - the
     * one recovery action a user has when they believe an account is compromised - leaves every
     * stolen session working: {@link #refresh} checks only expiry, the revoked flag and the
     * account's existence, never whether the password has changed since, and each refresh mints
     * another long-lived token. The same applies to a token left behind on a lost device.
     *
     * <p>An indexed lookup, not a scan: see {@link RefreshToken#INDEX_AUTH_USER_ID}.
     *
     * <p>Half of a sign-out on its own, which is why it is private: it leaves an access token
     * already in someone's hands working until it expires. {@link #endAllSessions(String)} is the
     * entry point, and every caller goes through it.
     *
     * @param authUserId the account whose sessions to end
     * @return how many tokens were revoked
     */
    private int revokeAllRefreshTokens(@NonNull final String authUserId) {
        final List<RefreshToken> tokens;
        try {
            tokens = this.dataFactory.getEntitiesByIndex(RefreshToken.class, RefreshToken.INDEX_AUTH_USER_ID, authUserId);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.revokeAllRefreshTokens: failed to list sessions of " + authUserId, e);
        }
        // Live-update connections authenticate once, at open time, so ending the sessions has to
        // end those too - otherwise a revoked session keeps receiving the account's changes for as
        // long as its socket stays up.
        closeLiveUpdateSessionsQuietly(authUserId);

        int revoked = 0;
        for (final RefreshToken token : tokens) {
            if (token.isRevoked()) {
                continue;
            }
            try {
                this.dataFactory.update(token.revoked());
                revoked++;
            } catch (final DatabaseClientException | KeyWrapException failed) {
                // Best-effort per token: one row that will not update must not leave the rest of
                // the account's sessions alive.
                CloudDriver.getInstance().getLogger().log(Level.WARNING,
                        "@AuthService.revokeAllRefreshTokens: failed to revoke one session of " + authUserId, failed);
            }
        }
        return revoked;
    }

    /**
     * Ends every session of {@code authUserId}: bumps the account's session generation, so every
     * access token already issued stops validating at once, then revokes every outstanding
     * refresh token and closes the account's live-update connections.
     *
     * <p>The bump is deliberately not best-effort, unlike the per-token loop inside the
     * revocation it delegates to: a silently swallowed bump would leave every stolen access token
     * alive while the audit line claims the sessions were ended, which is the whole failure this
     * method exists to prevent. So a failure to persist it throws.
     *
     * @param authUserId the account whose sessions to end
     * @return how many refresh tokens were revoked
     */
    @Override
    public int endAllSessions(@NonNull final String authUserId) {
        try {
            final Optional<AuthUser> account = this.dataFactory.findById(authUserId, AuthUser.class);
            if (account.isPresent()) {
                this.dataFactory.update(account.get().withNextTokenVersion());
            }
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.endAllSessions: failed to end the sessions of " + authUserId, e);
        }
        return this.revokeAllRefreshTokens(authUserId);
    }

    /**
     * Largest number of wrong verification codes one pending row tolerates before it is discarded
     * and a fresh code must be requested.
     */
    private static final int MAX_VERIFICATION_ATTEMPTS = 5;

    /**
     * Compares a presented verification code against the stored one in constant time.
     *
     * @param expected the code this row was issued with
     * @param presented the code the caller supplied
     * @return {@code true} if they match
     */
    private static boolean verificationCodeMatches(final String expected, final String presented) {
        if (expected == null || presented == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Records one failed verification against {@code pending}, deleting the row outright once it
     * has failed {@link #MAX_VERIFICATION_ATTEMPTS} times.
     *
     * <p>Best-effort: the caller is about to reject the attempt regardless, and a bookkeeping
     * failure must not turn a wrong code into a server error.
     *
     * @param pending the row with its incremented attempt count already applied
     * @param primaryKey the row's primary key (a digest, for the address-keyed rows), for the delete
     * @param type the row's entity type, for the delete
     */
    private void registerFailedVerificationAttempt(final de.lino.database.database.entity.Serialized pending,
                                                    final String primaryKey, final Class<? extends de.lino.database.database.entity.Serialized> type) {
        try {
            if (failedAttemptsOf(pending) >= MAX_VERIFICATION_ATTEMPTS) {
                this.dataFactory.delete(primaryKey, type);
                return;
            }
            this.dataFactory.update(pending);
        } catch (final DatabaseClientException | KeyWrapException | RuntimeException bookkeepingFailed) {
            CloudDriver.getInstance().getLogger().log(Level.WARNING,
                    "@AuthService: failed to record a wrong verification code against " + primaryKey, bookkeepingFailed);
        }
    }

    /**
     * @param pending one of the three pending-flow rows
     * @return how many wrong codes have been presented against it
     */
    private static int failedAttemptsOf(final de.lino.database.database.entity.Serialized pending) {
        if (pending instanceof PendingRegistration registration) return registration.getFailedAttempts();
        if (pending instanceof PendingPasswordReset reset) return reset.getFailedAttempts();
        if (pending instanceof PendingEmailChange change) return change.getFailedAttempts();
        return 0;
    }


    /**
     * Closes every live-update connection held by {@code authUserId}, if live push is running.
     *
     * <p>Best-effort and facet-optional: a deployment without the REST extension has no publisher,
     * and a connection that will not close must not fail the revocation that asked for it.
     *
     * @param authUserId the account whose connections to close
     */
    private static void closeLiveUpdateSessionsQuietly(final String authUserId) {
        try {
            final de.lino.cloud.api.push.LiveUpdatePublisher publisher =
                    CloudDriver.getInstance().getServiceContainer().getLiveUpdatePublisher();
            if (publisher != null) publisher.closeSessionsOf(authUserId);
        } catch (final RuntimeException closeFailed) {
            CloudDriver.getInstance().getLogger().log(Level.WARNING,
                    "@AuthService: failed to close the live-update sessions of " + authUserId, closeFailed);
        }
    }

    /**
     * Best-effort delete of an already-invalid {@link RefreshToken} row - {@link #refresh} throws
     * regardless of whether this succeeds.
     *
     * @param rowId the id the row was actually found under, as reported by {@link
     *     #findRefreshToken(String)} - never a presented token, which is not a key
     */
    private void deleteRefreshTokenQuietly(final String rowId) {
        try {
            this.dataFactory.delete(rowId, RefreshToken.class);
        } catch (final DatabaseClientException ignored) {
            // Best-effort cleanup only - the caller is about to throw InvalidRefreshTokenException
            // regardless of whether this delete succeeds.
        }
    }

    /**
     * A refresh-token row as it was actually found: the entity, and the id its row is stored
     * under.
     *
     * @param token the row's decrypted entity
     * @param rowId the id the row was found under, which is what a delete or an update must target
     */
    private record StoredRefreshToken(RefreshToken token, String rowId) {
    }

    /**
     * Resolves a presented refresh token to its row, whichever id that row is filed under.
     *
     * <p>A row written before these identifiers were stored as digests is still filed under the
     * token itself, so the digest lookup misses it. Rather than reject a session that is
     * perfectly valid, such a value is retried against its own id, and the caller then rotates or
     * revokes the row it found - which moves it off the plain-text key for good. Nothing is ever
     * written under a presented value by this path; it only reads.
     *
     * <p>The retry is refused for a value that already has the shape of a stored key. Without
     * that guard, anyone holding a database dump could present a stored id as if it were a token
     * and be authenticated by the fallback - which is precisely what keying on the digest exists
     * to prevent. A real token is base64url of {@link RefreshToken#RAW_TOKEN_LENGTH_BYTES} bytes,
     * so it is never all-lowercase hexadecimal and never loses anything to the guard.
     *
     * <p>Transitional: once no row is filed under a presented value any more, this resolves
     * exactly what the digest lookup alone would.
     *
     * @param presented the refresh token as the client supplied it
     * @return the row and the id it was found under, or {@link Optional#empty()} if nothing matches
     * @throws DatabaseClientException if a lookup fails
     * @throws KeyWrapException if a row's data-encryption key cannot be unwrapped by the KMS/HSM
     */
    @NonNull
    private Optional<StoredRefreshToken> findRefreshToken(@NonNull final String presented)
            throws DatabaseClientException, KeyWrapException {
        try {
            final String digestKey = RefreshToken.keyOf(presented);
            final Optional<RefreshToken> byDigest = this.dataFactory.findById(digestKey, RefreshToken.class);
            if (byDigest.isPresent()) {
                return Optional.of(new StoredRefreshToken(byDigest.get(), digestKey));
            }
            if (LookupKeyDigest.isDigest(presented)) {
                return Optional.empty();
            }
            return this.dataFactory.findById(presented, RefreshToken.class)
                    .map(legacy -> new StoredRefreshToken(legacy, presented));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.findRefreshToken: failed to look up refresh token", e);
        }
    }

    /**
     * Marks {@code refreshToken} revoked, if it still exists and isn't already - see {@link
     * IAuthService#revokeRefreshToken}'s own Javadoc for why this is deliberately a no-op rather
     * than throwing when there's nothing left to revoke.
     *
     * @param refreshToken the token to revoke
     */
    @Override
    public void revokeRefreshToken(@NonNull final String refreshToken) {
        final Optional<StoredRefreshToken> found;
        try {
            found = this.findRefreshToken(refreshToken);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.revokeRefreshToken: failed to look up refresh token", e);
        }
        if (found.isEmpty() || found.get().token().isRevoked()) {
            return;
        }
        try {
            if (found.get().rowId().equals(RefreshToken.keyOf(refreshToken))) {
                this.dataFactory.update(found.get().token().revoked());
            } else {
                // A row still filed under the presented value cannot be updated: an update writes
                // under the entity's own primary key, which is the digest, and would find nothing
                // there. Deleting the row ends the session with the same observable result.
                this.dataFactory.delete(found.get().rowId(), RefreshToken.class);
            }
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.revokeRefreshToken: failed to revoke refresh token", e);
        }
        // Same reasoning as revokeAllRefreshTokens: a logged-out client must stop receiving push.
        closeLiveUpdateSessionsQuietly(found.get().token().getAuthUserId());
    }

}
