package de.lino.cloud.api.file.exception;

/**
 * Thrown by {@code CloudUserService#resolvePublicFileLink} when a token doesn't resolve to a
 * currently-usable public share link - missing,
 * expired, or pointing at a file its owner has since trashed/deleted. One message for all three
 * cases, deliberately - the same "don't leak which" idiom {@code InvalidVerificationCodeException}
 * already uses, since distinguishing "wrong token" from "expired token" would hand an
 * unauthenticated caller free information about whether a token they don't have ever existed.
 * Translated to a {@code 404} by {@code DefaultRestFactory}, matching how every other "don't
 * confirm existence" case in this codebase is already surfaced.
 */
public final class PublicShareLinkInvalidException extends RuntimeException {

    public PublicShareLinkInvalidException() {
        super("This link is invalid or has expired");
    }

}
