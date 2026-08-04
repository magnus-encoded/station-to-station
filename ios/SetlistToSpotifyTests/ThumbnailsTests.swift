import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import SetlistToSpotify

/// #98's one new seam: source bytes in, thumbnail bytes out, asserted on the
/// dimensions and the approximate size of what comes back.
///
/// The scaling arithmetic is asserted against the same fixed values as Android's
/// `ThumbnailsTest`, rather than against Android — so neither platform can drift by
/// agreeing with itself. The real encode is asserted only here, because XCTest runs
/// on a simulator with a real ImageIO where Android's unit tests get stub Bitmaps.
final class ThumbnailsTests: XCTestCase {

    private var written: [URL] = []

    override func tearDown() {
        written.forEach { try? FileManager.default.removeItem(at: $0) }
        written = []
        super.tearDown()
    }

    // MARK: - The shared decision

    func testTheTierSizesAreTheOnesTransferArithmeticWasBuiltOn() {
        // Not a rendering detail: the grid figure is what makes #104's backlog
        // trickle feasible at all, so changing it changes another spec's premise.
        XCTAssertEqual(512, Thumbnails.gridEdgePx)
        XCTAssertEqual(0.8, Thumbnails.gridQuality)
        XCTAssertEqual(1440, Thumbnails.fullEdgePx)
        XCTAssertEqual(0.85, Thumbnails.fullQuality)
    }

    func testTheLongestEdgeBecomesTheTiersEdgeAndTheRatioIsKept() {
        XCTAssertTrue(thumbnailSize(width: 4000, height: 3000, maxEdge: 512) == (512, 384))
        XCTAssertTrue(thumbnailSize(width: 3000, height: 4000, maxEdge: 512) == (384, 512))
        XCTAssertTrue(thumbnailSize(width: 4000, height: 3000, maxEdge: 1440) == (1440, 1080))
        XCTAssertTrue(thumbnailSize(width: 2000, height: 2000, maxEdge: 512) == (512, 512))
    }

    func testASourceSmallerThanTheTierIsKeptAtItsOwnSize() {
        XCTAssertTrue(thumbnailSize(width: 300, height: 200, maxEdge: 512) == (300, 200))
        XCTAssertTrue(thumbnailSize(width: 512, height: 100, maxEdge: 512) == (512, 100))
    }

    func testAPanoramaKeepsAtLeastOnePixelOfHeight() {
        XCTAssertTrue(thumbnailSize(width: 4000, height: 80, maxEdge: 512) == (512, 10))
        XCTAssertTrue(thumbnailSize(width: 4000, height: 1, maxEdge: 512) == (512, 1))
    }

    func testASourceWithNoSizeAtAllProducesNoBox() {
        XCTAssertTrue(thumbnailSize(width: 0, height: 0, maxEdge: 512) == (0, 0))
    }

    func testTheFilenameIsDerivedFromTheMediaId() {
        XCTAssertEqual("a1b2.jpg", Thumbnails.fileName("a1b2"))
    }

    func testTheTwoTiersLiveInTwoDirectories() {
        // The invariant is structural rather than a policy someone has to remember.
        XCTAssertEqual("thumbs", Thumbnails.gridDir)
        XCTAssertEqual("thumb-cache", Thumbnails.cacheDir)
        XCTAssertNotEqual(Thumbnails.gridDirectory, Thumbnails.cacheDirectory)
    }

    // MARK: - Bytes in, bytes out

