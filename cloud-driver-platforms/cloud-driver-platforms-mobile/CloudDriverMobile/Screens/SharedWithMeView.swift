import SwiftUI

/// The "Shared" tab - everything another account has directly shared with the signed-in one,
/// via `GET /files/shared-with-me`/`GET /folders/shared-with-me` (see `AppViewModel.loadSharedWithMe`).
/// Read-only, matching cloud-driver-platforms-desktop's own `SharedWithMeScreen.kt`: a shared file
/// downloads (reusing `AppViewModel.download(_:)` unchanged - the download route already honors a
/// share the same way it honors ownership); a shared folder pushes into `SharedFolderBrowserView`
/// for read-only browsing/downloading of its contents.
struct SharedWithMeView: View {
    @ObservedObject var viewModel: AppViewModel

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                ScrollView {
                    VStack(spacing: 16) {
                        if !viewModel.sharedFolders.isEmpty {
                            CloudCard(
                                icon: "folder.fill",
                                iconColor: CloudTheme.iconFolder,
                                title: "Shared Folders",
                                subtitle: itemCountText(viewModel.sharedFolders.count)
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(Array(viewModel.sharedFolders.enumerated()), id: \.element.id) { index, entry in
                                        NavigationLink {
                                            SharedFolderBrowserView(
                                                viewModel: viewModel,
                                                folderId: entry.folder.folderId,
                                                folderName: entry.folder.name
                                            )
                                        } label: {
                                            CloudRow(
                                                icon: "folder.fill",
                                                iconColor: CloudTheme.iconFolder,
                                                title: entry.folder.name,
                                                subtitle: "Shared by \(entry.ownerEmail)",
                                                showDivider: index != viewModel.sharedFolders.count - 1
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

                        if !viewModel.sharedFiles.isEmpty {
                            CloudCard(
                                icon: "doc.fill",
                                iconColor: CloudTheme.iconFile,
                                title: "Shared Files",
                                subtitle: itemCountText(viewModel.sharedFiles.count)
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(Array(viewModel.sharedFiles.enumerated()), id: \.element.id) { index, entry in
                                        CloudRow(
                                            icon: fileIcon(for: entry.file.contentType),
                                            iconColor: CloudTheme.iconFile,
                                            title: entry.file.fileName,
                                            subtitle: "Shared by \(entry.ownerEmail)",
                                            showDivider: index != viewModel.sharedFiles.count - 1
                                        ) {
                                            Button {
                                                viewModel.download(entry.file)
                                            } label: {
                                                Image(systemName: "arrow.down.circle")
                                                    .foregroundStyle(CloudTheme.accent)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if viewModel.sharedFiles.isEmpty && viewModel.sharedFolders.isEmpty && !viewModel.busy {
                            emptyState
                        }
                    }
                    .padding(16)
                }
                .scrollIndicators(.hidden)
                .refreshable {
                    viewModel.loadSharedWithMe()
                }

                if viewModel.busy && viewModel.sharedFiles.isEmpty && viewModel.sharedFolders.isEmpty {
                    ProgressView()
                        .tint(.white)
                }
            }
            .navigationTitle("Shared with Me")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .task {
            viewModel.loadSharedWithMe()
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "person.2")
                .font(.system(size: 40))
                .foregroundStyle(CloudTheme.textSecondary)
            Text("Nothing has been shared with you yet")
                .foregroundStyle(CloudTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private func itemCountText(_ count: Int) -> String {
        "\(count) item\(count == 1 ? "" : "s")"
    }
}
