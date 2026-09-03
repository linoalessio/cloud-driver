import SwiftUI

/// Two-step "change your account email" flow, presented from `DashboardView` - request a code
/// (`AppViewModel.requestEmailChange`), then confirm it (`AppViewModel.confirmEmailChange`).
/// Driven directly by `AppViewModel.pendingEmailChangeAddress` (non-`nil` once step one
/// succeeds), the same "no separate step-tracking state" shape cloud-driver-platforms-desktop's
/// own `ChangeEmailDialog` uses.
struct ChangeEmailSheet: View {
    @ObservedObject var viewModel: AppViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var newEmail = ""
    @State private var code = ""
    /// Tracks whether we've actually reached step two at least once - `pendingEmailChangeAddress`
    /// going back to `nil` only means "successfully confirmed" if we got here first; otherwise
    /// it's just this sheet's very first, still-unstarted composition.
    @State private var enteredCodeStep = false

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                VStack(spacing: 16) {
                    if let pending = viewModel.pendingEmailChangeAddress {
                        Text("Enter the 6-digit code sent to \(pending).")
                            .foregroundStyle(CloudTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 32)

                        GlassField {
                            TextField("Verification code", text: $code)
                                .keyboardType(.numberPad)
                                .multilineTextAlignment(.center)
                                .font(.title2.monospaced())
                        }

                        PrimaryButton(title: "Confirm", busy: viewModel.busy, disabled: code.count != 6) {
                            viewModel.confirmEmailChange(code: code)
                        }
                    } else {
                        Text("Enter your new email address. We'll send a code there to confirm it.")
                            .foregroundStyle(CloudTheme.textSecondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 32)

                        GlassField {
                            TextField("New email", text: $newEmail)
                                .textContentType(.emailAddress)
                                .keyboardType(.emailAddress)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                        }

                        PrimaryButton(title: "Send Code", busy: viewModel.busy, disabled: newEmail.isEmpty) {
                            viewModel.requestEmailChange(newEmailAddress: newEmail)
                        }
                    }

                    Spacer()
                }
                .padding(.horizontal, 24)
            }
            .navigationTitle("Change Email")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        viewModel.cancelEmailChangeRequest()
                        dismiss()
                    }
                }
            }
        }
        .onChange(of: viewModel.pendingEmailChangeAddress) { _, newValue in
            if newValue != nil {
                enteredCodeStep = true
            } else if enteredCodeStep {
                // pendingEmailChangeAddress just went back to nil after we'd reached step two -
                // that only happens on a successful confirm (a failed one leaves it untouched).
                dismiss()
            }
        }
    }
}
