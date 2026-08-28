import AVFoundation
import Photos
import UIKit

/// PhotoKit, on the near side of **Attach** — the plumbing half (ADR-0001). The
/// rules it runs are `PhotoWindow`'s and the tiers it writes are `Thumbnails`';
/// what is here is the device.
///
/// The counterpart of Android's `PhotoRepository`, and deliberately not a port of
/// it: MediaStore cursors become `PHFetchOptions`, a `content://` URI becomes a
/// `PHAsset.localIdentifier`. Neither reference is portable and neither is
/// expected to be — the **Pointer** is what crosses, when sharing lands.
enum PhotoLibrary {

    /// Whether the library can be read *without asking*. The suggestion row is
    /// silent when it cannot: a permission prompt only ever follows a tap, so the
    /// picker is what asks.
    static var isAuthorized: Bool {
        let status = PHPhotoLibrary.authorizationStatus(for: .readWrite)
        return status == .authorized || status == .limited
    }

    /// Reading a picked asset needs library authorization even though the picker
    /// itself does not. Limited access is fine and is the iOS twin of Android's
    /// "selected photos only": a PHPicker selection joins the limited set, so the
    /// bytes are readable at exactly the moment #98 needs them.
    static func authorize() async -> Bool {
        if PHPhotoLibrary.authorizationStatus(for: .readWrite) == .notDetermined {
            let granted = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
            return granted == .authorized || granted == .limited
        }
        return isAuthorized
    }

    /// **Attach**: the picked assets become records with both thumbnail tiers
    /// already written. Returns what landed, and how many could not be read.
    ///
    /// A source that cannot be read is a *failed attach* with a reason on screen,
    /// not a record with nothing behind it. On this platform that is not
    /// hypothetical: with "Optimise iPhone Storage" the bytes live in iCloud, the
    /// fetch is a network call, and it can simply fail. This is the last moment
    /// the app can still get them.
    ///
    /// ponytail: sequential, which is the bounded queue — the same call Android
    /// made. Widen it if attaching a night's worth is ever felt.
    static func attach(assetIds: [String]) async -> (media: [StoredMedia], failed: Int) {
        guard await authorize() else { return ([], assetIds.count) }
        var byId: [String: PHAsset] = [:]
        PHAsset.fetchAssets(withLocalIdentifiers: assetIds, options: nil)
            .enumerateObjects { asset, _, _ in byId[asset.localIdentifier] = asset }

        var fresh: [StoredMedia] = []
        var failed = 0
        // Walked in the order the picker returned, not the order PhotoKit fetched:
        // the night's arrangement is the user's (#75).
        for id in assetIds {
            // Assigned before the bytes are read, because it names the files they
            // are written to. A UUID and not a content hash, for #97's reason.
            let mediaId = UUID().uuidString.lowercased()
            guard let asset = byId[id],
                  let source = await sourceJpeg(asset),
                  writeTiers(mediaId: mediaId, source: source)
            else { failed += 1; continue }
            fresh.append(StoredMedia(
                id: mediaId,
                kind: asset.mediaType == .video ? StoredMedia.Kind.video : StoredMedia.Kind.photo,
                ref: asset.localIdentifier,
                capturedAt: capturedAtMs(taken: millis(asset.creationDate),
                                         added: millis(asset.modificationDate))
            ))
        }
        return (fresh, failed)
    }

