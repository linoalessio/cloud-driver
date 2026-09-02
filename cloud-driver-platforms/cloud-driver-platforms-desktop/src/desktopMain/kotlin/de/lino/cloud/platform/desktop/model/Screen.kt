package de.lino.cloud.platform.desktop.model

/** Every screen [AppViewModel.screen] can be, before and after login. */
sealed interface Screen {
    /** Before login: sign in with an existing account. */
    data object Login : Screen

    /** Before login: step one of registration - enter email/password. */
    data object Register : Screen

    /** Before login: step two of registration - enter the e-mailed code. */
    data class RegisterConfirm(val email: String) : Screen

    /** Before login: step one of a password reset - enter the account's email. */
    data object ResetPasswordRequest : Screen

    /** Before login: step two of a password reset - enter the e-mailed code plus a new password. */
    data class ResetPasswordConfirm(val email: String) : Screen

    /**
     * Between password verification and a real session: the matched account has two-factor
     * authentication enabled (item 12, see `architecture/SERVICES.md`) - enter the current TOTP
     * code from an authenticator app. [pendingToken] is what [AppViewModel.completeTwoFactorLogin]
     * presents back alongside the code; [email] is only kept for display ("Signed in as ...").
     */
    data class TwoFactorLogin(val pendingToken: String, val email: String) : Screen

    /** After login: the file/folder browser. */
    data object Browser : Screen

    /** After login: account overview - email, account id, storage/file/folder stats. */
    data object Dashboard : Screen

    /** After login: the trash - trashed files/folders, restorable back to where they were. */
    data object Trash : Screen

    /** After login: files/folders other accounts have shared with the signed-in account. */
    data object SharedWithMe : Screen

    /** After login: browsing inside a folder reached via a share (added 2026-09-02) - current folder/breadcrumbs tracked on [AppViewModel], the same shape [Browser] uses for the caller's own folders. */
    data object SharedFolderBrowser : Screen

    /** After login: read-only admin panel (registered accounts + audit trail) - only reachable while [AppViewModel.currentUserIsAdmin]. */
    data object Admin : Screen
}
