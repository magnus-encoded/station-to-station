import AVFoundation
import CryptoKit
import Foundation
import ImageIO
import Photos
import UIKit

/// The bytes half of a LAN reconcile (#265): what gets hashed to decide whether to send,
/// what gets exported to send, and where what arrives lands. The iOS counterpart of the
/// `PhotoRepository` methods Android's `ContactExchange` leans on, kept out of
/// `PhotoLibrary.swift` because none of it is **Attach**'s business.
extension PhotoLibrary {

    // MARK: - Hashing

    /// The content hash `OfferedMedia.hash` and `GalleryItem.hash` compare — same bytes,
    /// same hash, is all either field's contract asks for. SHA-256 over the asset's
    /// original bytes, streamed, so nothing is copied just to be measured.
    ///
    /// ponytail: photos only. Android hashes a video's first 64 KiB plus its byte count,
    /// which it can do because MediaStore hands it the size for free; PhotoKit has no
    /// supported way to ask a `PHAssetResource` how big it is without reading it, and
    /// reading a 233 MB recording to decide whether to send 233 MB is the exact trade
    /// Android's own comment says to avoid. So a video simply never matches from the
    /// gallery and always transfers. Upgrade path if that bites: read the first 64 KiB and
    /// use `AVURLAsset`'s `.totalFileSize` for the length.
    static func mediaHash(assetId: String) async -> String? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject,
              asset.mediaType == .image,
              let resource = originalResource(asset)
        else { return nil }

