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
            case .twoFactor(let pendingToken, let email):
                TwoFactorView(viewModel: viewModel, pendingToken: pendingToken, email: email)
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
