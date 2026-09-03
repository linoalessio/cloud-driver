import SwiftUI

/// Mirrors cloud-driver-platforms-desktop's client-side password-format hint (`PasswordValidation.kt`)
/// - the server is still the real enforcement point (see cloud-driver's `AuthService#requirePasswordFormat`);
/// this only avoids an obviously-doomed round trip.
private let passwordRequirementHint = "At least 8 characters, with an uppercase letter, lowercase letter, digit, and symbol."

private struct AuthBackdrop<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        ZStack {
            CloudTheme.backgroundGradient
            ScrollView {
                content
                    .padding(.horizontal, 24)
            }
        }
    }
}

struct LoginView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var email = ""
    @State private var password = ""

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 20) {
                Spacer(minLength: 56)

                RoundedRectangle(cornerRadius: 20, style: .continuous)
                    .fill(CloudTheme.accent.gradient)
                    .frame(width: 72, height: 72)
                    .overlay {
                        Image(systemName: "cloud.fill")
                            .font(.system(size: 32, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                Text("Cloud Driver")
                    .font(CloudTheme.headline(.largeTitle))
                    .foregroundStyle(CloudTheme.textPrimary)

                VStack(spacing: 12) {
                    GlassField {
                        TextField("Email", text: $email)
                            .textContentType(.emailAddress)
                            .keyboardType(.emailAddress)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    GlassField {
                        SecureField("Password", text: $password)
                            .textContentType(.password)
                    }
                }

                PrimaryButton(title: "Sign In", busy: viewModel.busy, disabled: email.isEmpty || password.isEmpty) {
                    viewModel.login(email: email, password: password)
                }

                Button("Forgot password?") {
                    viewModel.screen = .resetPasswordRequest
                }
                .foregroundStyle(CloudTheme.textSecondary)
                .font(.footnote)

                Spacer(minLength: 40)

                Button("Create an account") {
                    viewModel.screen = .register
                }
                .foregroundStyle(CloudTheme.accent)
                .padding(.bottom, 24)
            }
        }
    }
}

struct RegisterView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var email = ""
    @State private var password = ""
    @State private var confirmPassword = ""

    private var passwordsMatch: Bool { !password.isEmpty && password == confirmPassword }

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 16) {
                Text("Create your account")
                    .font(CloudTheme.headline(.title))
                    .foregroundStyle(CloudTheme.textPrimary)
                    .padding(.top, 40)

                GlassField {
                    TextField("Email", text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                GlassField {
                    SecureField("Password", text: $password)
                        .textContentType(.newPassword)
                }
                Text(passwordRequirementHint)
                    .font(.caption)
                    .foregroundStyle(CloudTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                GlassField {
                    SecureField("Confirm password", text: $confirmPassword)
                        .textContentType(.newPassword)
                }

                PrimaryButton(title: "Continue", busy: viewModel.busy, disabled: email.isEmpty || !passwordsMatch) {
                    viewModel.register(email: email, password: password)
                }

                Button("Already have an account? Sign in") {
                    viewModel.screen = .login
                }
                .foregroundStyle(CloudTheme.accent)
                .font(.footnote)
                .padding(.bottom, 40)
            }
        }
    }
}

struct ConfirmRegistrationView: View {
    @ObservedObject var viewModel: AppViewModel
    let email: String
    @State private var code = ""

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 16) {
                Spacer(minLength: 56)
                Text("Check your email")
                    .font(CloudTheme.headline(.title))
                    .foregroundStyle(CloudTheme.textPrimary)
                Text("Enter the 6-digit code we sent to \(email).")
                    .foregroundStyle(CloudTheme.textSecondary)
                    .multilineTextAlignment(.center)

                GlassField {
                    TextField("Verification code", text: $code)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospaced())
                }

                PrimaryButton(title: "Create account", busy: viewModel.busy, disabled: code.count != 6) {
                    viewModel.confirmRegistration(email: email, code: code)
                }

                Button("Back") { viewModel.screen = .register }
                    .foregroundStyle(CloudTheme.accent)
                    .font(.footnote)
                Spacer(minLength: 56)
            }
        }
    }
}

