import SwiftUI

/// The Trash tab - files/folders soft-deleted via `AppViewModel.deleteFile`/`deleteFolder`
/// (`DELETE /files/{id}`/`DELETE /folders/{id}`, already soft deletes server-side), listed via
/// `GET /files/trash`/`GET /folders/trash`. Each row restores individually; "Empty Trash"
/// permanently removes everything, bypassing the retention window, behind a confirmation.
struct TrashView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var showingEmptyTrashConfirmation = false

    private static let dateFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        return formatter
    }()

    /// Whether there is currently nothing to empty - drives both the "Empty Trash" header
    /// button's `disabled` state and the dimmed styling below, since a `Button` with `role:
    /// .destructive` doesn't automatically dim itself when disabled the way some other button
    /// styles do.
    private var isTrashEmpty: Bool {
        viewModel.trashFiles.isEmpty && viewModel.trashFolders.isEmpty
    }

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                VStack(spacing: 0) {
                    // Always shown at the top, disabled (not hidden) once there's nothing left
                    // to act on - fixed outside the `ScrollView` below so this row never scrolls
                    // away, per Lino's own request ("shall be displayed at the top, ALWAYS").
                    // "Restore Trash" (green - a positive, fully reversible action, added
                    // 2026-09-05 per Lino's own request) sits alongside "Empty Trash" (red -
                    // destructive, irreversible) so the two opposite bulk actions read as a
                    // deliberate pair, not one favored over the other.
                    HStack(spacing: 12) {
                        Button {
                            viewModel.restoreAllTrash()
                        } label: {
                            Text("Restore Trash")
                                .font(CloudTheme.headline(.body))
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(Color.green.opacity(isTrashEmpty ? 0.35 : 0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .disabled(isTrashEmpty)

                        Button(role: .destructive) {
                            showingEmptyTrashConfirmation = true
                        } label: {
                            Text("Empty Trash")
                                .font(CloudTheme.headline(.body))
                                .frame(maxWidth: .infinity)
                        }
                        .padding(.vertical, 14)
                        .foregroundStyle(.white)
                        .background(Color.red.opacity(isTrashEmpty ? 0.35 : 0.85), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                        .disabled(isTrashEmpty)
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)

                    ScrollView {
                        VStack(spacing: 16) {
                            if !viewModel.trashFolders.isEmpty {
                                CloudCard(
                                    icon: "folder.fill",
                                    iconColor: CloudTheme.iconFolder,
                                    title: "Trashed Folders",
                                    subtitle: itemCountText(viewModel.trashFolders.count)
                                ) {
                                    VStack(spacing: 0) {
                                        ForEach(Array(viewModel.trashFolders.enumerated()), id: \.element.id) { index, entry in
                                            CloudRow(
                                                icon: "folder.fill",
                                                iconColor: CloudTheme.iconFolder,
                                                title: entry.folder.name,
                                                subtitle: purgeText(entry.purgeAtEpochMillis),
                                                showDivider: index != viewModel.trashFolders.count - 1
                                            ) {
                                                Button {
                                                    viewModel.restoreFolder(entry)
                                                } label: {
                                                    Image(systemName: "arrow.uturn.backward.circle")
                                                        .foregroundStyle(CloudTheme.accent)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if !viewModel.trashFiles.isEmpty {
                                CloudCard(
                                    icon: "doc.fill",
                                    iconColor: CloudTheme.iconFile,
                                    title: "Trashed Files",
                                    subtitle: itemCountText(viewModel.trashFiles.count)
                                ) {
                                    VStack(spacing: 0) {
                                        ForEach(Array(viewModel.trashFiles.enumerated()), id: \.element.id) { index, entry in
                                            CloudRow(
                                                icon: fileIcon(for: entry.file.contentType),
                                                iconColor: fileIconColor(for: entry.file.contentType),
                                                title: entry.file.fileName,
                                                subtitle: purgeText(entry.purgeAtEpochMillis),
                                                showDivider: index != viewModel.trashFiles.count - 1
                                            ) {
                                                Button {
                                                    viewModel.restoreFile(entry)
                                                } label: {
                                                    Image(systemName: "arrow.uturn.backward.circle")
                                                        .foregroundStyle(CloudTheme.accent)
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            if isTrashEmpty && !viewModel.busy {
                                emptyState
                            }
                        }
                        .padding(16)
                    }
                    .scrollIndicators(.hidden)
                    .refreshable {
                        viewModel.loadTrash()
                    }
                }

                if viewModel.busy && isTrashEmpty {
                    ProgressView()
                        .tint(.white)
                }
            }
            .navigationTitle("Trash")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
        }
        .task {
            viewModel.loadTrash()
        }
        // `.alert`, not `.confirmationDialog` - a confirmation dialog is iOS's action-sheet style,
        // which always slides up from the *bottom* of the screen with no way to anchor it near
        // whatever triggered it. Since the "Empty Trash" button lives in a fixed header at the
        // *top* of this screen (see above), an action sheet at the opposite end read as
        // disconnected from it - an alert, centered near the top of the screen, sits much closer
        // to the button that opened it (per Lino's own request).
        .alert("Empty Trash?", isPresented: $showingEmptyTrashConfirmation) {
            Button("Empty Trash", role: .destructive) {
                viewModel.emptyTrash()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Every trashed file and folder will be permanently deleted. This can't be undone.")
        }
    }

    private var emptyState: some View {
        VStack(spacing: 12) {
            Image(systemName: "trash")
                .font(.system(size: 40))
                .foregroundStyle(CloudTheme.textSecondary)
            Text("Trash is empty")
                .foregroundStyle(CloudTheme.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 60)
    }

    private func itemCountText(_ count: Int) -> String {
        "\(count) item\(count == 1 ? "" : "s")"
    }

    private func purgeText(_ purgeAtEpochMillis: Int64) -> String {
        "Permanently deleted on \(Self.dateFormatter.string(from: Date(timeIntervalSince1970: Double(purgeAtEpochMillis) / 1000)))"
    }
}
