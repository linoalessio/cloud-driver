package de.lino.cloud.api.jwt.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidPasswordFormatException;
import de.lino.cloud.api.jwt.InvalidRefreshTokenException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;

/**
 * Verifies end-user login (username + password) against {@link de.lino.cloud.api.jwt.user.AuthUser}
 * entities stored via {@link DataFactory}, and issues/validates the JWTs
 * that then authenticate every subsequent request from that client - see
 * {@link de.lino.cloud.api.jwt.JwtSigner}. Deliberately separate from {@code
 * de.lino.cloud.api.security.rest.ApiKey}: an end user never sees, and this
 * class never needs, database credentials.
 *
 * <p>Framework-agnostic on purpose - this module has no Javalin dependency,
 * so {@link #login}/{@link #validate} throw plain {@link
 * InvalidCredentialsException}/{@link InvalidJwtException} rather than an
 * HTTP-specific type; a caller wiring this into an HTTP layer (e.g. {@code
 * cloud-driver-plugin}'s {@code DefaultRestFactory}) translates those into
 * the appropriate response itself.
 *
 * <p>{@link #register} itself is transport-agnostic - whether it's reachable over HTTP is a
 * deployment choice made by whichever caller wires it up. This deployment wants open
 * self-registration: {@code cloud-driver-plugin}'s {@code DefaultRestFactory(DataFactory,
 * AuthService)} constructor mounts {@code POST /auth/register} (and {@code
 * POST /auth/register/confirm}) automatically whenever an {@code AuthService} is supplied, the
 * same way it already mounts {@code POST /auth/login} - this is the only way a new account gets
 * created (the earlier operator-run {@code CreateUserCli} has been removed).
 *
 * <p>Registration is a two-step, e-mail-verified flow: {@link #register} does <b>not</b>
 * create the account - it validates the address, stashes the hashed password and a freshly
 * generated, time-limited verification code, and e-mails that code to the caller. Only {@link
 * #confirmRegistration}, given that same code back within its validity window, actually creates
 * the account and returns a token pair - the same shape {@link #login} does. This guards against
 * accounts being created under an e-mail address the registrant doesn't actually control.
 *
 * <p>{@link #login}/{@link #confirmRegistration}/{@link #confirmPasswordReset} all return an
 * {@link AuthTokens} pair rather than a bare access-token {@link String}: alongside the
 * short-lived (12h) access JWT, each also issues a longer-lived, opaque, single-use refresh
 * token (see {@code RefreshToken} in {@code cloud-driver-auth}) a client can later exchange, via
 * {@link #refresh}, for a fresh pair without asking the user to log in again with a password -
 * useful for a long-running client (e.g. a desktop app) that would otherwise be forced back to
 * the login screen every 12 hours. A refresh token is rotated on every use - see {@link
 * #refresh}'s own Javadoc.
 */
public interface IAuthService {

    /**
     * Starts registration of a new account under {@code emailAddress}: validates the address,
     * checks no account already exists under it, then persists a pending registration (hashed
     * password + a freshly generated verification code, valid for a short window) and e-mails
     * that code to {@code emailAddress}. Does <b>not</b> create the account yet - see {@link
     * #confirmRegistration}. Exposed over HTTP as {@code POST /auth/register} by {@code
     * cloud-driver-plugin}'s {@code DefaultRestFactory} whenever it's constructed with an {@code
     * AuthService}.
     *
     * @param emailAddress the new account's identifying e-mail address
     * @param rawPassword the new account's plaintext password, hashed before persistence and never itself retained -
     *     must be at least 8 characters and contain a digit, a lowercase letter, an uppercase letter, and a symbol,
     *     and must not contain {@code ;}, {@code ,}, {@code :}, or {@code `}
     * @throws InvalidPasswordFormatException if {@code rawPassword} doesn't meet that format requirement
     * @throws EmailAlreadyRegisteredException if an account already exists under {@code emailAddress}
     * @throws DatabaseClientException if persisting the pending registration fails
     * @throws KeyWrapException if the pending registration's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void register(@NonNull final String emailAddress, final char @NonNull [] rawPassword) throws DatabaseClientException, KeyWrapException;

    /**
     * Completes a registration previously started by {@link #register}: verifies {@code code}
     * against the pending registration stored under {@code emailAddress} (and that it hasn't
     * expired), then creates the real account and returns a signed JWT for it - the caller goes
     * straight from a confirmed code into an authenticated session, the same way {@link #login}'s
     * result is used. Exposed over HTTP as {@code POST /auth/register/confirm}.
     *
     * @param emailAddress the e-mail address {@link #register} was called with
     * @param code the verification code e-mailed to {@code emailAddress}
     * @return a freshly issued {@link AuthTokens} pair asserting the newly created account's id
     * @throws InvalidVerificationCodeException if there is no pending registration under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match
     * @throws DatabaseClientException if creating the account fails
     * @throws KeyWrapException if the new account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    AuthTokens confirmRegistration(@NonNull final String emailAddress, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException;

    /**
     * Verifies {@code username}/{@code rawPassword}, returning a {@link LoginResult} on success -
     * a freshly issued {@link AuthTokens} pair if the matched account has two-factor authentication
     * disabled, or a pending second-factor token (see {@link LoginResult#requiresTwoFactor()}) that
     * must be exchanged, together with a valid TOTP code, via {@link #completeTwoFactorLogin} if it
     * doesn't.
     *
     * @param username the account's identifying username (an email address, in the current implementation) to verify
     * @param rawPassword the plaintext password to verify against the stored hash
     * @return a {@link LoginResult} either carrying a freshly issued {@link AuthTokens} pair, or
     *     signaling that a second factor is still required
     * @throws InvalidCredentialsException if the username doesn't exist or the password doesn't match
     */
    @NonNull
    LoginResult login(@NonNull final String username, final char @NonNull [] rawPassword);

