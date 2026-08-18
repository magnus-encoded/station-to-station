import XCTest
@testable import StationToStation

/// The LAN reconcile decision between two **Contacts** (#265), the Swift twin of
/// Android's `ContactReconcileTest`. No radio, no socket, no Keychain, no device — which
/// is the whole reason this logic was pulled out into a pure function to begin with.
///
/// Names are invented: this repository is public and real timeline data never enters a
/// fixture.
final class ContactReconcileTests: XCTestCase {

    private func photo(_ id: String, ref: String = "asset/mine/") -> StoredMedia {
        StoredMedia(id: id, kind: StoredMedia.Kind.photo, ref: ref + id)
    }

    private func offered(_ id: String, hash: String) -> OfferedMedia {
        OfferedMedia(id: id, gigId: "a", kind: StoredMedia.Kind.photo, hash: hash, from: "their-key")
    }

    private func cache(gigs: [String: StoredGig] = [:],
                       gigMedia: [String: [StoredMedia]] = [:]) -> TimelineCache {
        var c = TimelineCache()
        c.gigs = gigs
        c.gigMedia = gigMedia
        return c
    }

    private func manifest(timeline: TimelineCache = TimelineCache(),
                          media: [OfferedMedia]) -> HandoverManifest {
        HandoverManifest(timeline: timeline, media: media)
    }

    // MARK: - The plan

    /// The fail-safe posture, and the reason `verified` is an argument rather than
    /// something computed in here: a peer that has not proven who they are gets nothing,
    /// by construction rather than by an upstream caller remembering to check.
    func testAnUnverifiedPeerYieldsAnEmptyPlan() {
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: false)

