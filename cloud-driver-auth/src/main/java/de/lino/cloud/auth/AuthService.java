package de.lino.cloud.auth;

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
import de.lino.cloud.api.jwt.auth.AuthTokens;
import de.lino.cloud.api.jwt.auth.IAuthService;
import de.lino.cloud.api.jwt.auth.LoginResult;
import de.lino.cloud.api.jwt.auth.TwoFactorSetupStart;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.mail.EmailDeliveryException;
import de.lino.cloud.api.mail.EmailSender;
import de.lino.cloud.api.security.crypto.AuthenticationFailedException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import de.lino.cloud.api.security.password.PasswordHasher;
import de.lino.cloud.api.user.ICloudUserService;
import de.lino.cloud.auth.entity.RefreshToken;
import de.lino.cloud.auth.mail.EmailTemplates;
import de.lino.cloud.auth.pending.PendingEmailChange;
import de.lino.cloud.auth.pending.PendingPasswordReset;
import de.lino.cloud.auth.pending.PendingRegistration;
import de.lino.cloud.auth.pending.PendingTwoFactorLogin;
import de.lino.cloud.auth.pending.PendingTwoFactorSetup;
import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
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

    /** How long a {@link PendingTwoFactorSetup} row issued by {@link #beginTwoFactorSetup} remains valid: 10 minutes. */
    private static final long TWO_FACTOR_SETUP_TTL_MILLIS = Duration.ofMinutes(10).toMillis();

    /**
     * How long a {@link PendingTwoFactorLogin} row issued by {@link #login} remains valid: 5
     * minutes. Deliberately shorter than every other pending-code TTL in this class - unlike an
     * e-mailed code (which has to survive mail-server/network delivery delay before the caller can
     * even see it), the caller here already has their authenticator app open the moment {@link
     * #login} returns, so there is no equivalent delivery delay to accommodate.
     */
    private static final long TWO_FACTOR_LOGIN_TTL_MILLIS = Duration.ofMinutes(5).toMillis();

    /** The issuer name embedded in every {@code otpauth://} URI {@link #beginTwoFactorSetup} builds - shown by an authenticator app next to the account entry. */
    private static final String TWO_FACTOR_ISSUER = "Cloud Driver";

    /** Source of randomness for {@link #generateVerificationCode()}. */
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Verifies a caller-supplied TOTP code against a secret - {@link
     * DefaultCodeVerifier}'s default construction already tolerates the small clock-drift window
     * (one step before/after the current one) a real authenticator-app/server clock pair needs in
     * practice. Stateless and thread-safe, so one shared instance serves every {@link
     * #confirmTwoFactorSetup}/{@link #completeTwoFactorLogin} call.
     */
    private static final CodeVerifier TOTP_CODE_VERIFIER = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());

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
     * e-mail change) to the persisted audit trail - see {@code architecture/SERVICES.md} item 11
     * and {@code AuditLogService}'s own Javadoc. Never throws, so every call site below invokes it
     * directly with no defensive try/catch of its own.
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
     * as a duplicate - {@link PendingRegistration#keysOf()} is keyed on {@code emailAddress}
     * itself, so it simply overwrites the previous attempt with a fresh code/expiry.
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

        return this.issueTokens(user.getId());
    }

    /**
     * Signs a fresh access JWT and mints/persists a fresh {@link RefreshToken} for {@code
     * authUserId}, bundling both into the {@link AuthTokens} pair {@link #login}/{@link
     * #confirmRegistration}/{@link #confirmPasswordReset}/{@link #refresh} all return. The one
     * place any of those four methods actually issues tokens, so the pairing is never built
     * inconsistently between them.
     *
     * @param authUserId the account id to issue tokens for
     * @return a freshly issued {@link AuthTokens} pair
     * @throws DatabaseClientException if persisting the new {@link RefreshToken} fails
     * @throws KeyWrapException if the new {@link RefreshToken}'s data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    private AuthTokens issueTokens(@NonNull final String authUserId) throws DatabaseClientException, KeyWrapException {
        final String accessToken = this.signer.sign(authUserId, ACCESS_TOKEN_TTL_SECONDS);
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
     * @return a {@link LoginResult} carrying a freshly issued {@link AuthTokens} pair, or (if the
     *     matched account has two-factor authentication enabled) a pending second-factor token
     * @throws InvalidCredentialsException if no account matches {@code emailAddress}, or the
     *     password doesn't match
     */
    @NonNull
    @Override
    public LoginResult login(@NonNull final String emailAddress, final char @NonNull [] rawPassword) {

        final Optional<AuthUser> userOpt;
        try {
            userOpt = this.dataFactory.getEntities(AuthUser.class).stream()
                    .filter(candidate -> candidate.getEmailAddress().equals(emailAddress))
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

        if (user.isTwoFactorEnabled()) {
            // Password verified, but a second factor is still required - deliberately no
            // LOGIN_SUCCESS audit event yet (see #completeTwoFactorLogin, which fires it once the
            // TOTP code also verifies) and no tokens issued yet either.
            try {
                final PendingTwoFactorLogin pending = new PendingTwoFactorLogin(
                        user.getId(), System.currentTimeMillis() + TWO_FACTOR_LOGIN_TTL_MILLIS);
                this.dataFactory.register(pending);
                return LoginResult.requiresTwoFactor(pending.getToken());
            } catch (final DatabaseClientException | KeyWrapException e) {
                throw new RuntimeException("@AuthService.login: failed to persist pending two-factor login for '" + emailAddress + "'", e);
            }
        }

        try {
            final AuthTokens tokens = this.issueTokens(user.getId());
            this.auditLogService.record(new AuditEvent(user.getId(), AuditAction.LOGIN_SUCCESS, emailAddress, null));
            return LoginResult.success(tokens);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.login: failed to issue tokens for '" + emailAddress + "'", e);
        }
    }

    /**
     * See {@link IAuthService#beginTwoFactorSetup}'s own Javadoc.
     */
    @NonNull
    @Override
    public TwoFactorSetupStart beginTwoFactorSetup(@NonNull final String authUserId) {

        final AuthUser user;
        try {
            user = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new IllegalArgumentException("no AuthUser with id " + authUserId));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.beginTwoFactorSetup: failed to look up account " + authUserId, e);
        }

        final String secretBase32 = new DefaultSecretGenerator().generate();
        final PendingTwoFactorSetup pending = new PendingTwoFactorSetup(
                authUserId, secretBase32, System.currentTimeMillis() + TWO_FACTOR_SETUP_TTL_MILLIS);
        try {
            this.dataFactory.register(pending);
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.beginTwoFactorSetup: failed to persist pending setup for " + authUserId, e);
        }

        final String otpauthUri = new QrData.Builder()
                .label(user.getEmailAddress())
                .secret(secretBase32)
                .issuer(TWO_FACTOR_ISSUER)
                .build()
                .getUri();

        return new TwoFactorSetupStart(secretBase32, otpauthUri);
    }

    /**
     * See {@link IAuthService#confirmTwoFactorSetup}'s own Javadoc.
     */
    @Override
    public void confirmTwoFactorSetup(@NonNull final String authUserId, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingTwoFactorSetup> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(authUserId, PendingTwoFactorSetup.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmTwoFactorSetup: failed to look up pending setup for " + authUserId, e);
        }

        final PendingTwoFactorSetup pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(authUserId, PendingTwoFactorSetup.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        if (!TOTP_CODE_VERIFIER.isValidCode(pending.getSecretBase32(), code)) {
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
            this.dataFactory.update(existing.withTotpSecret(pending.getSecretBase32()));
            this.dataFactory.delete(authUserId, PendingTwoFactorSetup.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.confirmTwoFactorSetup: failed to enable two-factor authentication for " + authUserId, e);
        }

        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.TWO_FACTOR_ENABLED, authUserId, null));
    }

    /**
     * See {@link IAuthService#disableTwoFactor}'s own Javadoc.
     */
    @Override
    public void disableTwoFactor(@NonNull final String authUserId, final char @NonNull [] password) {

        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class).orElse(null);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.disableTwoFactor: failed to look up account " + authUserId, e);
        }

        // Same message whether the account doesn't exist (shouldn't happen behind an
        // already-validated bearer token) or the password doesn't match - matching #login's own
        // "don't leak which" idiom.
        if (existing == null || !this.hasher.verify(password, existing.getPasswordHash())) {
            throw new InvalidCredentialsException("invalid credentials");
        }

        try {
            this.dataFactory.update(existing.withTotpSecret(null));
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.disableTwoFactor: failed to disable two-factor authentication for " + authUserId, e);
        }

        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.TWO_FACTOR_DISABLED, authUserId, null));
    }

    /**
     * See {@link IAuthService#completeTwoFactorLogin}'s own Javadoc.
     */
    @NonNull
    @Override
    public AuthTokens completeTwoFactorLogin(@NonNull final String pendingTwoFactorToken, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException {

        final Optional<PendingTwoFactorLogin> pendingOpt;
        try {
            pendingOpt = this.dataFactory.findById(pendingTwoFactorToken, PendingTwoFactorLogin.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.completeTwoFactorLogin: failed to look up pending login", e);
        }

        final PendingTwoFactorLogin pending = pendingOpt.orElseThrow(
                () -> new InvalidVerificationCodeException("invalid or expired verification code"));

        if (pending.isExpired()) {
            this.dataFactory.delete(pendingTwoFactorToken, PendingTwoFactorLogin.class);
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        final AuthUser user;
        try {
            user = this.dataFactory.findById(pending.getAuthUserId(), AuthUser.class)
                    .orElseThrow(() -> new InvalidVerificationCodeException("invalid or expired verification code"));
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.completeTwoFactorLogin: failed to look up account " + pending.getAuthUserId(), e);
        }

        // Defense-in-depth: the account may have disabled two-factor authentication after this
        // pending login was issued (e.g. via a different, already-authenticated session) - treat
        // that the same as an invalid code rather than falling back to issuing tokens anyway.
        if (!user.isTwoFactorEnabled() || !TOTP_CODE_VERIFIER.isValidCode(user.getTotpSecretBase32(), code)) {
            // Deliberately not deleted here - see PendingTwoFactorLogin's own Javadoc on why a
            // mistyped code may be retried within the pending token's validity window.
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        try {
            this.dataFactory.delete(pendingTwoFactorToken, PendingTwoFactorLogin.class);
        } catch (final DatabaseClientException alreadyConsumed) {
            throw new InvalidVerificationCodeException("invalid or expired verification code");
        }

        try {
            final AuthTokens tokens = this.issueTokens(user.getId());
            this.auditLogService.record(new AuditEvent(user.getId(), AuditAction.LOGIN_SUCCESS, user.getEmailAddress(), null));
            return tokens;
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.completeTwoFactorLogin: failed to issue tokens for " + user.getId(), e);
        }
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

        final AuthUser updated = new AuthUser(existing.getId(), existing.getEmailAddress(), this.hasher.hash(newPassword),
                existing.isAdmin(), existing.getTotpSecretBase32());
        this.dataFactory.update(updated);
        this.dataFactory.delete(emailAddress, PendingPasswordReset.class);

        final AuthTokens tokens = this.issueTokens(updated.getId());
        this.auditLogService.record(new AuditEvent(updated.getId(), AuditAction.PASSWORD_RESET, emailAddress, null));
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

        final AuthUser updated = new AuthUser(existing.getId(), pending.getNewEmailAddress(), existing.getPasswordHash(),
                existing.isAdmin(), existing.getTotpSecretBase32());
        this.dataFactory.update(updated);
        this.dataFactory.delete(authUserId, PendingEmailChange.class);

        this.auditLogService.record(new AuditEvent(authUserId, AuditAction.EMAIL_CHANGE, pending.getNewEmailAddress(), null));
    }

    /**
     * Grants or revokes the {@link AuthUser#isAdmin()} flag for {@code authUserId} - the only
     * writer of that field anywhere in this codebase (see {@link AuthUser#isAdmin()}'s own
     * Javadoc: never reachable via any REST route, only this method, called from a terminal
     * {@code Command} by the operator console).
     *
     * @param authUserId the account to grant/revoke admin on
     * @param isAdmin the new admin flag value
     * @throws IllegalArgumentException if no account exists under {@code authUserId}
     */
    @Override
    public void setAdmin(@NonNull final String authUserId, final boolean isAdmin) {
        final AuthUser existing;
        try {
            existing = this.dataFactory.findById(authUserId, AuthUser.class)
                    .orElseThrow(() -> new IllegalArgumentException("no AuthUser with id " + authUserId));
            this.dataFactory.update(existing.withAdmin(isAdmin));
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
     * single-process deployment (see {@code CLAUDE.md}) has no cross-process transaction to lean
     * on either - this is the strongest guarantee actually available on this stack.
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

        final RefreshToken pending;
        try {
            pending = this.dataFactory.findById(refreshToken, RefreshToken.class)
                    .orElseThrow(() -> new InvalidRefreshTokenException("invalid or expired refresh token"));
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.refresh: failed to look up refresh token", e);
        }

        if (pending.isExpired() || pending.isRevoked()) {
            this.deleteRefreshTokenQuietly(refreshToken);
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        final boolean accountStillExists;
        try {
            accountStillExists = this.dataFactory.findById(pending.getAuthUserId(), AuthUser.class).isPresent();
        } catch (final AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.refresh: failed to look up account " + pending.getAuthUserId(), e);
        }
        if (!accountStillExists) {
            this.deleteRefreshTokenQuietly(refreshToken);
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        try {
            this.dataFactory.delete(refreshToken, RefreshToken.class);
        } catch (final DatabaseClientException alreadyRotatedAway) {
            throw new InvalidRefreshTokenException("invalid or expired refresh token");
        }

        return this.issueTokens(pending.getAuthUserId());
    }

    /** Best-effort delete of an already-invalid {@link RefreshToken} row - {@link #refresh} throws regardless of whether this succeeds. */
    private void deleteRefreshTokenQuietly(final String refreshToken) {
        try {
            this.dataFactory.delete(refreshToken, RefreshToken.class);
        } catch (final DatabaseClientException ignored) {
            // Best-effort cleanup only - the caller is about to throw InvalidRefreshTokenException
            // regardless of whether this delete succeeds.
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
        final Optional<RefreshToken> existing;
        try {
            existing = this.dataFactory.findById(refreshToken, RefreshToken.class);
        } catch (final DatabaseClientException | KeyWrapException | AuthenticationFailedException e) {
            throw new RuntimeException("@AuthService.revokeRefreshToken: failed to look up refresh token", e);
        }
        if (existing.isEmpty() || existing.get().isRevoked()) {
            return;
        }
        try {
            this.dataFactory.update(existing.get().revoked());
        } catch (final DatabaseClientException | KeyWrapException e) {
            throw new RuntimeException("@AuthService.revokeRefreshToken: failed to revoke refresh token", e);
        }
    }

}
