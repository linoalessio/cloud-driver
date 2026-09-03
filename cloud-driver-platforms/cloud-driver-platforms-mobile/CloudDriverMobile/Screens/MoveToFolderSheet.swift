import SwiftUI

/// A file or folder awaiting a destination in `MoveToFolderSheet`.
enum MoveTarget: Identifiable {
    case file(StoredFileSummaryResponse)
    case folder(FolderResponse)

    var id: String {
        switch self {
        case .file(let file): return "move-file-\(file.fileId)"
        case .folder(let folder): return "move-folder-\(folder.folderId)"
        }
    }

    var displayName: String {
        switch self {
        case .file(let file): return file.fileName
        case .folder(let folder): return folder.name
        }
    }

    /// The folder id this target itself refers to, if it is a folder - excluded from the list of
    /// destinations one level down so a folder is never trivially offered as its own new parent.
    /// The server's own cycle check (409) is still the real guard against a deeper cycle (moving
    /// into a descendant reached via further navigation) - this only avoids the obviously-doomed
    /// single-tap case.
    var ownFolderId: String? {
        if case .folder(let folder) = self { return folder.folderId }
        return nil
    }
}

/// A folder-picker sheet for moving a file or folder to a new parent - navigates the caller's own
/// folder tree independently of `AppViewModel`'s main browser state (its own local `@State`), the
/// same self-contained shape `SharedFolderBrowserView`/`ShareSheet` use. Confirming calls
/// `AppViewModel.moveFile`/`moveFolder`, which refreshes the main browser listing afterwards.
struct MoveToFolderSheet: View {
    @ObservedObject var viewModel: AppViewModel
    let target: MoveTarget
    @Environment(\.dismiss) private var dismiss

    @State private var currentFolderId: String?
    @State private var currentFolderName = "Home"
    @State private var path: [(id: String?, name: String)] = [(nil, "Home")]
    @State private var folders: [FolderResponse] = []
    @State private var isLoading = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                VStack(spacing: 0) {
                    ScrollView {
                        CloudCard(
                            icon: "folder.fill",
                            iconColor: CloudTheme.iconFolder,
                            title: currentFolderName,
                            subtitle: "Choose a destination"
                        ) {
                            VStack(spacing: 0) {
                                if folders.isEmpty && !isLoading {
                                    Text("No subfolders here")
                                        .foregroundStyle(CloudTheme.textSecondary)
                                        .padding(16)
                                }
                                ForEach(visibleFolders.indices, id: \.self) { index in
                                    let folder = visibleFolders[index]
                                    Button {
                                        open(folder)
                                    } label: {
                                        CloudRow(
                                            icon: "folder.fill",
                                            iconColor: CloudTheme.iconFolder,
                                            title: folder.name,
                                            showDivider: index != visibleFolders.count - 1
                                        ) {
                                            Image(systemName: "chevron.right")
                                                .font(.caption)
                                                .foregroundStyle(CloudTheme.textSecondary)
                                        }
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                        .padding(16)
                    }
                    .scrollIndicators(.hidden)

                    Button {
                        confirmMove()
                    } label: {
                        Text("Move Here")
                            .font(CloudTheme.headline(.body))
                            .frame(maxWidth: .infinity)
                    }
                    .padding(.vertical, 14)
                    .foregroundStyle(.white)
                    .background(CloudTheme.accent.gradient, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                    .padding(16)
                }

                if isLoading && folders.isEmpty {
                    ProgressView()
                        .tint(.white)
                }
            }
            .navigationTitle("Move \u{201C}\(target.displayName)\u{201D}")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                if path.count > 1 {
                    ToolbarItem(placement: .topBarLeading) {
                        Button {
                            goBack()
                        } label: {
                            Image(systemName: "chevron.backward")
                        }
                    }
                }
            }
            .task {
                await load()
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

    private var visibleFolders: [FolderResponse] {
        folders.filter { $0.folderId != target.ownFolderId }
    }

    private func open(_ folder: FolderResponse) {
        currentFolderId = folder.folderId
        currentFolderName = folder.name
        path.append((folder.folderId, folder.name))
        Task { await load() }
    }

    private func goBack() {
        guard path.count > 1 else { return }
        path.removeLast()
        let last = path[path.count - 1]
        currentFolderId = last.id
        currentFolderName = last.name
        Task { await load() }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            folders = try await viewModel.client.listFolders(parentFolderId: currentFolderId)
        } catch let error as APIError {
            errorMessage = error.errorDescription
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func confirmMove() {
        switch target {
        case .file(let file):
            viewModel.moveFile(file, toFolderId: currentFolderId)
        case .folder(let folder):
            viewModel.moveFolder(folder, toFolderId: currentFolderId)
        }
        dismiss()
    }
}
