import SwiftUI

/// Read-only browsing inside a folder reached via a share (pushed from `SharedWithMeView`, or
/// from itself for a deeper subfolder) - `GET /folders/{id}/shared-contents`. Deliberately
/// self-contained (its own local loading/error/content state) rather than routed through
/// `AppViewModel`'s global `busy` guard: it's a nested, read-only navigation destination, not a
/// fire-and-forget action, so it doesn't need that machinery - the same reasoning
/// cloud-driver-platforms-desktop's own `ShareDialog` documents for staying outside `run`'s guard.
/// Downloading a file inside still goes through `AppViewModel.download(_:)` (and therefore the
/// shared `fileToShare` sheet, presented from `RootView` so it works regardless of which tab is
/// active) - only the folder *listing* here is local state.
struct SharedFolderBrowserView: View {
    @ObservedObject var viewModel: AppViewModel
    let folderId: String
    let folderName: String

    @State private var files: [StoredFileSummaryResponse] = []
    @State private var subfolders: [FolderResponse] = []
    @State private var isLoading = false
    @State private var loadError: String?

    var body: some View {
        ZStack {
            CloudTheme.backgroundGradient

            ScrollView {
                VStack(spacing: 16) {
                    if !subfolders.isEmpty {
                        CloudCard(
                            icon: "folder.fill",
                            iconColor: CloudTheme.iconFolder,
                            title: "Folders",
                            subtitle: itemCountText(subfolders.count)
                        ) {
                            VStack(spacing: 0) {
                                ForEach(Array(subfolders.enumerated()), id: \.element.id) { index, folder in
                                    NavigationLink {
                                        SharedFolderBrowserView(viewModel: viewModel, folderId: folder.folderId, folderName: folder.name)
                                    } label: {
                                        CloudRow(
                                            icon: "folder.fill",
                                            iconColor: CloudTheme.iconFolder,
                                            title: folder.name,
                                            showDivider: index != subfolders.count - 1
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
                    }

                    if !files.isEmpty {
                        CloudCard(
                            icon: "doc.fill",
                            iconColor: CloudTheme.iconFile,
                            title: "Files",
                            subtitle: itemCountText(files.count)
                        ) {
                            VStack(spacing: 0) {
                                ForEach(Array(files.enumerated()), id: \.element.id) { index, file in
                                    CloudRow(
                                        icon: fileIcon(for: file.contentType),
                                        iconColor: fileIconColor(for: file.contentType),
                                        title: file.fileName,
                                        subtitle: formatBytes(file.sizeBytes),
                                        showDivider: index != files.count - 1
                                    ) {
                                        Button {
                                            viewModel.download(file)
                                        } label: {
                                            Image(systemName: "arrow.down.circle")
                                                .foregroundStyle(CloudTheme.accent)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if files.isEmpty && subfolders.isEmpty && !isLoading {
                        VStack(spacing: 12) {
                            Image(systemName: "tray")
                                .font(.system(size: 40))
                                .foregroundStyle(CloudTheme.textSecondary)
                            Text("This folder is empty")
                                .foregroundStyle(CloudTheme.textSecondary)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 60)
                    }
                }
                .padding(16)
            }
            .scrollIndicators(.hidden)
            .refreshable {
                await load()
            }

            if isLoading && files.isEmpty && subfolders.isEmpty {
                ProgressView()
                    .tint(.white)
            }
        }
        .navigationTitle(folderName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbarBackground(.hidden, for: .navigationBar)
        .task {
            await load()
        }
        .alert("Error", isPresented: Binding(
            get: { loadError != nil },
            set: { isPresented in if !isPresented { loadError = nil } }
        )) {
            Button("OK", role: .cancel) { loadError = nil }
        } message: {
            Text(loadError ?? "")
        }
    }

    private func load() async {
        isLoading = true
        defer { isLoading = false }
        do {
            let contents = try await viewModel.client.sharedFolderContents(folderId: folderId)
            files = contents.files
            subfolders = contents.subfolders
        } catch let error as APIError {
            loadError = error.errorDescription
        } catch {
            loadError = error.localizedDescription
        }
    }

    private func itemCountText(_ count: Int) -> String {
        "\(count) item\(count == 1 ? "" : "s")"
    }
}
