package de.lino.cloud.api.jwt.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidPasswordFormatException;
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
 * the account and returns a JWT - the same shape {@link #login} does. This guards against
 * accounts being created under an e-mail address the registrant doesn't actually control.
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
     * @return a freshly signed JWT asserting the newly created account's id
     * @throws InvalidVerificationCodeException if there is no pending registration under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match
     * @throws DatabaseClientException if creating the account fails
     * @throws KeyWrapException if the new account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    String confirmRegistration(@NonNull final String emailAddress, @NonNull final String code)
            throws DatabaseClientException, KeyWrapException;

    /**
     * Verifies {@code username}/{@code rawPassword}, returning a signed JWT on success.
     *
     * @param username the account's identifying username (an email address, in the current implementation) to verify
     * @param rawPassword the plaintext password to verify against the stored hash
     * @return a freshly signed JWT asserting the matched account's id
     * @throws InvalidCredentialsException if the username doesn't exist or the password doesn't match
     */
    @NonNull
    String login(@NonNull final String username, final char @NonNull [] rawPassword);

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
     * @return a freshly signed JWT asserting the account's id
     * @throws InvalidPasswordFormatException if {@code newPassword} doesn't meet that format requirement
     * @throws InvalidVerificationCodeException if there is no pending reset under {@code
     *     emailAddress}, it has expired, or {@code code} doesn't match - also thrown (with the
     *     same message) if the pending reset somehow outlives its account, so this method never
     *     distinguishes "bad code" from "account gone" either
     * @throws DatabaseClientException if updating the account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    @NonNull
    String confirmPasswordReset(@NonNull final String emailAddress, @NonNull final String code, final char @NonNull [] newPassword)
            throws DatabaseClientException, KeyWrapException;

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
