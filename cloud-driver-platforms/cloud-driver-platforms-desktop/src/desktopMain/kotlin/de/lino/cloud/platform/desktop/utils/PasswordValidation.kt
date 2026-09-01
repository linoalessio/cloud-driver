package de.lino.cloud.platform.desktop.utils

/** The minimum length [isValidPasswordFormat] accepts for a chosen password. */
private const val MIN_PASSWORD_LENGTH = 8

/**
 * Characters a chosen password may never contain - mirrors `cloud-driver-auth`'s
 * `AuthService#FORBIDDEN_PASSWORD_CHARACTERS` exactly, reimplemented client-side rather than
 * depended on, same "this module never depends on cloud-driver-api/-auth" reasoning
 * `ByteFormat.kt`/`PreviewSupport.kt` already document for their own ports of server-side logic.
 */
private const val FORBIDDEN_PASSWORD_CHARACTERS = ";,:`"

/** The symbol characters counting toward the "at least one symbol" requirement - mirrors `AuthService#ALLOWED_PASSWORD_SYMBOLS`. */
private const val ALLOWED_PASSWORD_SYMBOLS = "!\"#$%&'()*+-./<=>?@[\\]^_{|}~"

/** Human-readable password requirement, shown as helper text under a password field on Register/Reset-password. */
const val PASSWORD_REQUIREMENT_HINT =
    "At least 8 characters, with a number, a lowercase letter, an uppercase letter, and a symbol. Not allowed: ; , : `"

/**
 * Client-side mirror of `cloud-driver-auth`'s `AuthService#requirePasswordFormat` - lets
 * `RegisterScreen`/`ResetPasswordConfirmScreen` reject an invalid password (and show
 * [PASSWORD_REQUIREMENT_HINT] as an error) before ever submitting it, rather than only after a
 * rejected request round-trips back. The server enforces the same rule independently and remains
 * the actual source of truth - this is a UX convenience, not the enforcement point.
 *
 * @return `true` if [password] is at least [MIN_PASSWORD_LENGTH] characters, contains a digit, a
 *     lowercase letter, an uppercase letter, and a symbol from [ALLOWED_PASSWORD_SYMBOLS], and
 *     contains none of [FORBIDDEN_PASSWORD_CHARACTERS]
 */
fun isValidPasswordFormat(password: String): Boolean {
    if (password.length < MIN_PASSWORD_LENGTH) return false

    var hasDigit = false
    var hasLowercase = false
    var hasUppercase = false
    var hasSymbol = false

    for (character in password) {
        if (character in FORBIDDEN_PASSWORD_CHARACTERS) return false
        when {
            character.isDigit() -> hasDigit = true
            character.isLowerCase() -> hasLowercase = true
            character.isUpperCase() -> hasUppercase = true
            character in ALLOWED_PASSWORD_SYMBOLS -> hasSymbol = true
        }
    }

    return hasDigit && hasLowercase && hasUppercase && hasSymbol
}