struct RequestResetView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var email = ""

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 16) {
                Spacer(minLength: 56)
                Text("Reset your password")
                    .font(CloudTheme.headline(.title))
                    .foregroundStyle(CloudTheme.textPrimary)
                Text("Enter your account email and we'll send you a reset code.")
                    .foregroundStyle(CloudTheme.textSecondary)
                    .multilineTextAlignment(.center)

                GlassField {
                    TextField("Email", text: $email)
                        .textContentType(.emailAddress)
                        .keyboardType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }

                PrimaryButton(title: "Send code", busy: viewModel.busy, disabled: email.isEmpty) {
                    viewModel.requestPasswordReset(email: email)
                }

                Button("Back to sign in") { viewModel.screen = .login }
                    .foregroundStyle(CloudTheme.accent)
                    .font(.footnote)
                Spacer(minLength: 56)
            }
        }
    }
}

struct ResetPasswordConfirmView: View {
    @ObservedObject var viewModel: AppViewModel
    let email: String
    @State private var code = ""
    @State private var newPassword = ""
    @State private var confirmPassword = ""

    private var passwordsMatch: Bool { !newPassword.isEmpty && newPassword == confirmPassword }

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 16) {
                Text("Enter your reset code")
                    .font(CloudTheme.headline(.title))
                    .foregroundStyle(CloudTheme.textPrimary)
                    .padding(.top, 40)
                Text("Check \(email) for the 6-digit code.")
                    .foregroundStyle(CloudTheme.textSecondary)

                GlassField {
                    TextField("Verification code", text: $code)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospaced())
                }
                GlassField {
                    SecureField("New password", text: $newPassword)
                        .textContentType(.newPassword)
                }
                Text(passwordRequirementHint)
                    .font(.caption)
                    .foregroundStyle(CloudTheme.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                GlassField {
                    SecureField("Confirm new password", text: $confirmPassword)
                        .textContentType(.newPassword)
                }

                PrimaryButton(title: "Reset password", busy: viewModel.busy, disabled: code.count != 6 || !passwordsMatch) {
                    viewModel.confirmPasswordReset(email: email, code: code, newPassword: newPassword)
                }

                Button("Back to sign in") { viewModel.screen = .login }
                    .foregroundStyle(CloudTheme.accent)
                    .font(.footnote)
                    .padding(.bottom, 40)
            }
        }
    }
}

struct TwoFactorView: View {
    @ObservedObject var viewModel: AppViewModel
    let pendingToken: String
    let email: String
    @State private var code = ""

    var body: some View {
        AuthBackdrop {
            VStack(spacing: 16) {
                Spacer(minLength: 56)
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(CloudTheme.iconAdmin.gradient)
                    .frame(width: 64, height: 64)
                    .overlay {
                        Image(systemName: "lock.shield.fill")
                            .font(.system(size: 26, weight: .semibold))
                            .foregroundStyle(.white)
                    }
                Text("Two-factor authentication")
                    .font(CloudTheme.headline(.title))
                    .foregroundStyle(CloudTheme.textPrimary)
                Text("Enter the 6-digit code from your authenticator app.")
                    .foregroundStyle(CloudTheme.textSecondary)
                    .multilineTextAlignment(.center)

                GlassField {
                    TextField("Code", text: $code)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.center)
                        .font(.title2.monospaced())
                }

                PrimaryButton(title: "Verify", busy: viewModel.busy, disabled: code.count != 6) {
                    viewModel.completeTwoFactorLogin(pendingToken: pendingToken, email: email, code: code)
                }

                Button("Back to sign in") { viewModel.screen = .login }
                    .foregroundStyle(CloudTheme.accent)
                    .font(.footnote)
                Spacer(minLength: 56)
            }
        }
    }
}
