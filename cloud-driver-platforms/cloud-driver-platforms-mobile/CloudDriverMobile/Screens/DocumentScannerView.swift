import SwiftUI
import VisionKit
import PDFKit

/// A SwiftUI wrapper around `VNDocumentCameraViewController` - VisionKit's built-in document
/// scanner (camera capture with automatic edge detection, perspective correction, and multi-page
/// support out of the box, no custom camera/image-processing code of this app's own needed) -
/// added 2026-09-05, per Lino's own request: "scan documents using the iPhone's camera" and
/// import the result as a PDF. `onFinish` receives the resulting multi-page PDF as `Data`
/// (already combined via `combinedPDFData(from:)` below), or `nil` if the user cancelled without
/// scanning anything; `onError` reports a genuine scan failure.
struct DocumentScannerView: UIViewControllerRepresentable {
    let onFinish: (Data?) -> Void
    let onError: (Error) -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let controller = VNDocumentCameraViewController()
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(onFinish: onFinish, onError: onError)
    }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        private let onFinish: (Data?) -> Void
        private let onError: (Error) -> Void

        init(onFinish: @escaping (Data?) -> Void, onError: @escaping (Error) -> Void) {
            self.onFinish = onFinish
            self.onError = onError
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            onFinish(Self.combinedPDFData(from: scan))
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onFinish(nil)
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            onError(error)
        }

        /// Combines every scanned page into one multi-page PDF via PDFKit - `VNDocumentCameraScan`
        /// only hands back individual page images (`UIImage`, already perspective-corrected/
        /// cropped by VisionKit itself), with no PDF-assembly step of its own.
        private static func combinedPDFData(from scan: VNDocumentCameraScan) -> Data {
            let document = PDFDocument()
            for pageIndex in 0..<scan.pageCount {
                let image = scan.imageOfPage(at: pageIndex)
                if let page = PDFPage(image: image) {
                    document.insert(page, at: document.pageCount)
                }
            }
            return document.dataRepresentation() ?? Data()
        }
    }
}
