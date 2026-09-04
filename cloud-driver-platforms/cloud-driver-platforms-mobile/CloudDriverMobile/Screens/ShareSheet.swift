import SwiftUI

/// A batch of files/folders being shared (owner side) via `ShareSheet` - wraps `[SelectableEntry]`
/// so it can drive a SwiftUI `.sheet(item:)`; a single-item share (from a row's own "..." menu) is
/// just a one-element `entries` array, which keeps that case's existing "who is this shared with,
/// with revoke" behavior (see `ShareSheet.loadShares`/`revoke` below - only meaningful for exactly
/// one item).
struct ShareTargets: Identifiable {
    let entries: [SelectableEntry]
    var id: String { entries.map(\.id).joined(separator: ",") }

    var displayName: String {
        entries.count == 1 ? entries[0].displayName : "\(entries.count) items"
    }
}

/// Owner-side sharing: grant another account's read-only access to one or more files/folders at
/// once, and - only when exactly one item is targeted, since a revoke list has no single meaning
/// across multiple items with potentially different grantee sets - see who it's currently shared
/// with and revoke that access. Fully self-contained (its own local loading/error/list state)
/// rather than routed through `AppViewModel`'s global `busy` guard - the same reasoning
/// cloud-driver-platforms-desktop's own `ShareDialog` documents: tying a modal's own actions to a
/// screen-wide busy flag would disable the rest of the app for no reason while it's simply open.
struct ShareSheet: View {
    @ObservedObject var viewModel: AppViewModel
    let targets: ShareTargets
    @Environment(\.dismiss) private var dismiss

    @State private var email = ""
    @State private var grantees: [String] = []
    @State private var isLoading = false
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                ScrollView {
                    VStack(spacing: 16) {
                        CloudCard(
                            icon: "person.badge.plus",
                            iconColor: CloudTheme.iconAccount,
                            title: "Share",
                            subtitle: targets.displayName
                        ) {
                            HStack(spacing: 10) {
                                GlassField {
                                    TextField("Email address", text: $email)
                                        .textContentType(.emailAddress)
                                        .keyboardType(.emailAddress)
                                        .textInputAutocapitalization(.never)
                                        .autocorrectionDisabled()
                                }
                                Button {
                                    share()
                                } label: {
                                    if isSubmitting {
                                        ProgressView().tint(.white)
                                    } else {
                                        Image(systemName: "paperplane.fill")
                                    }
                                }
                                .frame(width: 46, height: 46)
                                .foregroundStyle(.white)
                                .background(CloudTheme.accent.gradient, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                                .opacity(email.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting ? 0.5 : 1)
                                .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty || isSubmitting)
                            }
                            .padding(.horizontal, 16)
                            .padding(.bottom, 16)
                        }

                        // A revoke list has no single meaning across multiple items with
                        // potentially different grantee sets - only shown for a single target.
                        if targets.entries.count == 1 {
                            CloudCard(
                                icon: "person.2.fill",
                                iconColor: CloudTheme.iconStorage,
                                title: "Shared With",
                                subtitle: grantees.isEmpty ? "No one yet" : "\(grantees.count) \(grantees.count == 1 ? "person" : "people")"
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(grantees.indices, id: \.self) { index in
                                        let granteeEmail = grantees[index]
                                        CloudRow(
                                            icon: "person.fill",
                                            iconColor: CloudTheme.iconAccount,
                                            title: granteeEmail,
                                            showDivider: index != grantees.count - 1
                                        ) {
                                            Button {
                                                revoke(granteeEmail)
                                            } label: {
                                                Image(systemName: "xmark.circle.fill")
                                                    .foregroundStyle(Color.red.opacity(0.85))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    .padding(16)
                }
                .scrollIndicators(.hidden)

                if isLoading && grantees.isEmpty {
                    ProgressView()
                        .tint(.white)
                }
            }
            .navigationTitle("Share")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Done") { dismiss() }
                }
            }
            .task {
                await loadShares()
            }
            .alert("Error", isPresented: Binding(
                get: { errorMessage != nil },
                set: { isPresented in if !isPresented { errorMessage = nil } }
            )) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    /// Only meaningful for a single target - see the "Shared With" card's own `targets.entries.count == 1` guard.
    private func loadShares() async {
        guard targets.entries.count == 1 else { return }
        isLoading = true
        defer { isLoading = false }
        do {
            switch targets.entries[0] {
            case .file(let file):
                grantees = try await viewModel.client.listFileShares(fileId: file.fileId)
            case .folder(let folder):
                grantees = try await viewModel.client.listFolderShares(folderId: folder.folderId)
            }
        } catch let error as APIError {
            errorMessage = error.errorDescription
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func share() {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        isSubmitting = true
        Task {
            defer { isSubmitting = false }
            do {
                try await viewModel.shareEntries(targets.entries, granteeEmail: trimmed)
                email = ""
                await loadShares()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }

    private func revoke(_ granteeEmail: String) {
        guard targets.entries.count == 1 else { return }
        Task {
            do {
                switch targets.entries[0] {
                case .file(let file):
                    try await viewModel.client.revokeFileShare(fileId: file.fileId, granteeEmail: granteeEmail)
                case .folder(let folder):
                    try await viewModel.client.revokeFolderShare(folderId: folder.folderId, granteeEmail: granteeEmail)
                }
                await loadShares()
            } catch let error as APIError {
                errorMessage = error.errorDescription
            } catch {
                errorMessage = error.localizedDescription
            }
        }
    }
}
