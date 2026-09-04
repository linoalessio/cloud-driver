import SwiftUI

/// Switches between the auth flow and the signed-in file browser based on `AppViewModel.screen` -
/// the mobile counterpart to cloud-driver-platforms-desktop's `App.kt`/`Screen.kt` dispatch.
struct RootView: View {
    @ObservedObject var viewModel: AppViewModel

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
                TabView {
                    FileBrowserView(viewModel: viewModel)
                        .tabItem { Label("Home", systemImage: "house.fill") }
                    TrashView(viewModel: viewModel)
                        .tabItem { Label("Trash", systemImage: "trash.fill") }
                    SharedWithMeView(viewModel: viewModel)
                        .tabItem { Label("Shared", systemImage: "person.2.fill") }
                    DashboardView(viewModel: viewModel)
                        .tabItem { Label("Dashboard", systemImage: "person.crop.circle") }
                }
                .tint(CloudTheme.accent)
                .toolbarColorScheme(.dark, for: .tabBar)
            }
        }
        // Owned here, same reasoning as the alert/sheet below - visible regardless of which tab
        // is currently selected, since AppViewModel.transferProgress is shared state an upload/
        // download/extraction triggered from Home should still be visible to from Trash/Shared/
        // Dashboard.
        .safeAreaInset(edge: .bottom) {
            if let progress = viewModel.transferProgress {
                TransferProgressBar(progress: progress)
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
    }
}

/// Real, byte-level progress for an in-flight upload/download/extraction - the mobile counterpart
/// to cloud-driver-platforms-desktop's own `TransferProgressBar` (`Sidebar.kt`). Pinned to the
/// bottom of the screen via `RootView`'s `.safeAreaInset(edge: .bottom)`, so it never overlaps
/// scrollable content and stays visible across every tab.
private struct TransferProgressBar: View {
    let progress: TransferProgress

    private var verb: String {
        switch progress.kind {
        case .upload: return "Uploading"
        case .download: return "Downloading"
        case .extract: return "Extracting"
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
