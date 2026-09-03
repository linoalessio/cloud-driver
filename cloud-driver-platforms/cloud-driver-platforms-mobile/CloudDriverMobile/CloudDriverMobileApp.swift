import SwiftUI

@main
struct CloudDriverMobileApp: App {
    @StateObject private var viewModel = AppViewModel()

    var body: some Scene {
        WindowGroup {
            RootView(viewModel: viewModel)
                // This app's whole visual language (Theme.swift) is a fixed dark, iCloud.com-style
                // gradient/glass design - it doesn't have a light-mode palette to fall back to, so
                // it's pinned to dark rather than following the system appearance.
                .preferredColorScheme(.dark)
                .task {
                    viewModel.tryRestoreSession()
                }
        }
    }
}
