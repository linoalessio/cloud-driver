import SwiftUI
import UniformTypeIdentifiers

/// The "Home" screen once signed in - lists the current folder's subfolders/files inside
/// `CloudCard` widgets (a "Folders" card, a "Files" card), with upload (via the system document
/// picker), download/move/share/delete (via a per-row menu, or via multi-select - see
/// `viewModel.isSelecting`), and folder navigation through a tappable breadcrumb trail. A toolbar
/// eye icon blurs and disables the listing on demand (a privacy toggle - e.g. before showing the
/// screen to someone else), independent of navigation. The mobile counterpart to
/// cloud-driver-platforms-desktop's `FileBrowserScreen.kt`, deliberately scoped down for this
/// first pass - no drag-and-drop, thumbnails, or previews yet.
/// Styled after Apple's own iCloud.com dashboard (see `Theme.swift`), not default iOS list chrome.
struct FileBrowserView: View {
    /// Which kind of item `.fileImporter` below is currently being asked to pick - `nil` means the
    /// picker isn't showing. **Deliberately one `.fileImporter` modifier driven by one piece of
    /// state, not two separate `.fileImporter` modifiers (one per kind)** - fixed a real bug: two
    /// simultaneous `.fileImporter` modifiers attached to the same view is a well-known SwiftUI
    /// gotcha where the second one silently stops presenting (or both start fighting over which
    /// one's sheet actually shows), confirmed here directly - "Upload file" stopped working the
    /// moment a second `.fileImporter` (for "Upload folder") was added alongside it. One importer
    /// whose `allowedContentTypes` switches on `pendingImport` avoids the conflict entirely.
    private enum PendingImport: Equatable {
        case file
        case folder
    }

    @ObservedObject var viewModel: AppViewModel
    @State private var pendingImport: PendingImport?
    @State private var showingNewFolderAlert = false
    @State private var newFolderName = ""
    @State private var movingTargets: MoveTargets?
    @State private var sharingTargets: ShareTargets?
    @State private var showingDeleteSelectedConfirmation = false
    /// Privacy toggle - blurs and disables interaction with the listing below without navigating
    /// away from it, e.g. before showing the screen to someone else. Local, transient UI state,
    /// not persisted - resets to visible on every fresh appearance of this screen.
    @State private var isContentHidden = false