    /**
     * Starts enabling two-factor authentication for the already-authenticated account {@code
     * authUserId}: generates a fresh TOTP secret and persists it as a not-yet-live pending setup
     * (see {@code de.lino.cloud.auth.pending.PendingTwoFactorSetup}), valid for a short window,
     * without touching the account's live {@code totpSecretBase32} yet - only {@link
     * #confirmTwoFactorSetup} does that, once the caller has proven it can actually produce a valid
     * code from the secret. Exposed over HTTP as {@code POST /auth/2fa/setup} (bearer-gated).
     *
     * @param authUserId the already-authenticated account starting two-factor setup
     * @return the freshly generated secret, plus a ready-to-render {@code otpauth://} URI
     * @throws IllegalArgumentException if no account exists under {@code authUserId}
     * @throws de.lino.cloud.api.security.database.DatabaseClientException if persisting the pending setup fails
     * @throws de.lino.cloud.api.security.keys.KeyWrapException if the pending setup's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    TwoFactorSetupStart beginTwoFactorSetup(@NonNull final String authUserId);

    /**
     * Completes a two-factor setup previously started by {@link #beginTwoFactorSetup}: verifies
     * {@code code} against the pending setup's secret (and that it hasn't expired), then promotes
     * that secret onto the account's live {@link de.lino.cloud.api.jwt.user.AuthUser#getTotpSecretBase32()}
     * and deletes the pending row. From this point on, {@link #login} for this account returns a
     * pending second-factor token instead of tokens directly. Exposed over HTTP as {@code
     * POST /auth/2fa/confirm} (bearer-gated).
     *
     * @param authUserId the already-authenticated account confirming setup
     * @param code the current TOTP code, produced by the caller's authenticator app from the pending secret
     * @throws InvalidVerificationCodeException if there is no pending setup under {@code authUserId},
     *     it has expired, or {@code code} doesn't verify against its secret
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void confirmTwoFactorSetup(@NonNull final String authUserId, @NonNull final String code) throws DatabaseClientException, KeyWrapException;

    /**
     * Disables two-factor authentication for the already-authenticated account {@code authUserId} -
     * a security-sensitive action, so this re-verifies {@code password} against the account's stored
     * hash first rather than trusting the caller's session/bearer token alone (a stolen-but-still-valid
     * access token should not by itself be enough to turn off the account's second factor). Exposed
     * over HTTP as {@code POST /auth/2fa/disable} (bearer-gated).
     *
     * @param authUserId the already-authenticated account disabling two-factor authentication
     * @param password the account's current password, re-verified before disabling
     * @throws InvalidCredentialsException if no account exists under {@code authUserId} or {@code password} doesn't match
     * @throws de.lino.cloud.api.security.database.DatabaseClientException if updating the account fails
     * @throws de.lino.cloud.api.security.keys.KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void disableTwoFactor(@NonNull final String authUserId, final char @NonNull [] password);

    /**
     * Completes a login previously left pending by {@link #login} returning {@link
     * LoginResult#requiresTwoFactor()}: verifies {@code code} against the account's live TOTP
     * secret and, on success, issues a real {@link AuthTokens} pair exactly the way a non-2FA {@link
     * #login} would (including firing the same {@code LOGIN_SUCCESS} audit event {@link #login}
     * itself fires for a completed non-2FA login). {@code pendingTwoFactorToken} is only consumed
     * (deleted) once {@code code} actually verifies - a mistyped code may be retried within the
     * pending token's short validity window rather than forcing the caller back to a fresh password
     * login. Exposed over HTTP as {@code POST /auth/2fa/login} (unauthenticated - the caller has no
     * real access token yet by definition).
     *
     * @param pendingTwoFactorToken the token returned by {@link #login}'s {@link
     *     LoginResult#pendingTwoFactorToken()}
     * @param code the current TOTP code, produced by the caller's authenticator app
     * @return a freshly issued {@link AuthTokens} pair asserting the pending login's account id
     * @throws InvalidVerificationCodeException if {@code pendingTwoFactorToken} doesn't exist, has
     *     expired, {@code code} doesn't verify, or (defense-in-depth) the account itself no longer
     *     exists or no longer has two-factor authentication enabled
     * @throws DatabaseClientException if persisting the issued tokens fails
     * @throws KeyWrapException if the new tokens' data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    AuthTokens completeTwoFactorLogin(@NonNull final String pendingTwoFactorToken, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException;

    /**
     * Validates a JWT from the {@code Authorization} header, returning the embedded user id.
     *
     * @param jwt the encoded JWT to validate
     * @return the user id embedded in {@code jwt} at signing time
     * @throws InvalidJwtException if the token's signature is invalid, it is malformed, or it has expired
     */
    @NonNull
    String validate(@NonNull final String jwt) throws InvalidJwtException;

