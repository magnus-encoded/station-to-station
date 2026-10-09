import SwiftUI
import UIKit
import Photos
import AVFoundation
import Combine

struct TourCoachMarkView: View {
    @ObservedObject var model: AppModel
    let mark: TourCoachMark
    var screen: String = "line"
    @State private var cameraPresented = false
    @State private var cameraError: String?

    var body: some View {
        let character = model.tour.character
        let line = character.line(mark, opener: model.tour.opener, screen: screen == "room" && model.tour.state.returnedFromPhotos ? "roomAfterPhoto" : screen)
        card(character, line: line)
            .sheet(isPresented: $cameraPresented) {
                TourCamera(cancel: { cameraPresented = false }) { result in
                    cameraPresented = false
                    switch result {
                    case .success:
                        if let id = model.state.selectedSetlist?.id { model.tour.returnedFromPhotos(id) }
                    case .failure(let error): cameraError = error.localizedDescription
                    }
                }
            }
            .alert("Camera", isPresented: Binding(get: { cameraError != nil }, set: { if !$0 { cameraError = nil } })) {
                Button("Choose an existing photo") {
                    if let id = model.state.selectedSetlist?.id { model.tour.returnedFromPhotos(id) }
                    cameraError = nil
                }
                Button("Cancel", role: .cancel) { cameraError = nil }
            } message: { Text(cameraError ?? "") }
    }

    private func card(_ character: TourCharacter, line: TourCharacter.Line) -> some View {
        // The flexible box leaves 94% of the portrait width visible.
        ZStack(alignment: .bottomTrailing) {
            Image(character.cutout).resizable().scaledToFit()
                .frame(width: 124, height: 148.8)
                .shadow(color: .white.opacity(0.45), radius: 1)
                .shadow(color: .black.opacity(0.5), radius: 6, y: 2)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 5) {
                Text(character.name).font(.system(size: 11, weight: .bold)).foregroundStyle(.white)
                    .padding(.init(top: 2, leading: 9, bottom: 2, trailing: 16))
                    .background(model.tourAccent, in: TourNameTab())
                Text(line.instruction).font(.system(size: 14, weight: .semibold, design: .serif))
                if let why = line.why {
                    Text(why).font(.system(size: 12, design: .serif))
                        .foregroundStyle(Color(red: 207/255, green: 199/255, blue: 222/255))
                }
                HStack {
                    Button("Skip") { model.tour.skip() }.foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    if mark == .selfie && screen.hasPrefix("room") {
                        Button {
                            Task { @MainActor in
                                guard UIImagePickerController.isSourceTypeAvailable(.camera) else {
                                    cameraError = "No camera is available. You can choose a photo from your library."
                                    return
                                }
                                let allowed = await AVCaptureDevice.requestAccess(for: .video)
                                if allowed { cameraPresented = true }
                                else { cameraError = "Camera access is off. Enable it in Settings, or choose a photo from your library." }
                            }
                        } label: { Image(systemName: "camera") }
                        .accessibilityLabel("Take a selfie")
                    }
                    if mark.canAcknowledge(on: screen) {
                        Button("OK") { model.tour.acknowledgeCard() }
                    }
                }.font(.system(size: 12)).padding(.top, 3)
            }
            .foregroundStyle(Color(red: 241/255, green: 236/255, blue: 248/255))
            .padding(.init(top: 12, leading: 14, bottom: 7, trailing: 11))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .background(Color(red: 58/255, green: 50/255, blue: 74/255).opacity(0.94), in: RoundedRectangle(cornerRadius: 14))
            .overlay(alignment: .leading) { Rectangle().fill(model.tourAccent).frame(width: 3) }
            .padding(.leading, 6).padding(.trailing, 124 * 0.94)
            .padding(.top, 10).padding(.bottom, 6)
        }
        .frame(minHeight: 155, alignment: .bottom)
        .fixedSize(horizontal: false, vertical: true)
    }
}

private struct TourNameTab: Shape {
    func path(in rect: CGRect) -> Path {
        Path { p in
            p.move(to: .zero); p.addLine(to: CGPoint(x: rect.width, y: 0))
            p.addLine(to: CGPoint(x: rect.width - 8, y: rect.height))
            p.addLine(to: CGPoint(x: 0, y: rect.height)); p.closeSubpath()
        }
    }
}

extension AppModel {
    var tourAccent: Color {
        let index = tour.virtualFriendKey.flatMap { key in Array(state.friends.reversed()).firstIndex { $0.publicKey == key } }
        // Before the Exchange there is no Contact; use the first Contact's palette index.
        let colourIndex = index ?? 0
        return laneColor(colourIndex)
    }
}

struct TourUpgradePromptView: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("New: a guided tour").font(.headline)
            Text("Walk through the app at a demo gig. It takes a few minutes and leaves nothing behind.")
            HStack {
                Button("No thanks") { model.tour.dismissUpgradePrompt() }
                Spacer()
                Button("Take the tour") { model.tour.acceptUpgradePrompt() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .padding()
    }
}

private struct TourModifier: ViewModifier {
    @ObservedObject var model: AppModel
    let dockTop: Bool
    let screen: String

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .top, spacing: 0) {
                if dockTop, let mark = model.state.tourCoachMark {
                    TourCoachMarkView(model: model, mark: mark, screen: screen)
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if model.state.tourUpgradePrompt {
                    TourUpgradePromptView(model: model)
                } else if !model.state.tourFinished, let step = model.state.tourStep,
                          step != .s20, step != .s3, step != .s4 {
                    if let mark = model.state.tourCoachMark, !dockTop {
                        TourCoachMarkView(model: model, mark: mark, screen: screen)
                    } else if !dockTop {
                        Button("Skip tour") { model.tour.skip() }.buttonStyle(.borderedProminent).padding()
                    }
                }
            }
            .onAppear { model.tour.start() }
    }
}

extension View {
    func tourOverlay(_ model: AppModel, inRoom: Bool, screen: String) -> some View {
        modifier(TourModifier(model: model, dockTop: inRoom, screen: screen))
    }
}

private struct TourCamera: UIViewControllerRepresentable {
    let cancel: () -> Void
    let completion: (Result<Void, Error>) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(cancel: cancel, completion: completion) }
    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        if UIImagePickerController.isCameraDeviceAvailable(.front) { picker.cameraDevice = .front }
        picker.delegate = context.coordinator
        return picker
    }
    func updateUIViewController(_ picker: UIImagePickerController, context: Context) {}

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let completion: (Result<Void, Error>) -> Void
        let cancel: () -> Void
        init(cancel: @escaping () -> Void, completion: @escaping (Result<Void, Error>) -> Void) {
            self.cancel = cancel
            self.completion = completion
        }
        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) { cancel() }
        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            guard let image = info[.originalImage] as? UIImage else { return }
            Task { @MainActor in
                let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
                guard status == .authorized || status == .limited else {
                    completion(.failure(NSError(domain: "TourCamera", code: 1,
                        userInfo: [NSLocalizedDescriptionKey: "Allow saving photos in Settings, or choose an existing photo."])))
                    return
                }
                do {
                    try await PHPhotoLibrary.shared().performChanges {
                        PHAssetChangeRequest.creationRequestForAsset(from: image)
                    }
                    completion(.success(()))
                } catch { completion(.failure(error)) }
            }
        }
    }
}
