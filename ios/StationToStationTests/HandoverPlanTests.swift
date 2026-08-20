import XCTest
@testable import StationToStation

/// The device handover decision (#142), the Swift twin of Android's `HandoverTest`. No
/// radio, no socket, no keychain, no device: the union of two of one person's phones is
/// exactly the part that has to be right, and exactly the part that needs neither.
///
/// Names are invented: this repository is public and real timeline data never enters a
/// fixture.
final class HandoverPlanTests: XCTestCase {

    private let all = categoriesFor(contact: false)

    private func photo(_ id: String, personal: Bool = false,
                       kind: String = StoredMedia.Kind.photo) -> StoredMedia {
        StoredMedia(id: id, kind: kind, ref: "asset/mine/\(id)", personal: personal)
    }

    private func gig(_ id: String, setlistId: String? = nil, createdAt: Int64 = 0,
                     artist: String = "Paper Cranes") -> StoredGig {
        StoredGig(id: id, date: "12-06-2026", artist: artist, venue: "The Long Room",
                  setlistId: setlistId, createdAt: createdAt)
    }

    private func cache(gigs: [StoredGig] = [], media: [String: [StoredMedia]] = [:]) -> TimelineCache {
        var c = TimelineCache()
        c.gigs = Dictionary(uniqueKeysWithValues: gigs.map { ($0.id, $0) })
        c.gigMedia = media
        return c
    }

    /// A source manifest with the hashes the device layer would have filled in.
    private func offer(_ cache: TimelineCache, allow: Set<String>? = nil,
                       hashes: [String: String] = [:]) -> HandoverManifest {
        var manifest = deviceManifest(cache, allow: allow ?? all)
        manifest.media = manifest.media.map {
            var item = $0
            item.hash = hashes[$0.id] ?? ""
            return item
        }
        return manifest
    }

    // MARK: - The verdict is an argument