    /**
     * Lists every currently-registered account.
     *
     * @return every registered {@link AuthUser}, in no particular guaranteed order
     */
    @NonNull
    List<AuthUser> getAuthUsers();

    /**
     * Looks up a single {@link AuthUser} by its plain id - the counterpart to {@link
     * #getAuthUsers()}'s "list everything" for the common case of resolving one already-known id
     * (e.g. {@link de.lino.cloud.api.user.ICloudUser#getAuthUserId()}) back to the account it
     * belongs to.
     *
     * @param authUserId the {@link AuthUser#getId()} to look up
     * @return the matching {@link AuthUser}, or {@link Optional#empty()} if no account exists under that id
     */
    @NonNull
    Optional<AuthUser> getAuthUser(@NonNull final String authUserId);

    /**
     * Grants or revokes {@link AuthUser#isAdmin()} for {@code authUserId} - the only writer of
     * that field anywhere in this codebase; never reachable via any REST route, only from a
     * terminal {@code Command} run by the operator, to avoid a privilege-escalation hole.
     *
     * @param authUserId the account to grant/revoke admin on
     * @param isAdmin the new admin flag value
     * @throws IllegalArgumentException if no account exists under {@code authUserId}
     */
    void setAdmin(@NonNull final String authUserId, final boolean isAdmin);

    /**
     * Starts a password reset for the account under {@code emailAddress}: if (and only if) an
     * {@link AuthUser} exists under it, persists a pending reset (a freshly generated
     * verification code, valid for a short window) and e-mails that code to {@code
     * emailAddress}. Does <b>not</b> change the password yet, and does <b>not</b> reveal whether
     * an account exists under {@code emailAddress} - this method returns identically either way,
     * the same "don't leak" idiom {@link #login} uses, since confirming account existence here
     * would hand an attacker a credential-stuffing oracle. Exposed over HTTP as {@code
     * POST /auth/reset-password} by {@code cloud-driver-plugin}'s {@code DefaultRestFactory}
     * whenever it's constructed with an {@code AuthService}.
     *
     * @param emailAddress the account's identifying e-mail address
     * @throws DatabaseClientException if persisting the pending reset fails
     * @throws KeyWrapException if the pending reset's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void requestPasswordReset(@NonNull final String emailAddress) throws DatabaseClientException, KeyWrapException;

    /**
     * Completes a password reset previously started by {@link #requestPasswordReset}: verifies
     * {@code code} against the pending reset stored under {@code emailAddress} (and that it
     * hasn't expired), then replaces the account's password with {@code newPassword} and returns
     * a signed JWT for it - the caller goes straight from a confirmed code into an authenticated
     * session, the same way {@link #confirmRegistration}'s result is used. Exposed over HTTP as
     * {@code POST /auth/reset-password/confirm}.
     *
     * @param emailAddress the e-mail address {@link #requestPasswordReset} was called with
     * @param code the verification code e-mailed to {@code emailAddress}
     * @param newPassword the caller's chosen new password, hashed before persistence and never itself retained -
     *     must meet the same format requirement documented on {@link #register}'s own {@code rawPassword} parameter
     * @return a freshly issued {@link AuthTokens} pair asserting the account's id
     * @throws InvalidPasswordFormatException if {@code newPassword} doesn't meet that format requirement
     * @throws InvalidVerificationCodeException if there is no pending reset under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match - also thrown (with the
     *     same message) if the pending reset somehow outlives its account, so this method never
     *     distinguishes "bad code" from "account gone" either
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    AuthTokens confirmPasswordReset(@NonNull final String emailAddress, @NonNull final String code, final char @NonNull [] newPassword)
            throws DatabaseClientException, KeyWrapException;

    /**
     * Exchanges a still-valid, not-yet-used refresh token for a fresh {@link AuthTokens} pair,
     * without requiring the caller to log in again with a password - the mechanism a long-running
     * client uses to stay signed in past its access token's 12h lifetime.
     *
     * <p><b>Rotated on every use:</b> {@code refreshToken} is invalidated as part of this call
     * (never usable again, successful or not) and the returned {@link AuthTokens#refreshToken()}
     * is a freshly generated value the caller must persist in its place - limiting how long a
     * stolen-but-unused refresh token remains useful, and (as a side effect) detecting reuse: if
     * two callers ever present the same refresh token, at most one call can win the race to
     * invalidate it first, and the other is rejected.
     *
     * @param refreshToken a refresh token previously returned by {@link #login}/{@link
     *     #confirmRegistration}/{@link #confirmPasswordReset}/a prior call to this method
     * @return a freshly issued {@link AuthTokens} pair asserting the same account id the supplied
     *     refresh token was issued for
     * @throws InvalidRefreshTokenException if {@code refreshToken} doesn't exist, has expired, has
     *     already been used/revoked, or its account no longer exists
     * @throws DatabaseClientException if persisting the rotation fails
     * @throws KeyWrapException if the new refresh token's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    AuthTokens refresh(@NonNull final String refreshToken) throws DatabaseClientException, KeyWrapException;

    /**
     * Best-effort, idempotent invalidation of a single refresh token - the counterpart to {@link
     * #refresh}'s rotation, called on logout so a signed-out client's refresh token can't later be
     * presented to mint a fresh access token without the user having actually logged in again. A
     * no-op (not an error) if {@code refreshToken} doesn't exist or is already revoked/rotated
     * away - logging out with an already-consumed token (e.g. right after a successful {@link
     * #refresh} rotated it) is a completely normal case, not a failure. Exposed over HTTP as
     * {@code POST /auth/logout} by {@code cloud-driver-plugin}'s {@code DefaultRestFactory}
     * whenever it's constructed with an {@code AuthService}.
     *
     * @param refreshToken the token to revoke
     */
    void revokeRefreshToken(@NonNull final String refreshToken);

