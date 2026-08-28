package de.lino.cloud.api.jwt.auth;

import de.lino.cloud.api.factory.DataFactory;
import de.lino.cloud.api.jwt.InvalidCredentialsException;
import de.lino.cloud.api.jwt.InvalidJwtException;
import de.lino.cloud.api.security.database.DatabaseClientException;
import de.lino.cloud.api.security.keys.KeyWrapException;
import lombok.NonNull;

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
 * AuthService)} constructor mounts {@code POST /auth/register} automatically whenever an {@code
 * AuthService} is supplied, the same way it already mounts {@code POST /auth/login} - this is
 * now the only way a new account gets created (the earlier operator-run {@code CreateUserCli}
 * has been removed).
 */
public interface IAuthService {

    /**
     * Creates and persists a new user account. Exposed over HTTP as {@code POST /auth/register}
     * by {@code cloud-driver-plugin}'s {@code DefaultRestFactory} whenever it's constructed with
     * an {@code AuthService}.
     *
     * @param username the new account's identifying username (an email address, in the current implementation)
     * @param rawPassword the new account's plaintext password, hashed before persistence and never itself retained
     * @throws de.lino.cloud.api.jwt.EmailAlreadyRegisteredException if an account already exists under {@code username}
     * @throws DatabaseClientException if persisting the new account fails
     * @throws KeyWrapException if the account's data-encryption key cannot be wrapped by the KMS/HSM
     */
    void register(@NonNull final String username, final char @NonNull [] rawPassword) throws DatabaseClientException, KeyWrapException;

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

}