    var body: some View {
        NavigationStack {
            ZStack {
                CloudTheme.backgroundGradient

                ScrollView {
                    VStack(spacing: 16) {
                        BreadcrumbBar(breadcrumbs: viewModel.breadcrumbs) { crumb in
                            viewModel.navigateToBreadcrumb(crumb)
                        }
                        .padding(.horizontal, 4)
                        .disabled(viewModel.isSelecting)
                        .opacity(viewModel.isSelecting ? 0.5 : 1)

                        if !viewModel.folders.isEmpty {
                            CloudCard(
                                icon: "folder.fill",
                                iconColor: CloudTheme.iconFolder,
                                title: "Folders",
                                subtitle: itemCountText(viewModel.folders.count)
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(Array(viewModel.folders.enumerated()), id: \.element.id) { index, folder in
                                        let entry = SelectableEntry.folder(folder)
                                        Button {
                                            if viewModel.isSelecting {
                                                viewModel.toggleSelection(entry)
                                            } else {
                                                viewModel.openFolder(folder)
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                if viewModel.isSelecting {
                                                    selectionIndicator(isSelected: viewModel.selectedEntries.contains(entry))
                                                }
                                                CloudRow(
                                                    icon: "folder.fill",
                                                    iconColor: CloudTheme.iconFolder,
                                                    title: folder.name,
                                                    showDivider: index != viewModel.folders.count - 1
                                                ) {
                                                    if !viewModel.isSelecting {
                                                        Menu {
                                                            folderMenuItems(folder, entry: entry)
                                                        } label: {
                                                            Image(systemName: "ellipsis")
                                                                .foregroundStyle(CloudTheme.textSecondary)
                                                                .padding(.trailing, 4)
                                                        }
                                                        Image(systemName: "chevron.right")
                                                            .font(.caption)
                                                            .foregroundStyle(CloudTheme.textSecondary)
                                                    }
                                                }
                                            }
                                        }
                                        .buttonStyle(.plain)
                                        // Long-pressing a row opens the exact same actions as
                                        // tapping its "..." menu, via `folderMenuItems` - native
                                        // SwiftUI `.contextMenu` already recognizes a long press,
                                        // no custom gesture needed. Suppressed while multi-selecting,
                                        // matching the "..." menu's own visibility.
                                        .contextMenu {
                                            if !viewModel.isSelecting {
                                                folderMenuItems(folder, entry: entry)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if !viewModel.files.isEmpty {
                            CloudCard(
                                icon: "doc.fill",
                                iconColor: CloudTheme.iconFile,
                                title: "Files",
                                subtitle: itemCountText(viewModel.files.count)
                            ) {
                                VStack(spacing: 0) {
                                    ForEach(Array(viewModel.files.enumerated()), id: \.element.id) { index, file in
                                        let entry = SelectableEntry.file(file)
                                        Button {
                                            if viewModel.isSelecting {
                                                viewModel.toggleSelection(entry)
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                if viewModel.isSelecting {
                                                    selectionIndicator(isSelected: viewModel.selectedEntries.contains(entry))
                                                }
                                                CloudRow(
                                                    icon: fileIcon(for: file.contentType),
                                                    iconColor: CloudTheme.iconFile,
                                                    title: file.fileName,
                                                    subtitle: formatBytes(file.sizeBytes),
                                                    showDivider: index != viewModel.files.count - 1
                                                ) {
                                                    if !viewModel.isSelecting {
                                                        Menu {
                                                            fileMenuItems(file, entry: entry)
                                                        } label: {
                                                            Image(systemName: "ellipsis.circle")
                                                                .foregroundStyle(CloudTheme.textSecondary)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .buttonStyle(.plain)
                                        // Same long-press-opens-the-"..."-menu affordance the
                                        // folder rows above get - see that comment.
                                        .contextMenu {
                                            if !viewModel.isSelecting {
                                                fileMenuItems(file, entry: entry)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if viewModel.folders.isEmpty && viewModel.files.isEmpty && !viewModel.busy {
                            emptyState
                        }
                    }
                    .padding(.horizontal, 16)
                    .padding(.top, 8)
                    .padding(.bottom, 32)
                    .blur(radius: isContentHidden ? 30 : 0)
                    .allowsHitTesting(!isContentHidden)
                    .animation(.easeInOut(duration: 0.2), value: isContentHidden)
                }
                .scrollIndicators(.hidden)
                .refreshable {
                    viewModel.loadCurrentFolder()
                }

                if viewModel.busy && viewModel.files.isEmpty && viewModel.folders.isEmpty {
                    ProgressView()
                        .tint(.white)
                }

                if isContentHidden {
                    Button {
                        isContentHidden = false
                    } label: {
                        VStack(spacing: 12) {
                            Image(systemName: "eye.slash.fill")
                                .font(.system(size: 36))
                                .foregroundStyle(CloudTheme.textPrimary)
                            Text("Content Hidden")
                                .font(CloudTheme.headline(.body))
                                .foregroundStyle(CloudTheme.textPrimary)
                            Text("Tap to show your files again")
                                .font(.caption)
                                .foregroundStyle(CloudTheme.textSecondary)
                        }
                        .padding(28)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .transition(.opacity)
                }
            }
            .navigationTitle(viewModel.breadcrumbs.last?.name ?? "Home")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                if viewModel.isSelecting {
                    ToolbarItem(placement: .topBarLeading) {
                        Button("Cancel") {
                            viewModel.exitSelectionMode()
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(isEverythingSelected ? "Deselect All" : "Select All") {
                            viewModel.selectAll()
                        }
                    }
                } else {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button {
                            isContentHidden.toggle()
                        } label: {
                            Image(systemName: isContentHidden ? "eye.slash.fill" : "eye.fill")
                                .foregroundStyle(CloudTheme.accent)
                        }
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            addMenuItems()
                        } label: {
                            Image(systemName: "plus.circle.fill")
                                .foregroundStyle(CloudTheme.accent)
                        }
                        .disabled(isContentHidden)
                    }
                }
            }
            .safeAreaInset(edge: .bottom) {
                if viewModel.isSelecting {
                    SelectionActionBar(
                        selectedCount: viewModel.selectedEntries.count,
                        onMove: { movingTargets = MoveTargets(entries: Array(viewModel.selectedEntries)) },
                        onShare: { sharingTargets = ShareTargets(entries: Array(viewModel.selectedEntries)) },
                        onDelete: { showingDeleteSelectedConfirmation = true }
                    )
                }
            }
        }
        .task {
            viewModel.loadCurrentFolder()
        }
        .fileImporter(
            isPresented: Binding(
                get: { pendingImport != nil },
                set: { isPresented in if !isPresented { pendingImport = nil } }
            ),
            allowedContentTypes: pendingImport == .folder ? [.folder] : [.item],
            allowsMultipleSelection: false
        ) { result in
            let kind = pendingImport
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    if kind == .folder {
                        viewModel.uploadPickedFolder(url: url)
                    } else {
                        viewModel.uploadPickedFile(url: url)
                    }
                }
            case .failure(let error):
                viewModel.errorMessage = error.localizedDescription
            }
        }
        .alert("New folder", isPresented: $showingNewFolderAlert) {
            TextField("Folder name", text: $newFolderName)
            Button("Create") {
                let name = newFolderName.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty {
                    viewModel.createFolder(name: name)
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        .alert(
            "Delete \(viewModel.selectedEntries.count) item\(viewModel.selectedEntries.count == 1 ? "" : "s")?",
            isPresented: $showingDeleteSelectedConfirmation
        ) {
            Button("Delete", role: .destructive) {
                viewModel.deleteSelected()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone.")
        }
        .sheet(item: $movingTargets) { targets in
            MoveToFolderSheet(viewModel: viewModel, targets: targets)
        }
        .sheet(item: $sharingTargets) { targets in
            ShareSheet(viewModel: viewModel, targets: targets)
        }
        // Error display and the download share sheet are both owned by RootView (shared across
        // every tab) - see its own comment.
    }

    private var isEverythingSelected: Bool {
        let everything = Set(viewModel.folders.map(SelectableEntry.folder) + viewModel.files.map(SelectableEntry.file))
        return !everything.isEmpty && viewModel.selectedEntries == everything
    }

    /// The actions available on one folder - shared between the row's tap-to-open "..." `Menu` and
    /// its long-press `.contextMenu`, so the two affordances can never drift out of sync with each
    /// other.
    @ViewBuilder
    private func folderMenuItems(_ folder: FolderResponse, entry: SelectableEntry) -> some View {
        Button {
            movingTargets = MoveTargets(entries: [entry])
        } label: {
            Label("Move to...", systemImage: "folder")
        }
        Button {
            sharingTargets = ShareTargets(entries: [entry])
        } label: {
            Label("Share", systemImage: "person.badge.plus")
        }
        Button(role: .destructive) {
            viewModel.deleteFolder(folder)
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    /// The actions available on one file - same "shared between tap-to-open and long-press" shape
    /// as `folderMenuItems`.
    @ViewBuilder
    private func fileMenuItems(_ file: StoredFileSummaryResponse, entry: SelectableEntry) -> some View {
        Button {
            viewModel.download(file)
        } label: {
            Label("Download", systemImage: "arrow.down.circle")
        }
        if isZipArchive(file.contentType) {
            Button {
                viewModel.extractArchive(file)
            } label: {
                Label("Extract", systemImage: "doc.zipper")
            }
        }
        Button {
            movingTargets = MoveTargets(entries: [entry])
        } label: {
            Label("Move to...", systemImage: "folder")
        }
        Button {
            sharingTargets = ShareTargets(entries: [entry])
        } label: {
            Label("Share", systemImage: "person.badge.plus")
        }
        Button(role: .destructive) {
            viewModel.deleteFile(file)
        } label: {
            Label("Delete", systemImage: "trash")
        }
    }

    @ViewBuilder
    private func selectionIndicator(isSelected: Bool) -> some View {
        Image(systemName: isSelected ? "checkmark.circle.fill" : "circle")
            .font(.system(size: 20))
            .foregroundStyle(isSelected ? CloudTheme.accent : CloudTheme.textSecondary)
    }

    private var emptyState: some View {
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

    private func itemCountText(_ count: Int) -> String {
        "\(count) item\(count == 1 ? "" : "s")"
    }

}

private struct BreadcrumbBar: View {
    let breadcrumbs: [Breadcrumb]
    let onTap: (Breadcrumb) -> Void

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 4) {
                ForEach(breadcrumbs) { crumb in
                    Button(crumb.name) { onTap(crumb) }
                        .font(.subheadline.weight(crumb.id == breadcrumbs.last?.id ? .semibold : .regular))
                        .foregroundStyle(crumb.id == breadcrumbs.last?.id ? CloudTheme.textPrimary : CloudTheme.textSecondary)
                        .lineLimit(1)
                    if crumb.id != breadcrumbs.last?.id {
                        Image(systemName: "chevron.right")
                            .font(.caption2)
                            .foregroundStyle(CloudTheme.textSecondary)
                    }
                }
            }
        }
    }
}

/// The bottom bar shown while `AppViewModel.isSelecting` - a selection count plus Move/Share/Delete,
/// each disabled while nothing is selected. Presented via `.safeAreaInset(edge: .bottom)` so it
/// never overlaps the scrollable listing above it.
private struct SelectionActionBar: View {
    let selectedCount: Int
    let onMove: () -> Void
    let onShare: () -> Void
    let onDelete: () -> Void

    private var isEmpty: Bool { selectedCount == 0 }

    var body: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(CloudTheme.rowDivider)
                .frame(height: 1)
            HStack {
                Text("\(selectedCount) selected")
                    .font(.subheadline)
                    .foregroundStyle(CloudTheme.textSecondary)
                Spacer()
                Button(action: onMove) {
                    Image(systemName: "folder")
                }
                .disabled(isEmpty)
                Spacer()
                Button(action: onShare) {
                    Image(systemName: "person.badge.plus")
                }
                .disabled(isEmpty)
                Spacer()
                Button(role: .destructive, action: onDelete) {
                    Image(systemName: "trash")
                }
                .disabled(isEmpty)
            }
            .font(.system(size: 18))
            .foregroundStyle(CloudTheme.accent)
            .padding(.horizontal, 24)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial)
        }
    }
}
