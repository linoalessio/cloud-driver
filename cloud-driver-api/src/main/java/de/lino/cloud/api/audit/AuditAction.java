package de.lino.cloud.api.audit;

/**
 * A security-relevant action worth recording in an {@link AuditEvent} - see that class's Javadoc
 * and {@code architecture/SERVICES.md} item 11 for the full audit-log design. Deliberately only
 * lists actions this codebase actually calls {@link AuditLogService#record} for today (see {@code
 * de.lino.cloud.auth.AuthService}/{@code de.lino.cloud.auth.CloudUserService}'s call sites) rather
 * than every action the original brainstorm named - extend this enum alongside a real new call
 * site, not speculatively ahead of one.
 */
public enum AuditAction {

    /** A successful {@code AuthService#login} call. */
    LOGIN_SUCCESS,

    /** A failed {@code AuthService#login} call - wrong password or no such account (never distinguished, matching {@code login}'s own "don't leak" contract). */
    LOGIN_FAILURE,

    /** A completed {@code AuthService#register}/{@code #confirmRegistration} flow (the account was actually created). */
    REGISTER,

    /** A completed {@code AuthService#confirmPasswordReset} call. */
    PASSWORD_RESET,

    /** A completed {@code AuthService#confirmEmailChange} call. */
    EMAIL_CHANGE,

    /** A {@code CloudUserService#deleteFile} call (moves a file to the trash - see the recycle-bin feature). */
    FILE_DELETE,

    /** A {@code CloudUserService#deleteCloudUser} call (permanently empties and removes an account). */
    ACCOUNT_DELETE,

    /** A {@code CloudUserService#replaceFileContent} call (overwrites a file's content in place - see {@code architecture/MICRO.md} section 2, versioning). */
    FILE_CONTENT_REPLACED,

    /** A {@code CloudUserService#uploadFile} call - added for section 3 (Activity/Audit-Feed, {@code architecture/MICRO.md}), the first pass to instrument this codebase's core file/folder lifecycle operations rather than only deletes/replaces. */
    FILE_UPLOAD,

    /** A {@code CloudUserService#renameFile} call. */
    FILE_RENAME,

    /** A {@code CloudUserService#moveFile} call. */
    FILE_MOVE,

    /** A {@code CloudUserService#restoreFile} call (out of the trash). */
    FILE_RESTORE,

    /** A {@code CloudUserService#createFolder} call. */
    FOLDER_CREATE,

    /** A {@code CloudUserService#updateFolder} call (rename and/or move, in one step). */
    FOLDER_UPDATE,

    /** A {@code CloudUserService#deleteFolder} call (moves a folder to the trash). */
    FOLDER_DELETE,

    /** A {@code CloudUserService#restoreFolder} call (out of the trash). */
    FOLDER_RESTORE

}
