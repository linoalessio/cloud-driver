import SwiftUI
import UIKit
import QuickLook

/// A `UIViewControllerRepresentable` wrapper around `QLPreviewController` - Apple's native file
/// preview UI, which already renders PDF, plain text, and Office documents (.docx/.xlsx/.pptx,
/// among others) with no per-format rendering code of this app's own, unlike
/// cloud-driver-platforms-desktop's `FilePreviewDialog.kt` (no JVM equivalent to QuickLook exists
/// there, so it hand-rolls PDF/DOCX rendering via PDFBox/POI). `url` must already have the file's
/// real extension in its last path component - `QLPreviewController` identifies how to render a
/// file from its extension/UTI, not its declared content type, so `AppViewModel#previewFile`
/// downloads to a temp path that preserves the original file name for exactly this reason.
///
/// Embedded in a `UINavigationController` purely to get a "Done" button - `QLPreviewController`
/// only renders its own top bar when it's itself the visible view controller inside a navigation
/// controller, not when presented bare.
struct FilePreviewView: UIViewControllerRepresentable {
    let url: URL
    let onDismiss: () -> Void

    func makeUIViewController(context: Context) -> UINavigationController {
        let controller = QLPreviewController()
        controller.dataSource = context.coordinator
        controller.navigationItem.rightBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .done,
            target: context.coordinator,
            action: #selector(Coordinator.dismissTapped)
        )
        return UINavigationController(rootViewController: controller)
    }

    func updateUIViewController(_ uiViewController: UINavigationController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(url: url, onDismiss: onDismiss)
    }

    final class Coordinator: NSObject, QLPreviewControllerDataSource {
        private let url: URL
        private let onDismiss: () -> Void

        init(url: URL, onDismiss: @escaping () -> Void) {
            self.url = url
            self.onDismiss = onDismiss
        }

        func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
            1
        }

        func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
            url as NSURL
        }

        @objc func dismissTapped() {
            onDismiss()
        }
    }
}