    func testBothTiersComeBackAtTheirOwnDimensions() throws {
        let source = try fixtureJpeg(width: 4000, height: 3000)
        let grid = try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx,
                                               quality: Thumbnails.gridQuality))
        let full = try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx,
                                               quality: Thumbnails.fullQuality))
        XCTAssertTrue(try dimensions(of: grid) == (512, 384))
        XCTAssertTrue(try dimensions(of: full) == (1440, 1080))
    }

    func testTheGridTierStaysSmallEnoughToTrickle() throws {
        let source = try fixtureJpeg(width: 4000, height: 3000)
        let grid = try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx,
                                               quality: Thumbnails.gridQuality))
        // A wide band on purpose: the fixture is a synthetic gradient and
        // compresses far better than a real photograph, so the assertion that
        // means something is the order of magnitude. A grid tier that came back
        // at hundreds of kilobytes would break #104's arithmetic; one at nothing
        // would mean the encode silently produced an empty image.
        XCTAssertGreaterThan(grid.count, 500)
        XCTAssertLessThan(grid.count, 150_000)
        // And the durable tier is the smaller of the two, always.
        let full = try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx,
                                               quality: Thumbnails.fullQuality))
        XCTAssertLessThan(grid.count, full.count)
    }

    func testASourceThatCannotBeReadProducesNothingRatherThanAnEmptyRecord() {
        XCTAssertNil(thumbnailJpeg(from: Data("not an image".utf8),
                                   maxEdge: Thumbnails.gridEdgePx, quality: Thumbnails.gridQuality))
    }

    /// The assertion the whole spec exists for.
    func testAThumbnailOutlivesItsSource() throws {
        let mediaId = UUID().uuidString
        let file = Thumbnails.gridFile(mediaId)
        written.append(file)

        var source: Data? = try fixtureJpeg(width: 4000, height: 3000)
        let grid = try XCTUnwrap(thumbnailJpeg(from: source!, maxEdge: Thumbnails.gridEdgePx,
                                               quality: Thumbnails.gridQuality))
        try grid.write(to: file)

        // The gallery tidies up, the phone is replaced, Google Photos frees space —
        // whatever the reason, the original is gone.
        source = nil

        let kept = try Data(contentsOf: file)
        XCTAssertTrue(try dimensions(of: kept) == (512, 384))
    }

    func testEvictionNeverTouchesTheDurableTier() throws {
        let mediaId = UUID().uuidString
        let grid = Thumbnails.gridFile(mediaId)
        let cached = Thumbnails.cacheFile(mediaId)
        written.append(contentsOf: [grid, cached])
        let source = try fixtureJpeg(width: 4000, height: 3000)
        try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.gridEdgePx,
                                    quality: Thumbnails.gridQuality)).write(to: grid)
        try XCTUnwrap(thumbnailJpeg(from: source, maxEdge: Thumbnails.fullEdgePx,
                                    quality: Thumbnails.fullQuality)).write(to: cached)

        Thumbnails.evictCache()

        XCTAssertFalse(FileManager.default.fileExists(atPath: cached.path))
        XCTAssertTrue(FileManager.default.fileExists(atPath: grid.path))
    }

    // MARK: - Fixtures

    /// A synthetic photograph: bands rather than one flat colour, so the JPEG has
    /// something to compress and its size means something.
    private func fixtureJpeg(width: Int, height: Int) throws -> Data {
        let context = try XCTUnwrap(CGContext(
            data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: 0,
            space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue
        ))
        let bands = 16
        for i in 0..<bands {
            context.setFillColor(red: CGFloat(i) / CGFloat(bands), green: 0.35, blue: 0.7, alpha: 1)
            context.fill(CGRect(x: 0, y: CGFloat(i) * CGFloat(height) / CGFloat(bands),
                                width: CGFloat(width), height: CGFloat(height) / CGFloat(bands)))
        }
        let image = try XCTUnwrap(context.makeImage())
        let out = NSMutableData()
        let dest = try XCTUnwrap(CGImageDestinationCreateWithData(
            out, UTType.jpeg.identifier as CFString, 1, nil
        ))
        CGImageDestinationAddImage(dest, image, nil)
        XCTAssertTrue(CGImageDestinationFinalize(dest))
        return out as Data
    }

    private func dimensions(of data: Data) throws -> (Int, Int) {
        let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
        let props = try XCTUnwrap(
            CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
        )
        return (props[kCGImagePropertyPixelWidth] as? Int ?? 0,
                props[kCGImagePropertyPixelHeight] as? Int ?? 0)
    }
}
