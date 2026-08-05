import PhotosUI
import SwiftUI

/// The system picker, wrapped thinly enough to be worth no more than this.
///
/// `PHPickerViewController` rather than an intent, and rather than a gallery of
/// our own: it runs out of process, so it needs no permission to *show* the
/// library, and a selection made in it joins the limited-access set — which is how
/// the bytes are readable at **Attach** even for a user who granted access to
/// nothing.
///
/// It hands back asset identifiers, which is all the record keeps (#97).
struct MediaPicker: UIViewControllerRepresentable {
    var onPicked: ([String]) -> Void

    func makeUIViewController(context: Context) -> PHPickerViewController {
        // The photoLibrary: form is required for `assetIdentifier` to come back at
        // all; the default configuration returns anonymous item providers.
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.selectionLimit = 0
        config.filter = .any(of: [.images, .videos])
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_: PHPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPicked) }

    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        private let onPicked: ([String]) -> Void

        init(_ onPicked: @escaping ([String]) -> Void) { self.onPicked = onPicked }

        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            picker.dismiss(animated: true)
            onPicked(results.compactMap(\.assetIdentifier))
        }
    }
}
