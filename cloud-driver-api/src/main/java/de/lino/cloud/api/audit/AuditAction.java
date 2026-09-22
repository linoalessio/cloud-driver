package de.lino.cloud.api.audit;

/**
 * A security-relevant action worth recording in an {@link AuditEvent} - see that class's Javadoc
 * for the full audit-log design. Deliberately only
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

    /** A {@code CloudUserService#replaceFileContent} call (overwrites a file's content in place - see the versioning feature). */
    FILE_CONTENT_REPLACED,

    /** A {@code CloudUserService#uploadFile} call - the first pass to instrument this codebase's core file/folder lifecycle operations rather than only deletes/replaces. */
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
    FOLDER_RESTORE,

    /**
     * A {@code CloudUserService#resetCloudUser} call - every file and folder an account owns,
     * destroyed outright, bypassing the trash. Reachable only from the operator console.
     */
    ACCOUNT_RESET,

    /**
     * An {@code AuthService#setAdmin} call granting the admin flag.
     *
     * <p>The single most security-relevant mutation this system has, and the only privilege
     * escalation path it contains - deliberately unreachable from any REST route, so the operator
     * console is the only place it can happen. It previously left no trace at all, while a folder
     * rename left one.
     */
    ADMIN_GRANT,

    /** An {@code AuthService#setAdmin} call revoking the admin flag - see {@link #ADMIN_GRANT}. */
    ADMIN_REVOKE,

    /** An {@code AuthService#setSuspended} call locking an account out without destroying it. */
    ACCOUNT_SUSPEND,

    /** An {@code AuthService#setSuspended} call lifting a suspension - see {@link #ACCOUNT_SUSPEND}. */
    ACCOUNT_UNSUSPEND,

    /**
     * A system command run from the operator console through {@code DispatchCommand} - an
     * arbitrary executable launched as the server process itself, and therefore the widest
     * action the console can take. Recorded before the process starts, so a command that never
     * returns still leaves an entry.
     */
    SYSTEM_COMMAND_DISPATCH,

    /**
     * An object-storage reconciliation that deleted objects - every bucket object no
     * {@code StoredFile} row referenced, removed. The only destructive action in this system that
     * operates on object storage rather than on rows, and the only one no service method owns:
     * the reconciliation is the operator command itself, so it is recorded there.
     */
    OBJECT_STORAGE_PURGE

}