        XCTAssertTrue(plan.held.isEmpty)
        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testAnItemAlreadyHeldByIdIsNeitherRequestedNorPulledFromTheGallery() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: mine, offer: offer, verified: true)

        XCTAssertEqual(["m1"], plan.held)
        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testAHashMatchInTheGalleryResolvesWithoutARequest() {
        let offer = manifest(media: [offered("m1", hash: "h1")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "h1")]

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer,
                                        verified: true, gallery: gallery)

        XCTAssertEqual(["m1": "asset/gallery/x"], plan.fromGallery)
        XCTAssertTrue(plan.request.isEmpty)
    }

    func testUnmatchedMediaIsRequested() {
        let offer = manifest(media: [offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true)

        XCTAssertEqual(["m1"], plan.request)
    }

    /// A video hashes to nothing on this platform (see `PhotoLibrary.mediaHash`), so an
    /// empty hash must never be treated as a match — every empty-hashed item would
    /// otherwise resolve to whichever unhashable thing the gallery listed first.
    func testAnEmptyHashNeverMatchesTheGallery() {
        let offer = manifest(media: [offered("m1", hash: "")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "")]

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer,
                                        verified: true, gallery: gallery)

        XCTAssertTrue(plan.fromGallery.isEmpty)
        XCTAssertEqual(["m1"], plan.request)
    }

    /// A **Note** is text and a **Verdict**, and both already rode the manifest. Asking
    /// for it would be asking for zero bytes — and then dropping it when zero bytes
    /// arrived, which is exactly what used to happen.
    func testANoteNeedsNothingFetchedAndIsNeverRequested() {
        let note = OfferedMedia(id: "n1", gigId: "a", kind: StoredMedia.Kind.note,
                                hash: "", from: "their-key", text: "the encore was the point")
        let offer = manifest(media: [note, offered("m1", hash: "h1")])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true)

        XCTAssertEqual(["n1"], plan.noBytes)
        XCTAssertEqual(["m1"], plan.request)
    }

    /// A note I already hold is still held: `noBytes` is "nothing to fetch", not "always
    /// take it again".
    func testANoteAlreadyHeldStaysHeld() {
        var note = photo("n1")
        note.kind = StoredMedia.Kind.note
        let mine = cache(gigMedia: ["a": [note]])
        let offer = manifest(media: [OfferedMedia(id: "n1", gigId: "a",
                                                  kind: StoredMedia.Kind.note, from: "their-key")])

        let plan = contactReconcilePlan(mine: mine, offer: offer, verified: true)

        XCTAssertEqual(["n1"], plan.held)
        XCTAssertTrue(plan.noBytes.isEmpty)
    }

    /// A media id becomes a filename downstream (`Thumbnails.gridFile`, the received-media
    /// directory) and `appendingPathComponent` does not escape a separator. An id from a
    /// peer is whatever they chose to send, so it never reaches a path at all.
    func testAnIdThatWouldEscapeItsDirectoryIsIgnoredEntirely() {
        let offer = manifest(media: [
            offered("../../../../Library/Preferences/stolen", hash: "h1"),
            offered("m1/../m2", hash: "h1"),
            offered("", hash: "h1"),
            offered(String(repeating: "x", count: 65), hash: "h1"),
            offered("A-perfectly-ordinary_UUID-0001", hash: "h1"),
        ])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "h1")]

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer,
                                        verified: true, gallery: gallery)

        XCTAssertEqual(["A-perfectly-ordinary_UUID-0001": "asset/gallery/x"], plan.fromGallery)
        XCTAssertTrue(plan.request.isEmpty)
        XCTAssertTrue(plan.held.isEmpty)
    }

    /// Checked at the landing too, and not only in the plan: `offer.media` and
    /// `offer.timeline.gigMedia` are different parts of a peer's message and nothing makes
    /// them agree.
    func testAnUnsafeIdIsRejectedAtTheLandingAsWell() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("../escape")]]),
            media: []
        )

        let landing = contactLanding(mine: mine, offer: offer,
                                     resolved: ["../escape": "asset/gallery/x"])

        XCTAssertTrue(landing.isEmpty)
    }

    /// What lets an Exchange visit re-diff on every discovery instead of tracking any
    /// session state of its own: walking in and out of range twice is the same plan
    /// twice, not two half-plans.
    func testRunningThePlanTwiceAgainstTheSameManifestsIsIdempotent() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])
        let offer = manifest(media: [offered("m1", hash: "h1"),
                                     offered("m2", hash: "h2"),
                                     offered("m3", hash: "h3")])
        let gallery = [GalleryItem(ref: "asset/gallery/x", hash: "h2")]

        let first = contactReconcilePlan(mine: mine, offer: offer, verified: true, gallery: gallery)
        let second = contactReconcilePlan(mine: mine, offer: offer, verified: true, gallery: gallery)

        XCTAssertEqual(first, second)
        XCTAssertEqual(["m1"], first.held)
        XCTAssertEqual(["m2": "asset/gallery/x"], first.fromGallery)
        XCTAssertEqual(["m3"], first.request)
    }

    // MARK: - The landing

    func testAResolvedItemLandsOnTheGigSharingItsSetlistIdNotItsSenderSideGigId() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: mine, offer: offer,
                                     resolved: ["m1": "asset/gallery/x"])

        XCTAssertEqual(1, landing.count)
        guard let item = landing["mine-gig"]?.first else { return XCTFail("nothing landed") }
        XCTAssertEqual("m1", item.id)
        XCTAssertEqual("asset/gallery/x", item.ref)
        // Attribution survives the transfer, or which photographs were whose is
        // unrecoverable the moment they are mingled into someone's nights.
        XCTAssertEqual("their-key", item.from)
    }

    /// This never mints a night. A Contact's offer is a shared band, not a device's own
    /// history, so a night I have no record of attending is not one for their photographs
    /// to create.
    func testANightIHaveNoRecordOfAttendingLandsNothing() {
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: TimelineCache(), offer: offer,
                                     resolved: ["m1": "asset/gallery/x"])

        XCTAssertTrue(landing.isEmpty)
    }

    /// A record whose reference points at nothing is the dead reference #97 exists to
    /// prevent, so an item joins only once its bytes have actually arrived.
    func testAnItemWithNoResolvedRefYetDoesNotLand() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(gigs: ["their-gig": StoredGig(id: "their-gig", setlistId: "sl-1")],
                            gigMedia: ["their-gig": [photo("m1")]]),
            media: [offered("m1", hash: "h1")]
        )

        let landing = contactLanding(mine: mine, offer: offer, resolved: [:])

        XCTAssertTrue(landing.isEmpty)
    }

    // MARK: - What a Contact is offered in the first place

    /// The whole privacy boundary of this feature, asserted where it is decided: an item
    /// in the vault is absent from the manifest, not filtered out of it downstream.
    func testAPersonalItemNeverEntersAContactManifest() {
        var vault = photo("m2")
        vault.personal = true
        let mine = cache(gigMedia: ["a": [photo("m1"), vault]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["m1"], offered.media.map(\.id))
        XCTAssertEqual(["m1"], offered.timeline.gigMedia["a"]?.map(\.id))
    }

    /// Passing a Contact's photograph on to my other Contacts would be publishing on
    /// their behalf — a second path for their picture that they never agreed to.
    func testReceivedMediaIsNeverOfferedOnward() {
        var theirs = photo("m2")
        theirs.from = "someone-elses-key"
        let mine = cache(gigMedia: ["a": [photo("m1"), theirs]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["m1"], offered.media.map(\.id))
    }

    func testEveryOfferedItemIsAttributedToMeAndMarkedShared() {
        let mine = cache(gigMedia: ["a": [photo("m1")]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["my-key"], offered.media.map { $0.from ?? "" })
        XCTAssertEqual([false], offered.media.map(\.personal))
        // In the source's own gig ids: translating them is the receiver's job.
        XCTAssertEqual(["a"], offered.media.map(\.gigId))
    }

    /// #265's ninth story, asserted: *media from the shared band, not my whole timeline*.
    /// A `TimelineCache` carries the **Log**, attendance and how it was decided, tickets
    /// held, playlists made and every band's shows — and it is one struct, so the leak is
    /// a field nobody removed rather than a decision anybody made.
    func testAManifestCarriesTheSharedNightsAndNothingElseFromMyTimeline() {
        var mine = cache(gigs: ["shared": StoredGig(id: "shared", setlistId: "sl-1"),
                                "private": StoredGig(id: "private", setlistId: "sl-2")],
                         gigMedia: ["shared": [photo("m1")]])
        mine.shows = ["me": [FmSetlist(id: "sl-9")]]
        mine.attendedTotals = ["me": 412]
        mine.gigPlanned = ["planned": FmSetlist(id: "sl-3")]

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertTrue(offered.timeline.shows.isEmpty)
        XCTAssertTrue(offered.timeline.attendedTotals.isEmpty)
        XCTAssertTrue(offered.timeline.gigPlanned.isEmpty)
        // The two fields `contactLanding` actually reads, and a night with nothing to
        // offer is not one whose existence travels either.
        XCTAssertEqual(["shared"], Array(offered.timeline.gigs.keys))
        XCTAssertEqual(["shared"], Array(offered.timeline.gigMedia.keys))
    }

    /// A night that shares nothing drops out of the manifest rather than travelling as an
    /// empty entry — the far end has no use for the fact that it exists.
    func testANightSharingNothingIsNotOffered() {
        var vault = photo("m1")
        vault.personal = true
        let mine = cache(gigMedia: ["a": [vault], "b": [photo("m2")]])

        let offered = contactManifest(mine, me: "my-key")

        XCTAssertEqual(["b"], Array(offered.timeline.gigMedia.keys))
        XCTAssertEqual(["m2"], offered.media.map(\.id))
    }
}
