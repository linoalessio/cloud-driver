import SwiftUI
import UIKit

/// A thin `UIViewControllerRepresentable` wrapper around `UIActivityViewController` - lets a
/// downloaded file be saved to Files, AirDropped, shared, etc. via the system share sheet.
/// SwiftUI's own `ShareLink` needs the item ready at view-build time; this is presented instead
/// once an async download has actually finished (see `AppViewModel.download`/`fileToShare`).
struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {
        // Nothing to update - the activity items are fixed for the lifetime of this sheet.
    }
}
