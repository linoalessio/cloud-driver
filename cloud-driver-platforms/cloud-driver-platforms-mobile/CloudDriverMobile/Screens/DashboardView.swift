import SwiftUI

/// The signed-in account-overview tab - email/account id/join date plus storage usage, backed by
/// `GET /auth/me` + `GET /cloudUsers/{id}` (see `AppViewModel.refreshAccountInfo`). The mobile
/// counterpart to cloud-driver-platforms-desktop's `DashboardScreen.kt`, scoped down to what this
/// first pass actually tracks - no folder/file counts or sharing stats yet. Styled after Apple's
/// own iCloud.com dashboard (see `Theme.swift`) - an "Account" widget, a "Storage" widget with a
/// gradient usage bar, mirroring the reference's own card composition.
struct DashboardView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var showingChangeEmail = false
    @State private var showingResetPasswordConfirmation = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter
    }()

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                ScrollView {
                    VStack(spacing: 16) {
                        CloudCard(
                            icon: "person.fill",
                            iconColor: CloudTheme.iconAccount,
                            title: viewModel.currentUserEmail ?? "Account",
                            subtitle: viewModel.currentUserIsAdmin ? "Administrator" : nil
                        ) {
                            VStack(spacing: 0) {
                                CloudFieldRow(label: "Email", value: viewModel.currentUserEmail ?? "-")
                                CloudFieldRow(label: "Account ID", value: viewModel.currentUserId ?? "-")
                                CloudFieldRow(label: "Joined", value: joinedText, showDivider: false)
                            }
                        }

                        CloudCard(
                            icon: "chart.pie.fill",
                            iconColor: CloudTheme.iconStorage,
                            title: "Storage",
                            subtitle: storageText
                        ) {
                            VStack(alignment: .leading, spacing: 8) {
                                GeometryReader { proxy in
                                    ZStack(alignment: .leading) {
                                        Capsule()
                                            .fill(Color.white.opacity(0.1))
                                        Capsule()
                                            .fill(CloudTheme.iconStorage.gradient)
                                            .frame(width: proxy.size.width * storageFraction)
                                    }
                                }
                                .frame(height: 10)
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }

                        CloudCard(
                            icon: "gearshape.fill",
                            iconColor: CloudTheme.iconAdmin,
                            title: "Account Settings"
                        ) {
                            VStack(spacing: 0) {
                                Button {
                                    showingResetPasswordConfirmation = true
                                } label: {
                                    CloudRow(icon: "key.fill", iconColor: CloudTheme.accent, title: "Reset Password") {
                                        Image(systemName: "chevron.right")
                                            .font(.caption)
                                            .foregroundStyle(CloudTheme.textSecondary)
                                    }
                                }
                                .buttonStyle(.plain)
                                .disabled(viewModel.currentUserEmail == nil)

                                Button {
                                    showingChangeEmail = true
                                } label: {
                                    CloudRow(icon: "envelope.fill", iconColor: CloudTheme.accent, title: "Change Email", showDivider: false) {
                                        Image(systemName: "chevron.right")
                                            .font(.caption)
                                            .foregroundStyle(CloudTheme.textSecondary)
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }

                        Button(role: .destructive) {
                            viewModel.logout()
                        } label: {
                            Text("Sign Out")
                                .font(CloudTheme.headline(.body))
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(Color.red.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .padding(16)
                }
                .scrollIndicators(.hidden)
                .refreshable {
                    viewModel.loadAccountInfo()
                }
            }
            .navigationTitle("Dashboard")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .task {
            viewModel.loadAccountInfo()
        }
        .sheet(isPresented: $showingChangeEmail) {
            ChangeEmailSheet(viewModel: viewModel)
        }
        .confirmationDialog(
            "Send a password reset code to \(viewModel.currentUserEmail ?? "your email")?",
            isPresented: $showingResetPasswordConfirmation,
            titleVisibility: .visible
        ) {
            Button("Send Code") {
                if let email = viewModel.currentUserEmail {
                    viewModel.requestPasswordReset(email: email)
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("You'll be asked to enter the code and choose a new password.")
        }
    }

    private var joinedText: String {
        guard let millis = viewModel.currentUserCreatedAtEpochMillis else { return "-" }
        return Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(millis) / 1000))
    }

    private var storageText: String {
        guard let used = viewModel.currentUserUploadedBytes,
              let limit = viewModel.currentUserMaxBytesToUpload else { return "-" }
        return "\(formatBytes(used)) of \(formatBytes(limit)) used"
    }

    private var storageFraction: Double {
        guard let used = viewModel.currentUserUploadedBytes,
              let limit = viewModel.currentUserMaxBytesToUpload,
              limit > 0 else { return 0 }
        return min(Double(used) / Double(limit), 1)
    }
}
