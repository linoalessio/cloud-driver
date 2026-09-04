import SwiftUI

/// Which of the four signed-in tabs is currently selected - see `RootView.tabSelectionBinding`
/// for why this is tracked explicitly rather than an inline `.tag` with no backing state at all.
private enum Tab: Hashable {
    case home
    case trash
    case shared
    case dashboard
}

/// Switches between the auth flow and the signed-in file browser based on `AppViewModel.screen` -
/// the mobile counterpart to cloud-driver-platforms-desktop's `App.kt`/`Screen.kt` dispatch.
struct RootView: View {
    @ObservedObject var viewModel: AppViewModel
    @State private var selectedTab: Tab = .home

    /// A manually-constructed `Binding` (passed to `TabView(selection:)` instead of `$selectedTab`
    /// directly) so its `set` closure runs on **every** tap of a tab item - including re-tapping
    /// "Home" while it's already selected, which a plain `@State`/`$selectedTab` binding wouldn't
    /// distinguish from "nothing changed" and therefore wouldn't fire an `.onChange` for.
    /// `TabView` itself always calls through to a bound selection's setter on every tap, regardless
    /// of whether the new value differs from the old one - it's only downstream state-diffing
    /// (`@State`'s own invalidation, or `.onChange`) that would skip a no-op write, so intercepting
    /// the tap here, before that write ever happens, is what actually lets "tap Home again while
    /// already on Home" reset navigation the same way switching back from another tab does. Added
    /// 2026-09-05, per Lino's own request: tapping "Home" on the tab bar should always go straight
    /// back to the root folder, not wherever folder navigation was last left inside it.
    private var tabSelectionBinding: Binding<Tab> {
        Binding(
            get: { selectedTab },
            set: { newValue in
                if newValue == .home {
                    viewModel.goToHomeRoot()
                }
                selectedTab = newValue
            }
        )
    }

    var body: some View {
        Group {
            switch viewModel.screen {
            case .login:
                LoginView(viewModel: viewModel)
            case .register:
                RegisterView(viewModel: viewModel)
            case .confirmRegistration(let email):
                ConfirmRegistrationView(viewModel: viewModel, email: email)
            case .resetPasswordRequest:
                RequestResetView(viewModel: viewModel)
            case .resetPasswordConfirm(let email):
                ResetPasswordConfirmView(viewModel: viewModel, email: email)
            case .browser:
                TabView(selection: tabSelectionBinding) {
                    FileBrowserView(viewModel: viewModel)
                        .tabItem { Label("Home", systemImage: "house.fill") }
                        .tag(Tab.home)
                    TrashView(viewModel: viewModel)
                        .tabItem { Label("Trash", systemImage: "trash.fill") }
                        .tag(Tab.trash)
                    SharedWithMeView(viewModel: viewModel)
                        .tabItem { Label("Shared", systemImage: "person.2.fill") }
                        .tag(Tab.shared)
                    DashboardView(viewModel: viewModel)
                        .tabItem { Label("Dashboard", systemImage: "person.crop.circle") }
                        .tag(Tab.dashboard)
                }
                .tint(CloudTheme.accent)
                .toolbarColorScheme(.dark, for: .tabBar)
            }
        }
        // Owned here, same reasoning as the alert/sheet below - visible regardless of which tab
        // is currently selected, since AppViewModel.transferProgress is shared state an upload/
        // download/extraction triggered from Home should still be visible to from Trash/Shared/
        // Dashboard. `.emptyTrash` gets its own centered overlay (`EmptyingTrashOverlay`, below)
        // instead of this bottom bar - a deliberately more prominent treatment for an action that
        // blocks interacting with the trash it's clearing, rather than a background transfer the
        // user can keep browsing around.
        .safeAreaInset(edge: .bottom) {
            if let progress = viewModel.transferProgress, progress.kind != .emptyTrash {
                TransferProgressBar(progress: progress)
            }
        }
        .overlay {
            if let progress = viewModel.transferProgress, progress.kind == .emptyTrash {
                EmptyingTrashOverlay()
            }
        }
        // Both owned here (not per-screen) so they keep working regardless of which tab is
        // currently selected inside the .browser TabView above - a TabView's non-selected tab
        // stays part of the view hierarchy, but an .alert()/.sheet() attached inside it may not
        // reliably present until that tab becomes visible, so every screen (Home, Trash, Shared,
        // and any pushed SharedFolderBrowserView) shares this one alert/sheet bound to the same
        // state.
        .alert("Error", isPresented: Binding(
            get: { viewModel.errorMessage != nil },
            set: { isPresented in if !isPresented { viewModel.errorMessage = nil } }
        )) {
            Button("OK", role: .cancel) { viewModel.errorMessage = nil }
        } message: {
            Text(viewModel.errorMessage ?? "")
        }
        .sheet(item: $viewModel.fileToShare) { item in
            ActivityView(activityItems: [item.url])
        }
        .sheet(item: $viewModel.previewURL) { item in
            FilePreviewView(url: item.url) { viewModel.previewURL = nil }
        }
    }
}

