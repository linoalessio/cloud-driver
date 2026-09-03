import SwiftUI

/// A file or folder being shared (owner side) via `ShareSheet`.
enum ShareTarget: Identifiable {
    case file(StoredFileSummaryResponse)
    case folder(FolderResponse)

    var id: String {
        switch self {
        case .file(let file): return "share-file-\(file.fileId)"
        case .folder(let folder): return "share-folder-\(folder.folderId)"
        }
    }

    var displayName: String {
        switch self {
        case .file(let file): return file.fileName
        case .folder(let folder): return folder.name
        }
    }
}

/// Owner-side sharing: grant/revoke another account's read-only access to a file or folder, and
/// see who it's currently shared with. Fully self-contained (its own local loading/error/list
/// state) rather than routed through `AppViewModel`'s global `busy` guard - the same reasoning
/// cloud-driver-platforms-desktop's own `ShareDialog` documents: tying a modal's own actions to a
/// screen-wide busy flag would disable the rest of the app for no reason while it's simply open.
struct ShareSheet: View {
    @ObservedObject var viewModel: AppViewModel
    let target: ShareTarget
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
                            subtitle: target.displayName
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

    private func loadShares() async {
        isLoading = true
        defer { isLoading = false }
        do {
            switch target {
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
                switch target {
                case .file(let file):
                    try await viewModel.client.shareFile(fileId: file.fileId, granteeEmail: trimmed)
                case .folder(let folder):
                    try await viewModel.client.shareFolder(folderId: folder.folderId, granteeEmail: trimmed)
                }
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
        Task {
            do {
                switch target {
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