    /// A manifest that does not verify writes *nothing* — total by construction, not by a
    /// caller remembering to check. The highest-stakes bit in the payload is `personal`.
    func testAnUnverifiedManifestYieldsAnEmptyPlan() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])

        let plan = handoverPlan(mine: TimelineCache(), offer: offer(theirs), allow: all, verified: false)

        XCTAssertEqual(plan.merged.gigs.count, 0)
        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertTrue(plan.fromGallery.isEmpty)
    }

    // MARK: - The union

    /// Neither device is a superset: the old phone has the history, the new one may hold
    /// the only copy of last night. Picking a winner by volume is a silent discard.
    func testBothSidesSurviveAUnion() {
        let mine = cache(gigs: [gig("g-mine", setlistId: "sl-mine")],
                         media: ["g-mine": [photo("p-mine")]])
        let theirs = cache(gigs: [gig("g-theirs", setlistId: "sl-theirs")],
                           media: ["g-theirs": [photo("p-theirs")]])

        let plan = handoverPlan(mine: mine, offer: offer(theirs), allow: all, verified: true)

        XCTAssertEqual(Set(plan.merged.gigs.keys), ["g-mine", "g-theirs"])
        XCTAssertEqual(plan.merged.gigMedia["g-mine"]?.map(\.id), ["p-mine"])
        // Not landed yet: its bytes have not arrived, and a record pointing at nothing is
        // the dead reference #97 exists to prevent.
        XCTAssertEqual(plan.request, ["p-theirs"])
        XCTAssertNil(plan.merged.gigMedia["g-theirs"])
    }

    /// Two records of one night collapse on `setlistId`, and the older id wins — so two
    /// phones reach the same answer with no synchronised clock.
    func testOneNightUnderTwoIdsCollapsesOntoTheOlder() {
        let mine = cache(gigs: [gig("g-new", setlistId: "sl-1", createdAt: 500)],
                         media: ["g-new": [photo("p-mine")]])
        var theirGig = gig("g-old", setlistId: "sl-1", createdAt: 100)
        theirGig.venue = ""
        let theirs = cache(gigs: [theirGig], media: ["g-old": [photo("p-theirs")]])

        let plan = handoverPlan(mine: mine, offer: offer(theirs), allow: all, verified: true)

        XCTAssertEqual(Set(plan.merged.gigs.keys), ["g-old"])
        // My own media moved onto their surviving id rather than being orphaned on mine.
        XCTAssertEqual(plan.merged.gigMedia["g-old"]?.map(\.id), ["p-mine"])
        // The blank field on the survivor takes the other record's answer.
        XCTAssertEqual(plan.merged.gigs["g-old"]?.venue, "The Long Room")
    }

    /// A **Log** typed at a gig must not vanish because the other phone had none.
    func testTheLongerLogSurvivesAndStaysOpen() {
        var mine = cache(gigs: [gig("g1", setlistId: "sl-1")])
        mine.gigLogs = ["g1": StoredLog(songs: ["Amber Line"], closed: true)]
        var theirs = cache(gigs: [gig("g1", setlistId: "sl-1")])
        theirs.gigLogs = ["g1": StoredLog(songs: ["Amber Line", "Second Sun"], closed: false)]

        let plan = handoverPlan(mine: mine, offer: offer(theirs), allow: all, verified: true)

        XCTAssertEqual(plan.merged.gigLogs["g1"]?.songs, ["Amber Line", "Second Sun"])
        // Never upgraded: a merge must not close a log nobody closed.
        XCTAssertEqual(plan.merged.gigLogs["g1"]?.closed, false)
    }

    /// A check-in reached by one route must not be flattened back to `planned` by the other.
    func testAttendanceNeverDowngrades() {
        var mine = cache(gigs: [gig("g1", setlistId: "sl-1")])
        mine.gigAttendance = ["g1": StoredAttendance(provenance: "planned")]
        var theirs = cache(gigs: [gig("g1", setlistId: "sl-1")])
        theirs.gigAttendance = ["g1": StoredAttendance(provenance: "checked_in", checkedInAt: 42)]

        let plan = handoverPlan(mine: mine, offer: offer(theirs), allow: all, verified: true)

        XCTAssertEqual(plan.merged.gigAttendance["g1"]?.provenance, "checked_in")
        XCTAssertEqual(plan.merged.gigAttendance["g1"]?.checkedInAt, 42)
    }

    // MARK: - What travels, and what does not

    /// The tick list is applied at construction: an item the source did not offer never
    /// reaches the wire, so no receiver-side filter can forget to apply it.
    func testTheVaultStaysHomeWhenItsBoxIsUnticked() {
        let theirs = cache(gigs: [gig("g1")],
                           media: ["g1": [photo("p1"), photo("secret", personal: true)]])

        let ticked: Set<String> = [categorySetlists, StoredMedia.Kind.photo]
        let manifest = deviceManifest(theirs, allow: ticked)

        XCTAssertEqual(manifest.media.map(\.id), ["p1"])
        XCTAssertEqual(manifest.timeline.gigMedia["g1"]?.map(\.id), ["p1"])
    }

    /// Facts unticked means media only — the nights are still named, because an item whose
    /// gig the receiver cannot find has nowhere to hang, but nothing else travels.
    func testMediaWithoutFactsCarriesTheNightsAndNothingElse() {
        var theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])
        theirs.gigLogs = ["g1": StoredLog(songs: ["Amber Line"])]

        let manifest = deviceManifest(theirs, allow: [StoredMedia.Kind.photo])

        XCTAssertEqual(Set(manifest.timeline.gigs.keys), ["g1"])
        XCTAssertTrue(manifest.timeline.gigLogs.isEmpty)
    }

    /// A photograph whose night did not come across has nowhere to hang.
    func testAnItemWhoseNightIsNotHereIsWithheld() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])
        let manifest = deviceManifest(theirs, allow: [StoredMedia.Kind.photo])

        let plan = handoverPlan(mine: TimelineCache(), offer: manifest,
                                allow: [StoredMedia.Kind.photo], verified: true)

        XCTAssertEqual(plan.withheld, ["p1"])
        XCTAssertTrue(plan.request.isEmpty)
    }

    /// A **Note** is complete the moment the manifest is: text and a **Verdict**, no bytes.
    /// Asking for it would be asking for zero bytes and then dropping it when zero arrived.
    func testANoteRidesTheManifestAndIsNeverRequested() {
        var note = photo("n1", kind: StoredMedia.Kind.note)
        note.ref = ""
        note.text = "The encore was the whole point."
        note.verdict = StoredMedia.Verdict.doubleUp
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [note]])

        let plan = handoverPlan(mine: TimelineCache(), offer: offer(theirs), allow: all, verified: true)

        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertEqual(plan.fromGallery["n1"], "")
        let landed = plan.merged.gigMedia["g1"]?.first
        XCTAssertEqual(landed?.text, "The encore was the whole point.")
        XCTAssertEqual(landed?.verdict, StoredMedia.Verdict.doubleUp)
    }

    /// Two things that could not be hashed are not the same picture. Without this rule
    /// every unhashable item matches whichever unhashable thing the gallery listed first.
    func testAnEmptyHashMatchesNothing() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])

        let plan = handoverPlan(mine: TimelineCache(), offer: offer(theirs), allow: all,
                                verified: true,
                                gallery: [GalleryItem(ref: "asset/mine/other", hash: "")])

        XCTAssertEqual(plan.request, ["p1"])
        XCTAssertTrue(plan.fromGallery.isEmpty)
    }

    /// The bytes are already here under another name: matched by hash, and nothing crosses
    /// the wire for it — while attribution still comes off the manifest.
    func testAHashMatchResolvesLocallyAndKeepsAttribution() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])
        var manifest = offer(theirs, hashes: ["p1": "same-bytes"])
        manifest.media = manifest.media.map { var i = $0; i.from = "their-key"; return i }

        let plan = handoverPlan(mine: TimelineCache(), offer: manifest, allow: all, verified: true,
                                gallery: [GalleryItem(ref: "asset/mine/copy", hash: "same-bytes")])

        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertEqual(plan.fromGallery["p1"], "asset/mine/copy")
        XCTAssertEqual(plan.merged.gigMedia["g1"]?.first?.from, "their-key")
    }

    /// A hash on the refuse list never transfers, and is reported apart from what was
    /// simply not offered: "you did not send this" and "I will not take it" differ.
    func testARefusedHashIsNeverRequested() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])

        let plan = handoverPlan(mine: TimelineCache(), offer: offer(theirs, hashes: ["p1": "nope"]),
                                allow: all, verified: true, refusedHashes: ["nope"])

        XCTAssertEqual(plan.refused, ["p1"])
        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertTrue(plan.withheld.isEmpty)
    }

    /// The second pass: same function, same offer, plus what actually turned up. A
    /// cancelled transfer's smaller `received` simply yields a smaller union.
    func testTheSecondPassAttachesOnlyWhatArrived() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1"), photo("p2")]])
        let manifest = offer(theirs)

        let plan = handoverPlan(mine: TimelineCache(), offer: manifest, allow: all, verified: true,
                                received: ["p1": "file:///received/p1.jpg"])

        XCTAssertEqual(plan.merged.gigMedia["g1"]?.map(\.id), ["p1"])
        XCTAssertEqual(plan.merged.gigMedia["g1"]?.first?.ref, "file:///received/p1.jpg")
        XCTAssertEqual(plan.request, ["p2"])
    }

    /// A truncated item list otherwise looks exactly like a smaller library. The counts
    /// are computed inside the seal (`sealManifest`), which is what makes this worth
    /// checking at all.
    func testCountsThatDisagreeWithTheItemsAreFlagged() {
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [photo("p1")]])
        var manifest = offer(theirs)
        manifest.counts = [StoredMedia.Kind.photo: 48]

        let plan = handoverPlan(mine: TimelineCache(), offer: manifest, allow: all, verified: true)

        XCTAssertTrue(plan.countMismatch)
        // Flagged, not refused: what arrived is still here.
        XCTAssertEqual(plan.request, ["p1"])
    }

    /// An id that would escape the directory it is written into is not an id. Checked in
    /// the plan because that is the one door every peer-supplied id comes through.
    func testAnUnsafeMediaIdIsIgnoredEntirely() {
        var bad = photo("../../etc/passwd")
        bad.ref = "asset/mine/x"
        let theirs = cache(gigs: [gig("g1")], media: ["g1": [bad]])

        let plan = handoverPlan(mine: TimelineCache(), offer: offer(theirs), allow: all, verified: true)

        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertTrue(plan.merged.gigMedia.isEmpty)
    }
}
