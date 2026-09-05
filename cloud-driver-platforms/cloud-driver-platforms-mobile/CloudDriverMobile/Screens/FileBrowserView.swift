import SwiftUI
import UniformTypeIdentifiers
import VisionKit

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
    ///
    /// **Only ever read at presentation time (`allowedContentTypes` above) - never at completion
    /// time anymore.** It used to also be captured inside the `.fileImporter` completion closure
    /// (`let kind = pendingImport`) to decide `uploadPickedFolder` vs. `uploadPickedFile` - see
    /// `Self.isDirectory(_:)`'s own doc comment below for the real, confirmed bug that reliance on
    /// produced, and why the completion closure now determines the kind from the picked URL itself
    /// instead.
    private enum PendingImport: Equatable {
        case file
        case folder
    }

    /// **Fixed a real, confirmed bug (2026-09-05): every "Upload folder" pick silently uploaded
    /// the folder as if it were a single plain file, throwing `"The operation couldn't be
    /// completed. Is a directory"` straight out of the network layer (`URLSession.upload(for:
    /// fromFile:)` trying to read a directory's bytes) - not from anywhere in the zip/copy pipeline
    /// at all, which is why three straight attempts to fix that pipeline never changed the observed
    /// error one bit; each one added diagnostics/fixes to code that was never actually being
    /// reached.** Root cause: the `.fileImporter` completion closure used to read `pendingImport`
    /// (a `@State` cleared by the *same* `isPresented` binding's `set` closure that fires as the
    /// picker sheet dismisses) to decide which upload method to call - a real race between "the
    /// completion handler runs with the result" and "the sheet finishes dismissing, clearing
    /// `pendingImport` back to `nil`" that SwiftUI's public API makes no ordering guarantee about.
    /// Confirmed by adding path/phase-specific diagnostics throughout `AppViewModel`'s entire
    /// folder-copy/zip pipeline (`copyItemRecursively`/`copyCoordinated`/`zipAndUploadFolder`) and
    /// observing the on-screen error stay the exact same generic, unprefixed message on every
    /// retry, byte-for-byte, even after a clean rebuild - proof that code path was never running.
    /// Fixed by determining folder-vs-file **from the picked URL's own type** instead of from
    /// `pendingImport`'s value at completion time, which sidesteps the race entirely regardless of
    /// whatever the real internal completion-vs-dismissal ordering turns out to be.
    private static func isDirectory(_ url: URL) -> Bool {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        var isDirectoryObjC: ObjCBool = false
        let exists = FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectoryObjC)
        return exists && isDirectoryObjC.boolValue
    }

    /// A `QuickActionMenu` currently being shown, plus where (in `Self.menuCoordinateSpace`) it
    /// should appear - see `quickActionGesture`/the overlay rendering it, right below the "Content
    /// Hidden" button in `body`.
    private struct ActiveQuickActionMenu: Identifiable {
        let id = UUID()
        let location: CGPoint
        let actions: [QuickAction]
    }

    /// Named coordinate space every `quickActionGesture`'s own `DragGesture` reads its press
    /// location in, and that the `ActiveQuickActionMenu` overlay positions itself within -
    /// declared once, on the outer `ZStack` in `body`, so both sides agree on the same origin
    /// regardless of how deeply nested the row that was actually pressed is.
    private static let menuCoordinateSpace = "FileBrowserView.menuSpace"

    @ObservedObject var viewModel: AppViewModel
    @State private var pendingImport: PendingImport?
    @State private var showingNewFolderAlert = false
    @State private var newFolderName = ""
    @State private var movingTargets: MoveTargets?
    @State private var sharingTargets: ShareTargets?
    @State private var showingDeleteSelectedConfirmation = false
    /// The entry currently being renamed, plus the alert's own text field contents - `nil`/empty
    /// when the "Rename" alert isn't showing. Added 2026-09-05, per Lino's own request.
    @State private var renamingEntry: SelectableEntry?
    @State private var renameText = ""
    /// The folder a "Set color" action is currently open for, if any - drives `FolderColorPickerSheet`.
    @State private var colorPickingFolder: FolderResponse?
    /// Whether the document scanner (`DocumentScannerView`) is currently presented - added
    /// 2026-09-05, per Lino's own request: scan a document with the camera and import it as a PDF.
    @State private var isShowingScanner = false
    @State private var quickActionMenu: ActiveQuickActionMenu?
    /// Continuously updated by `quickActionGesture`'s own `DragGesture` while a press is active -
    /// read once a long press actually succeeds, to know where to show the menu. A single shared
    /// piece of state is fine since only one press can be in progress at a time, and there is only
    /// ever one `quickActionGesture` attached at all (see its own doc comment for why).
    @State private var pressLocation: CGPoint = .zero
    /// Each currently-rendered folder/file row's own on-screen frame, in `Self.menuCoordinateSpace`
    /// - populated by every row's `.onGeometryChange`, read by `quickActions(at:)` to resolve which
    /// row (if any) a long press landed on. Keyed by the row's own id (`folderId`/`fileId`), not
    /// cleared on navigation - a stale entry for a folder/file no longer in the current listing is
    /// harmless (it's simply never looked up again, since `quickActions(at:)` also checks the id
    /// still resolves against `viewModel.folders`/`viewModel.files`).
    @State private var folderRowFrames: [String: CGRect] = [:]
    @State private var fileRowFrames: [String: CGRect] = [:]
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
                                                    iconColor: FolderColorOption.forName(folder.color).color,
                                                    title: folder.name,
                                                    showDivider: index != viewModel.folders.count - 1
                                                ) {
                                                    if !viewModel.isSelecting {
                                                        Menu {
                                                            menuButtons(folderMenuActions(folder, entry: entry))
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
                                        // Tracks this row's own on-screen frame so the *one*
                                        // shared `quickActionGesture` (attached once, to the
                                        // whole `ScrollView` - see its own doc comment) can tell
                                        // a long press landed here and show `folderMenuActions`
                                        // instead of the background's own "+" actions.
                                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .named(Self.menuCoordinateSpace)) }) { newValue in
                                            folderRowFrames[folder.folderId] = newValue
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
                                            } else {
                                                viewModel.previewFile(file)
                                            }
                                        } label: {
                                            HStack(spacing: 8) {
                                                if viewModel.isSelecting {
                                                    selectionIndicator(isSelected: viewModel.selectedEntries.contains(entry))
                                                }
                                                CloudRow(
                                                    icon: fileIcon(for: file.contentType),
                                                    iconColor: fileIconColor(for: file.contentType),
                                                    title: file.fileName,
                                                    subtitle: formatBytes(file.sizeBytes),
                                                    showDivider: index != viewModel.files.count - 1
                                                ) {
                                                    if !viewModel.isSelecting {
                                                        Menu {
                                                            menuButtons(fileMenuActions(file, entry: entry))
                                                        } label: {
                                                            Image(systemName: "ellipsis.circle")
                                                                .foregroundStyle(CloudTheme.textSecondary)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .buttonStyle(.plain)
                                        // Same frame-tracking the folder rows above get - see that
                                        // row's own comment for why this isn't a per-row gesture.
                                        .onGeometryChange(for: CGRect.self, of: { $0.frame(in: .named(Self.menuCoordinateSpace)) }) { newValue in
                                            fileRowFrames[file.fileId] = newValue
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
                // The one and only `quickActionGesture` - attached to the ScrollView itself (not
                // the background gradient behind it) since the ScrollView is the view that
                // actually receives touches across its whole frame, rows included. Long-pressing
                // directly on a row still opens *that* row's own actions (`quickActions(at:)`
                // checks `folderRowFrames`/`fileRowFrames` first) - long-pressing anywhere else
                // opens the same "+" actions the toolbar button does. Deliberately a single
                // shared gesture rather than one per row plus a separate one here: two
                // `.simultaneousGesture`s covering the same touch (one on a row, one on this
                // ScrollView, since the row sits inside it) would both recognize the same long
                // press with no exclusivity between them, racing to each set `quickActionMenu`
                // independently.
                .simultaneousGesture(quickActionGesture())

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

                // The instantly-appearing dropdown itself - rendered as the topmost `ZStack`
                // child so it draws over everything else, positioned via `clampedPosition` at
                // (as close as possible to) the exact point pressed, per Lino's explicit
                // instruction: appear directly at the press location, not with a native
                // `.contextMenu`'s delayed preview/blur. Deliberately no `.transition` -
                // "directly" means no fade-in either.
                if let menu = quickActionMenu {
                    GeometryReader { proxy in
                        // A near-invisible full-screen tap catcher, so tapping anywhere outside
                        // the menu itself dismisses it - the standard "tap outside to close" a
                        // dropdown menu is expected to have.
                        Color.black.opacity(0.001)
                            .contentShape(Rectangle())
                            .onTapGesture { quickActionMenu = nil }

                        QuickActionMenu(actions: menu.actions) { quickActionMenu = nil }
                            .position(clampedPosition(for: menu.location, in: proxy.size, itemCount: menu.actions.count))
                    }
                }
            }
            .coordinateSpace(name: Self.menuCoordinateSpace)
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
                            menuButtons(addMenuActions())
                        } label: {
                            Image(systemName: "plus.circle.fill")
                                .foregroundStyle(CloudTheme.accent)
                        }
                        .disabled(isContentHidden)
                    }
                    ToolbarItem(placement: .topBarTrailing) {
                        Menu {
                            Menu {
                                ForEach(SortOption.allCases) { option in
                                    Button {
                                        viewModel.changeFolderSortOption(option)
                                    } label: {
                                        if option == viewModel.folderSortOption {
                                            Label(option.label, systemImage: "checkmark")
                                        } else {
                                            Text(option.label)
                                        }
                                    }
                                }
                            } label: {
                                Label("Sort Folders", systemImage: "folder")
                            }
                            Menu {
                                ForEach(SortOption.allCases) { option in
                                    Button {
                                        viewModel.changeFileSortOption(option)
                                    } label: {
                                        if option == viewModel.fileSortOption {
                                            Label(option.label, systemImage: "checkmark")
                                        } else {
                                            Text(option.label)
                                        }
                                    }
                                }
                            } label: {
                                Label("Sort Files", systemImage: "doc")
                            }
                        } label: {
                            Image(systemName: "arrow.up.arrow.down.circle.fill")
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
        // Fixed a real bug: switching away to another tab (Dashboard/Trash/Shared) left an
        // already-open `QuickActionMenu` visible/interactive - `TabView` keeps every tab's view
        // hierarchy alive rather than tearing it down on switch, so `quickActionMenu` simply kept
        // its last value with nothing to ever clear it. `onDisappear` fires when this tab's own
        // content leaves the screen (a tab switch included), which is exactly the right moment to
        // dismiss a menu that's tied to a specific press location on this now-hidden screen.
        .onDisappear {
            quickActionMenu = nil
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
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    if Self.isDirectory(url) {
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
        // "Set color" (added 2026-09-05) - a small sheet of preset swatches, mirroring the
        // desktop app's own `FolderColorPickerDialog`.
        .sheet(item: $colorPickingFolder) { folder in
            FolderColorPickerSheet(
                currentColor: FolderColorOption.forName(folder.color),
                onSelect: { option in
                    viewModel.setFolderColor(folder, to: option)
                    colorPickingFolder = nil
                }
            )
            .presentationDetents([.height(180)])
        }
        // "Rename" (added 2026-09-05, per Lino's own request) - same "alert with a TextField"
        // shape as "New folder" above, prefilled with the entry's current name via `displayName`
        // (set alongside `renamingEntry` when the action fires - see `folderMenuActions`/
        // `fileMenuActions`). Dispatches to `renameFile`/`renameFolder` based on which case
        // `renamingEntry` actually is.
        .alert("Rename", isPresented: Binding(
            get: { renamingEntry != nil },
            set: { isPresented in if !isPresented { renamingEntry = nil } }
        )) {
            TextField("Name", text: $renameText)
            Button("Rename") {
                let name = renameText.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty, let entry = renamingEntry {
                    switch entry {
                    case .file(let file):
                        viewModel.renameFile(file, to: name)
                    case .folder(let folder):
                        viewModel.renameFolder(folder, to: name)
                    }
                }
            }
            Button("Cancel", role: .cancel) {}
        }
        // The document scanner (added 2026-09-05, per Lino's own request) - a full-screen camera
        // UI, matching Apple's own convention for VNDocumentCameraViewController (a `.sheet` would
        // read as a lightweight modal, not a full camera experience). `isShowingScanner` is reset
        // to `false` from both `onFinish`/`onError` themselves, so the cover always dismisses
        // regardless of how the scan session ended.
        .fullScreenCover(isPresented: $isShowingScanner) {
            DocumentScannerView(
                onFinish: { pdfData in
                    isShowingScanner = false
                    if let pdfData, !pdfData.isEmpty {
                        viewModel.uploadScannedDocument(pdfData: pdfData)
                    }
                },
                onError: { error in
                    isShowingScanner = false
                    viewModel.errorMessage = error.localizedDescription
                }
            )
            .ignoresSafeArea()
        }
        // Error display and the download share sheet are both owned by RootView (shared across
        // every tab) - see its own comment.
    }

    private var isEverythingSelected: Bool {
        let everything = Set(viewModel.folders.map(SelectableEntry.folder) + viewModel.files.map(SelectableEntry.file))
        return !everything.isEmpty && viewModel.selectedEntries == everything
    }

    /// The Home screen's "add" actions - shared between the toolbar's "+" `Menu` (tap, via
    /// `menuButtons`) and the long-press dropdown on the screen's own background
    /// (`quickActionGesture`/`quickActions(at:)`), so the two affordances can never drift out of
    /// sync with each other.
    private func addMenuActions() -> [QuickAction] {
        var actions: [QuickAction] = [
            QuickAction("Upload file", systemImage: "square.and.arrow.up") {
                pendingImport = .file
            },
            QuickAction("Upload folder", systemImage: "folder.badge.plus") {
                pendingImport = .folder
            },
            QuickAction("New folder", systemImage: "plus.rectangle.on.folder") {
                newFolderName = ""
                showingNewFolderAlert = true
            },
            QuickAction("Scan Document", systemImage: "doc.viewfinder") {
                if VNDocumentCameraViewController.isSupported {
                    isShowingScanner = true
                } else {
                    viewModel.errorMessage = "Document scanning isn't available on this device."
                }
            }
        ]
        if !viewModel.folders.isEmpty || !viewModel.files.isEmpty {
            actions.append(QuickAction("Select items", systemImage: "checkmark.circle") {
                viewModel.enterSelectionMode()
            })
        }
        return actions
    }

    /// The actions available on one folder - shared between the row's tap-to-open "..." `Menu` and
    /// its long-press dropdown, so the two affordances can never drift out of sync with each other.
    private func folderMenuActions(_ folder: FolderResponse, entry: SelectableEntry) -> [QuickAction] {
        [
            QuickAction("Rename", systemImage: "pencil") {
                renameText = entry.displayName
                renamingEntry = entry
            },
            QuickAction("Move to...", systemImage: "folder") {
                movingTargets = MoveTargets(entries: [entry])
            },
            QuickAction("Set color", systemImage: "paintpalette") {
                colorPickingFolder = folder
            },
            QuickAction("Share", systemImage: "person.badge.plus") {
                sharingTargets = ShareTargets(entries: [entry])
            },
            QuickAction("Delete", systemImage: "trash", role: .destructive) {
                viewModel.deleteFolder(folder)
            }
        ]
    }

    /// The actions available on one file - same "shared between tap-to-open and long-press" shape
    /// as `folderMenuActions`.
    private func fileMenuActions(_ file: StoredFileSummaryResponse, entry: SelectableEntry) -> [QuickAction] {
        var actions: [QuickAction] = [
            QuickAction("Download", systemImage: "arrow.down.circle") {
                viewModel.download(file)
            }
        ]
        if isZipArchive(file.contentType) {
            actions.append(QuickAction("Extract", systemImage: "doc.zipper") {
                viewModel.extractArchive(file)
            })
        }
        actions.append(contentsOf: [
            QuickAction("Rename", systemImage: "pencil") {
                renameText = entry.displayName
                renamingEntry = entry
            },
            QuickAction("Move to...", systemImage: "folder") {
                movingTargets = MoveTargets(entries: [entry])
            },
            QuickAction("Share", systemImage: "person.badge.plus") {
                sharingTargets = ShareTargets(entries: [entry])
            },
            QuickAction("Delete", systemImage: "trash", role: .destructive) {
                viewModel.deleteFile(file)
            }
        ])
        return actions
    }

    /// Renders a `[QuickAction]` list as native `Menu` content - shared by the toolbar's "+"
    /// `Menu` and each row's "..." `Menu`, so both stay native (VoiceOver/keyboard/Slide Over
    /// friendly) while sourcing their content from the exact same action lists the custom
    /// long-press dropdown (`QuickActionMenu`) below shows.
    @ViewBuilder
    private func menuButtons(_ actions: [QuickAction]) -> some View {
        ForEach(actions) { action in
            Button(role: action.role, action: action.action) {
                Label(action.title, systemImage: action.systemImage)
            }
        }
    }

    /// The one gesture recognizer behind every long-press dropdown on this screen - attached once,
    /// to the `ScrollView` (see its own comment), rather than once per row plus a separate one for
    /// the background. A plain `DragGesture(minimumDistance: 0, ...)` tracks the live touch point
    /// into `pressLocation` (a `LongPressGesture` alone reports no location); once the accompanying
    /// `LongPressGesture` actually succeeds, `quickActions(at:)` resolves what to show from that
    /// point - immediately, with no preview/blur animation, unlike a native `.contextMenu`, per
    /// Lino's explicit request. `SimultaneousGesture` (not `.exclusively(before:)`) so this never
    /// competes with - or blocks - a row's own tap-to-open `Button` action underneath it.
    private func quickActionGesture() -> some Gesture {
        SimultaneousGesture(
            DragGesture(minimumDistance: 0, coordinateSpace: .named(Self.menuCoordinateSpace))
                .onChanged { pressLocation = $0.location },
            LongPressGesture(minimumDuration: 0.2)
                .onEnded { _ in
                    let actions = quickActions(at: pressLocation)
                    guard !actions.isEmpty else { return }
                    quickActionMenu = ActiveQuickActionMenu(location: pressLocation, actions: actions)
                }
        )
    }

    /// Resolves which `QuickAction`s a long press at `point` (in `Self.menuCoordinateSpace`) should
    /// show: a folder/file row's own actions if `point` falls inside one of the frames
    /// `folderRowFrames`/`fileRowFrames` track (populated by each row's `.onGeometryChange`),
    /// otherwise the screen's own "+" actions for a press over empty background - never both,
    /// since this is the only place that decides, unlike two independently-firing gestures would.
    private func quickActions(at point: CGPoint) -> [QuickAction] {
        guard !viewModel.isSelecting else { return [] }
        if let folderId = folderRowFrames.first(where: { $0.value.contains(point) })?.key,
           let folder = viewModel.folders.first(where: { $0.folderId == folderId }) {
            return folderMenuActions(folder, entry: .folder(folder))
        }
        if let fileId = fileRowFrames.first(where: { $0.value.contains(point) })?.key,
           let file = viewModel.files.first(where: { $0.fileId == fileId }) {
            return fileMenuActions(file, entry: .file(file))
        }
        guard !isContentHidden else { return [] }
        return addMenuActions()
    }

    /// Positions a `QuickActionMenu` so it appears anchored at `point` - the exact spot pressed -
    /// while never rendering partly outside `containerSize`. The menu's own size is estimated from
    /// `QuickActionMenu.width`/`rowHeight` (real layout hasn't run yet at the point this is called)
    /// and nudged away from whichever edge(s) it would otherwise overflow.
    private func clampedPosition(for point: CGPoint, in containerSize: CGSize, itemCount: Int) -> CGPoint {
        let menuWidth = QuickActionMenu.width
        let menuHeight = CGFloat(itemCount) * QuickActionMenu.rowHeight
        let margin: CGFloat = 12

        var x = point.x + menuWidth / 2
        x = min(x, containerSize.width - margin - menuWidth / 2)
        x = max(x, margin + menuWidth / 2)

        var y = point.y + menuHeight / 2
        y = min(y, containerSize.height - margin - menuHeight / 2)
        y = max(y, margin + menuHeight / 2)

        return CGPoint(x: x, y: y)
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
