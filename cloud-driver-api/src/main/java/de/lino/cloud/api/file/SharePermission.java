package de.lino.cloud.api.file;

/**
 * The access level a {@code SharedFileGrant}/{@code SharedFolderGrant} carries, added alongside
 * expiry on top of what was, until then, a strictly
 * read-only sharing model (see {@code SharedFileGrant}'s own historical Javadoc).
 *
 * <p><b>{@link #VIEW} remains the only level a {@code SharedFolderGrant} meaningfully supports in
 * this pass</b> - a grantee can never upload into, or otherwise write to, a shared folder yet
 * (real ownership-attribution questions - whose quota does an uploaded-by-a-grantee file count
 * against? - are deliberately left unresolved rather than guessed at; a folder grant still carries
 * this field, for forward compatibility, but nothing currently branches on a folder grant's own
 * {@link #EDIT} value). {@link #EDIT} is fully implemented for {@code SharedFileGrant} only: a
 * grantee holding an {@link #EDIT} grant on a file may call {@code
 * CloudUserService#replaceFileContent} on it, in addition to everything {@link #VIEW} already
 * allows. Every structural operation (rename, move, delete, restore, re-share) stays strictly
 * owner-only regardless of permission level - a share, at any level, is never a path to changing a
 * file's identity or its place in the account's own folder tree, only (optionally) its content.
 */
public enum SharePermission {

    /** Read-only access - can view/download a shared file, or browse/download a shared folder's contents. The default, and the only level a legacy grant (persisted before this enum existed) is ever treated as. */
    VIEW,

    /** Everything {@link #VIEW} allows, plus (files only, see this enum's own Javadoc) the ability to overwrite the shared file's content via {@code CloudUserService#replaceFileContent}. */
    EDIT

}