        var digest = SHA256()
        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        let ok = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let answered = ReconcileOnce()
            PHAssetResourceManager.default().requestData(
                for: resource, options: options,
                dataReceivedHandler: { digest.update(data: $0) },
                completionHandler: { error in
                    if answered.claim() { continuation.resume(returning: error == nil) }
                }
            )
        }
        guard ok else { return nil }
        return digest.finalize().map { String(format: "%02x", $0) }.joined()
    }

    /// My own library, from the nights I have records of, ready for the plan to match a
    /// peer's offer against. The date narrowing is a prefilter and nothing more — the
    /// match itself is by hash, because a timestamp alone would happily grab a
    /// neighbouring frame from the same minute.
    static func galleryItems(dates: [ClosedRange<Int64>]) async -> [GalleryItem] {
        var assetIds: [String] = []
        var seen = Set<String>()
        for window in dates {
            // Zero is PhotoKit's "no limit", not "nothing": the default on
            // `assetsFromNight` is sized for suggesting a cover photo, not for reconcile
            // matching, and truncating here would send bytes the peer already holds
            // locally just because they fell past position 20.
            for id in assetsFromNight(window, limit: 0) where seen.insert(id).inserted {
                assetIds.append(id)
            }
        }
        var items: [GalleryItem] = []
        for id in assetIds {
            if let hash = await mediaHash(assetId: id) {
                items.append(GalleryItem(ref: id, hash: hash))
            }
        }
        return items
    }

    // MARK: - Sending

    /// Where exported originals wait to be sent. Under Caches and cleared when the
    /// Exchange screen closes: these are copies of bytes the library still holds, and
    /// keeping them would double a night's storage for nothing.
    private static var outboxDirectory: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("reconcile-outbox")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func clearReconcileOutbox() {
        try? FileManager.default.removeItem(at: outboxDirectory)
    }

    /// The full-resolution original as a file the wire can stream, or nil if the asset is
    /// gone. A file and not a stream because a `PHAsset`'s bytes are not a path — PhotoKit
    /// will only write them somewhere, and the declared length a frame header needs has to
    /// be known before the first byte goes out.
    static func reconcileExport(assetId: String, mediaId: String) async -> URL? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject,
              let resource = originalResource(asset)
        else { return nil }

        let destination = outboxDirectory.appendingPathComponent(
            "\(mediaId).\(asset.mediaType == .video ? "mp4" : "jpg")"
        )
        // Already exported this visit: several Contacts on the same WiFi asking for the
        // same photo should cost one export, not one each.
        if FileManager.default.fileExists(atPath: destination.path) { return destination }

        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true
        let written = await withCheckedContinuation { (continuation: CheckedContinuation<Bool, Never>) in
            let answered = ReconcileOnce()
            PHAssetResourceManager.default().writeData(for: resource, toFile: destination, options: options) { error in
                if answered.claim() { continuation.resume(returning: error == nil) }
            }
        }
        guard written else {
            try? FileManager.default.removeItem(at: destination)
            return nil
        }
        return destination
    }

    // MARK: - Receiving

    /// Where a received item's bytes land: Application Support, so they are backed up and
    /// kept. These are the only copy — the sender's gallery is not somewhere this device
    /// can go back to.
    static func receivedMediaFile(id: String, kind: String) -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("gig_media")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("\(id).\(kind == StoredMedia.Kind.video ? "mp4" : "jpg")")
    }

    /// Both thumbnail tiers for something that arrived through a reconcile rather than
    /// through **Attach**.
    ///
    /// Not optional politeness: the grid draws from the durable tier and never from `ref`
    /// (#98), so an item that skipped this is a record with a blank cell where the picture
    /// is. That applies to *both* ways an item can land, which is easy to miss — bytes
    /// that came over the wire arrive as a file, and a hash match against my own library
    /// arrives as an asset id under the sender's media id, with no thumbnail written for
    /// that id either. A video gets its poster frame, which is what stands in for it in a
    /// grid.
    static func writeReconcileTiers(mediaId: String, ref: String) async {
        let source: Data?
        if FileManager.default.fileExists(atPath: ref) {
            source = posterSource(URL(fileURLWithPath: ref))
        } else {
            source = await preview(assetId: ref, edgePx: Thumbnails.fullEdgePx)?
                .jpegData(compressionQuality: 0.95)
        }
        guard let source else { return }
        if let full = thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx,
                                    quality: Thumbnails.fullQuality) {
            try? full.write(to: Thumbnails.cacheFile(mediaId))
        }
        if let grid = thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx,
                                    quality: Thumbnails.gridQuality) {
            try? grid.write(to: Thumbnails.gridFile(mediaId))
        }
    }

    /// Encoded image bytes the thumbnail tiers can be cut from: the file itself for a
    /// photo, one frame for a video.
    private static func posterSource(_ url: URL) -> Data? {
        if let data = try? Data(contentsOf: url), CGImageSourceCreateWithData(data as CFData, nil) != nil {
            return data
        }
        let generator = AVAssetImageGenerator(asset: AVURLAsset(url: url))
        generator.appliesPreferredTrackTransform = true
        guard let frame = try? generator.copyCGImage(at: .zero, actualTime: nil) else { return nil }
        return UIImage(cgImage: frame).jpegData(compressionQuality: 0.95)
    }

    /// The resource holding the bytes as the camera wrote them — what both the hash and
    /// the export must read, or the two would describe different files.
    private static func originalResource(_ asset: PHAsset) -> PHAssetResource? {
        let resources = PHAssetResource.assetResources(for: asset)
        let wanted: PHAssetResourceType = asset.mediaType == .video ? .video : .photo
        return resources.first { $0.type == wanted } ?? resources.first
    }
}

/// The manifest a **Contact** is offered, with real content hashes on it.
///
/// `contactManifest` decides *what* is offered and is pure — it never touches PhotoKit,
/// which is why it is testable and why this second pass exists at all. The hash is the
/// only field that needs the bytes, and it is what lets the far end recognise a photo it
/// already holds under a different id instead of pulling it across the room again.
///
/// `bytes` is deliberately left at zero, unlike Android's. MediaStore hands Android a
/// size for free; PhotoKit has no supported way to ask for one without reading the file,
/// and nothing reads this field — the real length rides each item's own header, where a
/// receiver actually needs it.
func hashedContactManifest(_ cache: TimelineCache, me: String) async -> HandoverManifest {
    var manifest = contactManifest(cache, me: me)
    var refById: [String: String] = [:]
    for items in cache.gigMedia.values {
        for item in items { refById[item.id] = item.ref }
    }
    var hashed: [OfferedMedia] = []
    for var item in manifest.media {
        if let ref = refById[item.id]?.nilIfBlank {
            item.hash = await PhotoLibrary.mediaHash(assetId: ref) ?? ""
        }
        hashed.append(item)
    }
    manifest.media = hashed
    return manifest
}

/// A completion handler that may fire more than once, against a continuation that must
/// not be resumed more than once. `PhotoLibrary`'s own `Answered` is private to its file.
private final class ReconcileOnce {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