/// Real, byte-level progress for an in-flight upload/download/extraction - the mobile counterpart
/// to cloud-driver-platforms-desktop's own `TransferProgressBar` (`Sidebar.kt`). Pinned to the
/// bottom of the screen via `RootView`'s `.safeAreaInset(edge: .bottom)`, so it never overlaps
/// scrollable content and stays visible across every tab. **Never actually shown for
/// `.emptyTrash`** - `RootView` routes that kind to `EmptyingTrashOverlay` instead (below); the
/// `.emptyTrash` case still has to exist in `verb`'s `switch` for exhaustiveness, but nothing in
/// this view ever renders with it.
private struct TransferProgressBar: View {
    let progress: TransferProgress

    private var verb: String {
        switch progress.kind {
        case .upload: return "Uploading"
        case .download: return "Downloading"
        case .extract: return "Extracting"
        case .emptyTrash: return "Emptying Trash"
        }
    }

    private var label: String {
        let byteText = "\(formatBytes(progress.transferredBytes)) / \(formatBytes(progress.totalBytes))"
        guard progress.totalItems > 1 else {
            return "\(verb) - \(byteText)"
        }
        return "\(verb) \(min(progress.completedItems + 1, progress.totalItems)) of \(progress.totalItems) - \(byteText)"
    }

    var body: some View {
        VStack(spacing: 0) {
            Rectangle()
                .fill(CloudTheme.rowDivider)
                .frame(height: 1)
            VStack(alignment: .leading, spacing: 6) {
                Text(label)
                    .font(.caption)
                    .foregroundStyle(CloudTheme.textSecondary)
                ProgressView(value: progress.fraction)
                    .tint(CloudTheme.accent)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
        }
    }
}

/// The dedicated, centered treatment for `.emptyTrash` (added 2026-09-05, per Lino's own request -
/// "displayed in the middle of the screen with a rotating gear") - a dimmed scrim over the whole
/// screen plus a floating card with a continuously spinning `gearshape.fill` glyph and a short
/// label, rather than routing this kind through the bottom-pinned `TransferProgressBar` every
/// other transfer uses. Deliberately more prominent than that bar: emptying the trash is a single,
/// short-lived, blocking action (one request/response, no incremental byte progress to show at
/// all - unlike upload/download/extract, which stream and can run alongside continued browsing),
/// so a full-screen, attention-holding indicator reads better here than a quiet bottom strip would.
///
/// The rotation itself is driven by a plain `@State` boolean flipped once in `.onAppear` - SwiftUI
/// animates from the *current* value to the *new* value over the given duration and, because
/// `.repeatForever` loops that same interpolation indefinitely, the gear reads as spinning
/// continuously for as long as this view stays on screen (i.e., for as long as `emptyTrash` is
/// still in flight - `RootView` only shows this overlay while `AppViewModel.transferProgress`'s
/// kind is `.emptyTrash`, so it disappears the instant that call finishes).
private struct EmptyingTrashOverlay: View {
    @State private var isRotating = false

    var body: some View {
        ZStack {
            Color.black.opacity(0.35)
                .ignoresSafeArea()

            VStack(spacing: 16) {
                Image(systemName: "gearshape.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(CloudTheme.accent)
                    .rotationEffect(.degrees(isRotating ? 360 : 0))
                    .animation(.linear(duration: 1.4).repeatForever(autoreverses: false), value: isRotating)

                Text("Emptying Trash…")
                    .font(.callout)
                    .foregroundStyle(CloudTheme.textPrimary)
            }
            .padding(28)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
        }
        .onAppear { isRotating = true }
    }
}
