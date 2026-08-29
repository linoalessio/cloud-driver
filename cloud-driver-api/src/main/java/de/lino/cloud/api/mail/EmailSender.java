package de.lino.cloud.api.mail;

/**
 * Sends a single plain-text e-mail. The contract lives here, dependency-free, so {@code
 * cloud-driver-auth}'s {@code AuthService} (which sends the e-mail-verification code as part of
 * {@code de.lino.cloud.api.jwt.auth.IAuthService#register}) can depend on the contract without
 * pulling in whichever mail library a concrete implementation happens to use - the same
 * "contract in {@code cloud-driver-api}, concrete implementation(s) elsewhere" shape as {@link
 * de.lino.cloud.api.security.password.PasswordHasher}/{@code KeyEncryptionService}.
 */
public interface EmailSender {

    /**
     * Sends a plain-text e-mail. Implementations may block the calling thread for the duration
     * of the send.
     *
     * @param toAddress the recipient's e-mail address
     * @param subject the e-mail subject line
     * @param body the plain-text e-mail body
     * @throws EmailDeliveryException if the e-mail could not be sent
     */
    void send(String toAddress, String subject, String body) throws EmailDeliveryException;

}
