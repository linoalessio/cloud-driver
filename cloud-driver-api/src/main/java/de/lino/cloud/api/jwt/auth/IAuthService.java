package de.lino.cloud.api.jwt.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.EmailAlreadyRegisteredException;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.jwt.InvalidVerificationCodeException;
import de.lino.cloud.api.jwt.user.AuthUser;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import lombok.NonNull;

import java.util.List;

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
     * @param rawPassword the new account's plaintext password, hashed before persistence and never itself retained
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

    @NonNull
    List<AuthUser> getAuthUsers();

}