    /**
     * Starts an e-mail address change for the already-authenticated account {@code authUserId}:
     * checks that no other account already exists under {@code newEmailAddress}, then persists a
     * pending change (a freshly generated verification code, valid for a short window) and
     * e-mails that code to {@code newEmailAddress} itself - proving the caller actually controls
     * the new address before this account ever moves to it. Does <b>not</b> change {@code
     * authUserId}'s e-mail address yet - see {@link #confirmEmailChange}. Unlike {@link
     * #register}, this deliberately confirms whether {@code newEmailAddress} is already taken
     * (via {@link EmailAlreadyRegisteredException}) rather than hiding it - the caller is already
     * an authenticated account holder at this point, not an anonymous visitor, so this isn't a
     * login-enumeration risk the way {@link #requestPasswordReset} has to guard against. Exposed
     * over HTTP as {@code POST /auth/change-email} by {@code cloud-driver-plugin}'s {@code
     * DefaultRestFactory} whenever it's constructed with an {@code AuthService}.
     *
     * @param authUserId the already-authenticated account requesting the change (from its own bearer token, not user input)
     * @param newEmailAddress the address this account would move to on confirmation
     * @throws EmailAlreadyRegisteredException if another account already exists under {@code newEmailAddress}
     * @throws DatabaseClientException if persisting the pending change fails
     * @throws KeyWrapException if the pending change's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void requestEmailChange(@NonNull final String authUserId, @NonNull final String newEmailAddress)
            throws DatabaseClientException, KeyWrapException;

    /**
     * Completes an e-mail change previously started by {@link #requestEmailChange}: verifies
     * {@code code} against the pending change stored under {@code authUserId} (and that it hasn't
     * expired), then replaces the account's e-mail address with the pending change's own {@code
     * newEmailAddress}. Does not return a fresh JWT - a signed token's subject is the account's
     * id, never its e-mail address, so an already-authenticated caller's existing token remains
     * valid across this change. Exposed over HTTP as {@code POST /auth/change-email/confirm}.
     *
     * @param authUserId the already-authenticated account confirming the change (from its own bearer token, not user input)
     * @param code the verification code e-mailed to the pending change's new address
     * @throws InvalidVerificationCodeException if there is no pending change under {@code
     *     authUserId}, it has expired, or {@code code} doesn't match - also thrown (with the same
     *     message) if the pending change somehow outlives its account, so this method never
     *     distinguishes "bad code" from "account gone" either
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void confirmEmailChange(@NonNull final String authUserId, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException;

}
