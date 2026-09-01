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

    /** After login: the file/folder browser. */
    data object Browser : Screen

    /** After login: account overview - email, account id, storage/file/folder stats. */
    data object Dashboard : Screen
}