    /// The library's own photos from that night, offered before the picker is —
    /// the same suggestion Android makes, through the same window rule. Ids only;
    /// the thumbnails are drawn lazily by whatever shows them.
    static func assetsFromNight(_ window: ClosedRange<Int64>, limit: Int = 20) -> [String] {
        let options = PHFetchOptions()
        options.predicate = NSPredicate(
            format: "creationDate >= %@ AND creationDate <= %@",
            Date(timeIntervalSince1970: Double(window.lowerBound) / 1000) as NSDate,
            Date(timeIntervalSince1970: Double(window.upperBound) / 1000) as NSDate
        )
        options.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]
        options.fetchLimit = limit
        var ids: [String] = []
        PHAsset.fetchAssets(with: options).enumerateObjects { asset, _, _ in
            // The predicate narrowed it; the shared rule decides it, so the gate is
            // the one both platforms assert rather than a query string.
            if isInPhotoWindow(window, taken: millis(asset.creationDate),
                               added: millis(asset.modificationDate)) {
                ids.append(asset.localIdentifier)
            }
        }
        return ids
    }

    /// The durable copy, if it is there. The grid draws from this and not from the
    /// library, which is the whole point of #98: a night still renders when the
    /// original is gone.
    static func gridImage(_ mediaId: String) -> UIImage? {
        UIImage(contentsOfFile: Thumbnails.gridFile(mediaId).path)
    }

    /// A preview of something not attached yet, for the suggestion row. Comes from
    /// the library because there is no owned copy of it — that is the difference
    /// between a suggestion and a keepsake.
    static func preview(assetId: String, edgePx: Int) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject
        else { return nil }
        return await requestImage(asset, edgePx: edgePx, allowNetwork: false)
    }

    /// Whether this asset is a clip. The cover path asks because a clip has no one
    /// picture until a frame is chosen — every other reader here treats it as its
    /// poster frame and never needs to know.
    static func isVideo(assetId: String) -> Bool {
        PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil)
            .firstObject?.mediaType == .video
    }

    /// How long the clip runs, so a scrubber knows what it is scrubbing across.
    /// Read off the asset rather than the file: PhotoKit already knows, and asking
    /// the file would fetch bytes that may still be in iCloud to learn a number.
    static func videoDurationMs(assetId: String) -> Int64 {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject,
              asset.mediaType == .video
        else { return 0 }
        return Int64(asset.duration * 1000)
    }

    /// The frame at `atMs`, for scrubbing a clip to the picture worth keeping.
    ///
    /// Twin of Android's `videoFrameAt`, and the same choice at its centre: zero
    /// tolerance rather than the nearest sync frame. Sync frames can sit seconds
    /// apart, so snapping to them would make a scrubber feel stuck.
    static func videoFrame(assetId: String, atMs: Int64, edgePx: Int) async -> UIImage? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject,
              asset.mediaType == .video
        else { return nil }
        let options = PHVideoRequestOptions()
        options.isNetworkAccessAllowed = true
        options.deliveryMode = .highQualityFormat
        let video: AVAsset? = await withCheckedContinuation { continuation in
            let answered = Answered()
            PHImageManager.default().requestAVAsset(forVideo: asset, options: options) { av, _, _ in
                guard answered.claim() else { return }
                continuation.resume(returning: av)
            }
        }
        guard let video else { return nil }
        let generator = AVAssetImageGenerator(asset: video)
        // The video twin of `upright`: a clip filmed sideways carries its rotation in
        // the track transform, and a cover that ignores it is a cover on its side.
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: edgePx, height: edgePx)
        generator.requestedTimeToleranceBefore = .zero
        generator.requestedTimeToleranceAfter = .zero
        let time = CMTime(value: CMTimeValue(max(0, atMs)), timescale: 1000)
        return await withCheckedContinuation { continuation in
            let answered = Answered()
            generator.generateCGImagesAsynchronously(forTimes: [NSValue(time: time)]) { _, image, _, _, _ in
                guard answered.claim() else { return }
                continuation.resume(returning: image.map(UIImage.init(cgImage:)))
            }
        }
    }

    /// The photo as Spotify wants a cover: a square JPEG small enough that its
    /// base64 form stays inside the 256 KB the upload endpoint accepts. Base64
    /// costs a third on top, so the JPEG itself is held well under that.
    ///
    /// Twin of Android's `PhotoRepository.coverJpeg` — same edge, same byte
    /// ceiling, same downscale-until-it-fits loop.
    ///
    /// `frameMs` is the frame a clip was scrubbed to, and means nothing for a photo.
    /// A clip's cover is one picture out of it, which is why the choosing happens
    /// before the playlist is made rather than after.
    static func coverJpeg(assetId: String, frameMs: Int64 = 0) async -> Data? {
        guard let asset = PHAsset.fetchAssets(withLocalIdentifiers: [assetId], options: nil).firstObject
        else { return nil }
        let picture = asset.mediaType == .video
            ? await videoFrame(assetId: assetId, atMs: frameMs, edgePx: coverEdgePx)
            : await requestImage(asset, edgePx: coverEdgePx, allowNetwork: true)
        guard let picture, let square = centerCropSquare(picture) else { return nil }
        var quality: CGFloat = 0.9
        var data = square.jpegData(compressionQuality: quality)
        while let d = data, d.count > maxCoverJpegBytes, quality > 0.4 {
            quality -= 0.1
            data = square.jpegData(compressionQuality: quality)
        }
        guard let data, data.count <= maxCoverJpegBytes else { return nil }
        return data
    }

    private static let coverEdgePx = 640
    private static let maxCoverJpegBytes = 180_000

    private static func centerCropSquare(_ image: UIImage) -> UIImage? {
        guard let cg = image.cgImage else { return nil }
        let edge = min(cg.width, cg.height)
        let x = (cg.width - edge) / 2
        let y = (cg.height - edge) / 2
        guard let cropped = cg.cropping(to: CGRect(x: x, y: y, width: edge, height: edge)) else { return nil }
        return UIImage(cgImage: cropped, scale: image.scale, orientation: image.imageOrientation)
    }

    /// Whether this app holds the last picture of a keepsake.
    ///
    /// The iOS shape of Android's `ownsBytes`, and it answers the same question a
    /// different way. Android can own the bytes outright — a copy under its own
    /// files directory — while every keepsake here is a pointer into the library
    /// plus the derived tiers `Thumbnails` writes. So the app holds the only copy
    /// exactly when the library no longer has the asset and the durable tier still
    /// does: the original was deleted out from under us, and #98's copy is what has
    /// been drawing that night ever since.
    ///
    /// Asked before a delete, and never to decide what to draw — a night renders
    /// from the durable tier whether or not the original is still there.
    static func holdsOnlyCopy(mediaId: String, ref: String) -> Bool {
        guard FileManager.default.fileExists(atPath: Thumbnails.gridFile(mediaId).path)
        else { return false }
        return PHAsset.fetchAssets(withLocalIdentifiers: [ref], options: nil).firstObject == nil
    }

    /// Removing means removing: the record goes, and so do the bytes it owned.
    static func deleteThumbnails(_ mediaId: String) {
        try? FileManager.default.removeItem(at: Thumbnails.gridFile(mediaId))
        try? FileManager.default.removeItem(at: Thumbnails.cacheFile(mediaId))
    }

    // MARK: - The bytes

    /// One read of the source, at the larger tier's edge. A video answers with its
    /// poster frame, which is what stands in for it in a grid.
    private static func sourceJpeg(_ asset: PHAsset) async -> Data? {
        guard let image = await requestImage(asset, edgePx: Thumbnails.fullEdgePx, allowNetwork: true)
        else { return nil }
        // Near-lossless, and re-encoded once per tier below. The alternative is two
        // reads of a file that may be arriving from iCloud.
        return image.jpegData(compressionQuality: 0.95)
    }

    private static func requestImage(_ asset: PHAsset, edgePx: Int, allowNetwork: Bool) async -> UIImage? {
        let options = PHImageRequestOptions()
        // "Optimise iPhone Storage" means the bytes may not be local at all.
        options.isNetworkAccessAllowed = allowNetwork
        // One result, not a degraded placeholder followed by the real thing.
        options.deliveryMode = .highQualityFormat
        // resizeMode left at .none: aspectFit with an exact resize would pad or
        // crop, and the tiers do their own scaling from whatever comes back.
        let target = CGSize(width: edgePx, height: edgePx)
        return await withCheckedContinuation { continuation in
            let answered = Answered()
            PHImageManager.default().requestImage(
                for: asset, targetSize: target, contentMode: .aspectFit, options: options
            ) { image, info in
                // highQualityFormat delivers once, but the handler's contract does
                // not promise it — resuming a continuation twice is a crash.
                if (info?[PHImageResultIsDegradedKey] as? Bool) == true { return }
                guard answered.claim() else { return }
                continuation.resume(returning: image)
            }
        }
    }

    /// The cache tier first, so a failure to write it still leaves the durable tier
    /// as the last thing that happened. Only the durable tier's success is the
    /// attach's success — the cache is allowed to be absent, always.
    private static func writeTiers(mediaId: String, source: Data) -> Bool {
        if let full = thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx,
                                    quality: Thumbnails.fullQuality) {
            try? full.write(to: Thumbnails.cacheFile(mediaId))
        }
        guard let grid = thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx,
                                       quality: Thumbnails.gridQuality),
              (try? grid.write(to: Thumbnails.gridFile(mediaId))) != nil
        else { return false }
        return true
    }

    private static func millis(_ date: Date?) -> Int64? { date.map(epochMs) }
}

/// One-shot latch for a callback that may fire more than once.
private final class Answered {
    private var done = false
    func claim() -> Bool {
        if done { return false }
        done = true
        return true
    }
}
