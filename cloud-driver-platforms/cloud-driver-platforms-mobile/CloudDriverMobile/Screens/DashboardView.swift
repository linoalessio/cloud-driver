import SwiftUI

/// The signed-in account-overview tab - email/account id/join date plus s3storage usage, backed by
/// `GET /auth/me` + `GET /cloudUsers/{id}` (see `AppViewModel.refreshAccountInfo`). The mobile
/// counterpart to cloud-driver-platforms-desktop's `DashboardScreen.kt`, scoped down to what this
/// first pass actually tracks - no folder/file counts or sharing stats yet. Styled after Apple's
/// own iCloud.com dashboard (see `Theme.swift`) - an "Account" widget, a "Storage" widget with a
/// gradient usage bar, mirroring the reference's own card composition.
struct DashboardView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var showingChangeEmail = false
    @State private var showingResetPasswordConfirmation = false
    /// Drives the storage bar's fill-up entrance: the bar renders at zero width until this flips
    /// in `.onAppear`, so the fill visibly grows to its real fraction instead of appearing static.
    @State private var storageBarRevealed = false

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
                        .cardEntrance(index: 0)

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
                                            .frame(width: proxy.size.width * (storageBarRevealed ? storageFraction : 0))
                                            .shimmer()
                                            .clipShape(Capsule())
                                    }
                                }
                                .frame(height: 10)
                                // Springs from zero on first appearance, and re-springs whenever a
                                // refresh changes the real fraction.
                                .animation(.spring(response: 0.9, dampingFraction: 0.85), value: storageBarRevealed)
                                .animation(.spring(response: 0.9, dampingFraction: 0.85), value: storageFraction)
                                .onAppear { storageBarRevealed = true }
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }
                        .cardEntrance(index: 1)

                        CloudCard(
                            icon: "list.bullet.rectangle",
                            iconColor: CloudTheme.accent,
                            title: "Recent Activity"
                        ) {
                            VStack(spacing: 0) {
                                if !viewModel.isAccountActivityAvailable {
                                    Text("Not available on this server")
                                        .foregroundStyle(CloudTheme.textSecondary)
                                        .padding(16)
                                } else if viewModel.accountActivity.isEmpty {
                                    Text(viewModel.busy ? "Loading\u{2026}" : "No activity yet.")
                                        .foregroundStyle(CloudTheme.textSecondary)
                                        .padding(16)
                                } else {
                                    ForEach(Array(viewModel.accountActivity.prefix(5).enumerated()), id: \.element.id) { index, entry in
                                        ActivityRow(entry: entry, showDivider: true)
                                    }
                                }
                                NavigationLink {
                                    ActivityFeedView(viewModel: viewModel)
                                } label: {
                                    HStack {
                                        Text("View All")
                                        Spacer()
                                        Image(systemName: "chevron.right")
                                            .font(.caption)
                                    }
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 11)
                                }
                                .foregroundStyle(CloudTheme.accent)
                            }
                        }
                        .cardEntrance(index: 2)

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
                                .buttonStyle(CloudPressStyle())
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
                                .buttonStyle(CloudPressStyle())
                            }
                        }
                        .cardEntrance(index: 3)

                        // Chrome on the label (not the Button) so CloudPressStyle scales the
                        // whole pill - same shape PrimaryButton uses.
                        Button(role: .destructive) {
                            viewModel.logout()
                        } label: {
                            Text("Sign Out")
                                .font(CloudTheme.headline(.body))
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 14)
                                .foregroundStyle(.white)
                                .background(Color.red.opacity(0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        }
                        .buttonStyle(CloudPressStyle(scale: 0.97))
                        .cardEntrance(index: 4)
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
            viewModel.loadRecentActivity()
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
