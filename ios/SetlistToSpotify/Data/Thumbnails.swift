import Foundation
import ImageIO
import UniformTypeIdentifiers

/// The **durable floor** of a keepsake (#98). Constants and conventions field for
/// field with Android's `Thumbnails`.
///
/// **Attach** referenced the gallery and copied nothing, so a night emptied
/// whenever the gallery moved underneath it. Copying full-res instead is not the
/// alternative: a night of thirty photos plus a video is ~350 MB, and duplicating a
/// collector's gallery inside the app container is a defect, not a trade-off.
///
/// So two derived copies, and never the original:
///
/// - **Grid tier** — small, durable, kept forever. What survives a gallery
///   deletion, what renders in 2035, what a **Vault** export carries. *Never
///   evicted.*
/// - **Full-screen cache** — larger, evictable, nice-to-have. What makes a lost
///   original degrade to *slightly soft* rather than to *thumbnail*. Absent is a
///   normal state and nothing may depend on it being present.
///
/// The sizes are **cross-platform constants fixed here**, not each platform's
/// defaults. The grid figure in particular is not a rendering detail: it is the
/// input to #104's transfer arithmetic, where ~30–60 KB per item is what makes a
/// backlog trickle feasible at all.
enum Thumbnails {
    /// Longest edge, px. Covers a three-across cell on a 3× phone (~390 px).
    static let gridEdgePx = 512
    static let gridQuality = 0.8

    /// Longest edge, px. Enough for a full-bleed view on a 3× display.
    static let fullEdgePx = 1440
    static let fullQuality = 0.85

    /// Two directories, not one with a naming convention: "evict the cache" has to
    /// be a directory the durable tier is not in — the one rule whose breach
    /// silently destroys the product's core promise.
    static let gridDir = "thumbs"
    static let cacheDir = "thumb-cache"

    /// Derived from the media id (#97), by a convention stated once and shared by
    /// both platforms and by the vault export (#106). A stored path would duplicate
    /// a deterministic function and add a second thing to keep true.
    static func fileName(_ mediaId: String) -> String { "\(mediaId).jpg" }

    /// Application Support, so it is backed up and kept: the durable tier is small
    /// enough that keeping it is the right call.
    static var gridDirectory: URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(gridDir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Caches, and excluded from iCloud backup: this tier is regenerable in
    /// principle and expendable in practice, and backing it up would multiply what
    /// the user pays for storage by nothing they would notice losing.
    static var cacheDirectory: URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent(cacheDir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var url = dir
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }

    static func gridFile(_ mediaId: String) -> URL {
        gridDirectory.appendingPathComponent(fileName(mediaId))
    }

    static func cacheFile(_ mediaId: String) -> URL {
        cacheDirectory.appendingPathComponent(fileName(mediaId))
    }

    /// Gives back what the app can under storage pressure.
    ///
    /// **Eviction touches the cache tier only, ever.** An invariant rather than a
    /// policy, which is why the two tiers are two directories.
    static func evictCache() {
        try? FileManager.default.removeItem(at: cacheDirectory)
    }
}

/// The box a `width`×`height` source scales into for `maxEdge` — the longest edge
/// becomes `maxEdge` and the aspect ratio is kept.
///
/// Never upscales: a source already smaller than `maxEdge` is copied at its own
/// size. Blowing up a small photo costs bytes and adds nothing, and the grid tier's
/// size budget is load-bearing (#104).
///
/// Pure arithmetic, and the seam #98 is tested through: the encoders are idiomatic
/// per platform and differ, but this decision is shared and both platforms assert
/// the same fixed answers for it.
func thumbnailSize(width: Int, height: Int, maxEdge: Int) -> (width: Int, height: Int) {
    guard width > 0, height > 0 else { return (0, 0) }
    let longest = max(width, height)
    guard longest > maxEdge else { return (width, height) }
    let scale = Double(maxEdge) / Double(longest)
    // At least one pixel: a 4000×80 panorama would otherwise round its short edge
    // to zero, and a zero-height image is a failure rather than a thumbnail.
    return (max(1, Int((Double(width) * scale).rounded())),
            max(1, Int((Double(height) * scale).rounded())))
}

/// Source bytes in, JPEG bytes out. Nil when the source cannot be read at all —
/// which is a **failed attach**, not a record with nothing behind it.
///
/// Generated at **Attach** and not lazily at first display: the source is
/// guaranteed readable exactly once, the moment the user picks it, and every way a
/// keepsake breaks (#97) is that guarantee expiring later. On this platform that
/// also means iCloud "Optimise iPhone Storage" — the asset may not be local, the
/// fetch can fail, and this is the one moment the app can still get the bytes, so
/// the failure is loud.
func thumbnailJpeg(from data: Data, maxEdge: Int, quality: Double) -> Data? {
    guard let source = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
    let options: [CFString: Any] = [
        kCGImageSourceCreateThumbnailFromImageAlways: true,
        // Applies the sensor orientation, so a portrait photo is not sideways.
        kCGImageSourceCreateThumbnailWithTransform: true,
        kCGImageSourceThumbnailMaxPixelSize: maxEdge,
    ]
    guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
        return nil
    }
    let out = NSMutableData()
    guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil)
    else { return nil }
    CGImageDestinationAddImage(dest, image, [
        kCGImageDestinationLossyCompressionQuality: quality,
    ] as CFDictionary)
    guard CGImageDestinationFinalize(dest) else { return nil }
    return out as Data
}
