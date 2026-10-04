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

    /// A video hashes to nothing on this platform (see `PhotoLibrary.mediaFingerprint`), so an
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

    // MARK: - Nights ride the Reconcile offer (#405)
    //
    // A Contact with no setlist.fm account has no address to fetch a Lane from, so the
    // Lane is what they hand over. Hand-logged and imported Nights go on the same terms.
    // Android's `ContactReconcileTest`, case for case.

    private let imported = FmSetlist(id: "sl-imported", eventDate: "13-08-2026",
                                     artist: FmArtist(name: "Wilco"),
                                     url: "https://www.setlist.fm/setlist/wilco/2026/x.html")
    private let handLogged = localGigSetlist(gigId: "local-1", artist: "Nick Cave",
                                             date: "14-08-2026", venue: "Tøyenparken", city: "Oslo")

    private func theirCache() -> TimelineCache {
        var c = TimelineCache()
        c.shows = ["theirs": [imported]]
        c.gigPlanned = ["local-1": handLogged]
        c.gigAttendance = ["local-1": StoredAttendance(provenance: "attended")]
        return c
    }

    func testAnUnverifiedPeersNightsAreNotTaken() {
        let offer = HandoverManifest(nights: [imported, handLogged])

        XCTAssertTrue(contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: false).nights.isEmpty)
    }

    func testHandLoggedNightsAreOfferedOnTheSameTermsAsImportedOnes() {
        let offer = contactManifest(theirCache(), me: "their-key", setlistfm: "theirs")

        XCTAssertEqual(["sl-imported", "local-1"], Set(offer.nights.map(\.id)))
        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true)
        XCTAssertEqual(["sl-imported", "local-1"], Set(plan.nights.map(\.id)))
    }

    /// No account is no reason to offer less: what I logged by hand is my whole Line.
    func testAContactWithNoAccountStillOffersEveryNightTheyLogged() {
        let offer = contactManifest(theirCache(), me: "their-key", setlistfm: "")

        XCTAssertEqual(["local-1"], offer.nights.map(\.id))
    }

    func testNightsTheFarEndAlreadyHoldsAreNotTakenAgain() {
        let offer = HandoverManifest(nights: [imported, handLogged])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true,
                                        heldLane: [imported])

        XCTAssertEqual(["local-1"], plan.nights.map(\.id))
    }

    func testALaneAlreadyWholeTakesNothingAndRunningItTwiceIsRunningItOnce() {
        let offer = HandoverManifest(nights: [imported, handLogged, handLogged])

        let first = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true)
        let lane = landNights(nil, first.nights)
        let second = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true, heldLane: lane)

        XCTAssertEqual(["local-1", "sl-imported"], lane.map(\.id))
        XCTAssertTrue(second.nights.isEmpty)
    }

    func testANightWithNoIdIsNoNight() {
        let offer = HandoverManifest(nights: [FmSetlist(id: "", eventDate: "13-08-2026")])

        XCTAssertTrue(contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true).nights.isEmpty)
    }

    func testTheOfferCarriesNoSongs() {
        var withSongs = imported
        withSongs.sets = FmSets(set: [FmSet(song: [FmSong(name: "Jesus, Etc.")])])
        var c = theirCache()
        c.shows = ["theirs": [withSongs]]

        XCTAssertTrue(contactManifest(c, me: "their-key", setlistfm: "theirs").nights.allSatisfy { $0.sets == nil })
    }

    /// A received Lane is held, so an account-less Contact is never sent to setlist.fm.
    func testAnAccountlessContactsReceivedLaneDrawsWithoutAFetch() {
        let dio = Friend(setlistfm: "", name: "Dio", publicKey: "k-dio")
        let offer = contactManifest(theirCache(), me: "k-dio", setlistfm: "")
        let lane = landNights(nil, contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true).nights)

        XCTAssertEqual(["local-1"], lane.map(\.id))
        XCTAssertFalse(laneNeedsFetch(dio, held: lane))
    }

    /// A peer on a build before #405 sends no `nights` key at all.
    func testAManifestFromAnOlderBuildDecodesWithNoNights() throws {
        let data = Data(#"{"media":[],"counts":{}}"#.utf8)

        XCTAssertTrue(try JSONDecoder().decode(HandoverManifest.self, from: data).nights.isEmpty)
    }

    // MARK: - "I was there", taken back
    //
    // Their manifest carries their whole Spine, so a hand-logged Night it leaves out is one
    // they no longer claim. It leaves their Lane here, and what they offered for it goes too.

    func testAHandLoggedNightTheyNoLongerOfferIsWithdrawn() {
        let offer = HandoverManifest(nights: [imported])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true, heldLane: [imported, handLogged])

        XCTAssertEqual(["local-1"], plan.withdrawn)
        XCTAssertEqual(["sl-imported"], withdrawNights([imported, handLogged], plan.withdrawn).map(\.id))
    }

    /// setlist.fm's Nights are setlist.fm's to take back, whatever a phone without the account sends.
    func testASetlistFmNightTheyDoNotOfferStays() {
        let offer = HandoverManifest(nights: [handLogged])

        let plan = contactReconcilePlan(mine: TimelineCache(), offer: offer, verified: true, heldLane: [imported, handLogged])

        XCTAssertTrue(plan.withdrawn.isEmpty)
        XCTAssertEqual(["sl-imported", "local-1"], withdrawNights([imported, handLogged], ["sl-imported"]).map(\.id))
    }

    /// What a manifest from before Nights travelled decodes to.
    func testAnOfferWithNoNightsWithdrawsNothing() {
        let plan = contactReconcilePlan(mine: TimelineCache(), offer: HandoverManifest(), verified: true, heldLane: [handLogged])

        XCTAssertTrue(plan.withdrawn.isEmpty)
    }

    func testAWithdrawnNightTakesItsOfferWithItAndLeavesWhatIAccepted() {
        let mine = cache(gigs: ["my-local": myGig])
        let resolved = ["m1": "file:///received/m1"]
        let held = mine.holdingOffers(contactOffers(mine: mine, offer: theirOffer([photo("m1")]), resolved: resolved, myNights: [myNight]))
        let accepted = held.acceptingOffer("their-local", gigId: "my-local")

        XCTAssertTrue(held.withdrawingOffers(["their-local"]).mediaOffers.isEmpty)
        let after = accepted.withdrawingOffers(["their-local"])
        XCTAssertEqual(["m1"], after.gigMedia["my-local"]?.map(\.id))
        XCTAssertEqual(accepted.nightJoins, after.nightJoins)
    }

    // MARK: - Media a Contact sends is offered, never filed (#405)
    //
    // Another person's belief that we shared a Night must never write onto my record. What
    // they send for a Night I hold under the same catalogue id merges as it always did;
    // what they send for a Night I have not joined waits as an offer until I answer it.
    // Android's `ContactReconcileTest`, case for case.

    /// My own hand-logged Night on 14-08-2026, and the gig record it lives under.
    private let myNight = localGigSetlist(gigId: "my-local", artist: "Nick Cave",
                                          date: "14-08-2026", venue: "Tøyenparken", city: "Oslo")
    private let myGig = StoredGig(id: "my-local", date: "14-08-2026", artist: "Nick Cave", venue: "Tøyenparken")

    /// Their hand-logged record of the same evening: another id, so nothing links the two.
    private func theirOffer(_ items: [StoredMedia]) -> HandoverManifest {
        manifest(
            timeline: cache(
                gigs: ["their-local": StoredGig(id: "their-local", date: "14-08-2026",
                                                artist: "Nick Cave", venue: "Tøyenparken")],
                gigMedia: ["their-local": items]
            ),
            media: items.map { offered($0.id, hash: "h-\($0.id)") }
        )
    }

    func testMediaForANightIHoldUnderTheSameCatalogueIdMergesAndIsNotOffered() {
        let mine = cache(gigs: ["mine-gig": StoredGig(id: "mine-gig", date: "13-08-2026", setlistId: "sl-1")])
        let offer = manifest(
            timeline: cache(
                gigs: ["their-gig": StoredGig(id: "their-gig", date: "13-08-2026", setlistId: "sl-1")],
                gigMedia: ["their-gig": [photo("m1")]]
            ),
            media: [offered("m1", hash: "h1")]
        )
        let resolved = ["m1": "file:///received/m1"]
        let spine = [FmSetlist(id: "sl-1", eventDate: "13-08-2026")]

        XCTAssertEqual(["m1"], contactLanding(mine: mine, offer: offer, resolved: resolved)["mine-gig"]?.map(\.id))
        XCTAssertTrue(contactOffers(mine: mine, offer: offer, resolved: resolved, myNights: spine).isEmpty)
    }

    func testMediaForANightIHaveNotJoinedIsOfferedAndMyTimelineIsUntouched() {
        let mine = cache(gigs: ["my-local": myGig])
        let offer = theirOffer([photo("m1"), photo("m2")])
        let resolved = ["m1": "file:///received/m1", "m2": "file:///received/m2"]

        let landing = contactLanding(mine: mine, offer: offer, resolved: resolved)
        let offers = contactOffers(mine: mine, offer: offer, resolved: resolved, myNights: [myNight])
        let held = mine.holdingOffers(offers)

        XCTAssertTrue(landing.isEmpty)
        let waiting = offers["their-local"]
        XCTAssertEqual("14-08-2026", waiting?.date)
        XCTAssertEqual(["m1", "m2"], waiting?.media.map(\.id).sorted())
        XCTAssertEqual("file:///received/m1", waiting?.media.first { $0.id == "m1" }?.ref)
        XCTAssertEqual("their-key", waiting?.media.first?.from)
        // Held apart: the Night's media and the joins are exactly what they were.
        XCTAssertEqual(mine.gigMedia, held.gigMedia)
        XCTAssertEqual(mine.gigs, held.gigs)
        XCTAssertTrue(joinedNights(held).isEmpty)
        XCTAssertEqual(["their-local"], offersWaiting(held.mediaOffers, on: "14-08-2026").map(\.night))
    }

    func testMediaForANightOnADateIWasNotOutIsNeitherFiledNorOffered() {
        let offer = theirOffer([photo("m1")])
        let resolved = ["m1": "file:///received/m1"]
        var elsewhere = myNight
        elsewhere.eventDate = "01-01-2026"

        XCTAssertTrue(contactLanding(mine: TimelineCache(), offer: offer, resolved: resolved).isEmpty)
        XCTAssertTrue(contactOffers(mine: TimelineCache(), offer: offer, resolved: resolved,
                                    myNights: [elsewhere]).isEmpty)
    }

    func testDecliningChangesNothingOnMyTimelineAndIsNotAskedAgain() {
        let mine = cache(gigs: ["my-local": myGig], gigMedia: ["my-local": [photo("mine")]])
        let offer = theirOffer([photo("m1")])
        let resolved = ["m1": "file:///received/m1"]
        let held = mine.holdingOffers(contactOffers(mine: mine, offer: offer, resolved: resolved, myNights: [myNight]))

        let declined = held.decliningOffer("their-local")

        XCTAssertEqual(mine.gigMedia, declined.gigMedia)
        XCTAssertEqual(mine.gigs, declined.gigs)
        XCTAssertTrue(joinedNights(declined).isEmpty)
        XCTAssertTrue(offersWaiting(declined.mediaOffers, on: "14-08-2026").isEmpty)
        // The next Reconcile brings the same photo again: it is not offered a second time.
        let again = declined.holdingOffers(
            contactOffers(mine: declined, offer: offer, resolved: resolved, myNights: [myNight]))
        XCTAssertTrue(offersWaiting(again.mediaOffers, on: "14-08-2026").isEmpty)
    }

    func testAcceptingFilesTheMediaOnMyNightAndJoinsIt() {
        let mine = cache(gigs: ["my-local": myGig], gigMedia: ["my-local": [photo("mine")]])
        let resolved = ["m1": "file:///received/m1", "m2": "file:///received/m2"]
        let held = mine.holdingOffers(
            contactOffers(mine: mine, offer: theirOffer([photo("m1")]), resolved: resolved, myNights: [myNight]))

        let accepted = held.acceptingOffer("their-local", gigId: "my-local")

        XCTAssertEqual(["mine", "m1"], accepted.gigMedia["my-local"]?.map(\.id))
        XCTAssertEqual("their-key", accepted.gigMedia["my-local"]?.last?.from)
        XCTAssertEqual("my-local", joinedNights(accepted)["their-local"])
        XCTAssertTrue(accepted.mediaOffers.isEmpty)
        let later = theirOffer([photo("m1"), photo("m2")])
        XCTAssertEqual(["m1", "m2"],
                       contactLanding(mine: accepted, offer: later, resolved: resolved)["my-local"]?.map(\.id))
        XCTAssertTrue(contactOffers(mine: accepted, offer: later, resolved: resolved, myNights: [myNight]).isEmpty)
    }

    func testAnUnsafeIdIsNeverOffered() {
        let mine = cache(gigs: ["my-local": myGig])
        let offer = theirOffer([photo("../evil"), photo("m1")])
        let resolved = ["../evil": "file:///received/evil", "m1": "file:///received/m1"]

        let offers = contactOffers(mine: mine, offer: offer, resolved: resolved, myNights: [myNight])

        XCTAssertEqual(["m1"], offers["their-local"]?.media.map(\.id))
    }

    // MARK: The maybe, answered (#405): mine alone, and the seam #578 left for it.

    func testSameNightJoinsItSoWhatTheySendLandsDirectlyAndNothingIsOffered() {
        let mine = cache(gigs: ["my-local": myGig])
        let resolved = ["m1": "file:///received/m1"]

        let joined = mine.joiningNight("their-local", gigId: "my-local")

        XCTAssertEqual("my-local", joinedNights(joined)["their-local"])
        XCTAssertEqual(["their-local": "my-local"], joined.spineJoins())
        XCTAssertEqual(["m1"], contactLanding(mine: joined, offer: theirOffer([photo("m1")]),
                                              resolved: resolved)["my-local"]?.map(\.id))
        XCTAssertTrue(contactOffers(mine: joined, offer: theirOffer([photo("m1")]),
                                    resolved: resolved, myNights: [myNight]).isEmpty)
        XCTAssertEqual(mine.gigMedia.keys.sorted(), joined.gigMedia.keys.sorted())
        XCTAssertEqual(mine.gigs, joined.gigs)
    }

    func testNotTheSameIsRememberedPerPairOnceAndJoinsNothing() {
        let mine = cache(gigs: ["my-local": myGig])

        let apart = mine.dismissingMaybe("their-local", gigId: "my-local")
            .dismissingMaybe("their-local", gigId: "my-local")

        XCTAssertEqual(["their-local": ["my-local"]], apart.nightDismissals)
        XCTAssertEqual(["their-local": Set(["my-local"])], apart.spineDismissals())
        XCTAssertTrue(joinedNights(apart).isEmpty)
        XCTAssertTrue(apart.gigMedia.isEmpty)
    }

    /// The Spine knows a Night by its setlist.fm id once it has one; the answer follows it.
    func testAnAnswerIsReadBackUnderTheIdTheSpineUses() {
        let adopted = StoredGig(id: "my-local", date: "14-08-2026", setlistId: "sfm-1")
        let mine = cache(gigs: ["my-local": adopted])
            .joiningNight("their-a", gigId: "my-local")
            .dismissingMaybe("their-b", gigId: "my-local")

        XCTAssertEqual(["their-a": "sfm-1"], mine.spineJoins())
        XCTAssertEqual(["their-b": Set(["sfm-1"])], mine.spineDismissals())
    }

    // MARK: - Undo (#580)

    func testUndoSameNightRemovesOnlyThatJoin() {
        let mine = TimelineCache().joiningNight("their-b", gigId: "my-local")
        let undone = mine.joiningNight("their-local", gigId: "my-local")
            .unjoiningNight("their-local", gigId: "my-local")
        XCTAssertEqual(mine.nightJoins, undone.nightJoins)
        XCTAssertEqual(["their-b": "my-local"], undone.nightJoins)
    }

    func testUndoLeavesANewerJoinToAnotherNightAlone() {
        let mine = TimelineCache().joiningNight("their-local", gigId: "my-other")
        XCTAssertEqual(mine.nightJoins, mine.unjoiningNight("their-local", gigId: "my-local").nightJoins)
    }

    func testUndoNotTheSameRestoresThatPairAndKeepsOthersApart() {
        let once = TimelineCache().dismissingMaybe("their-local", gigId: "my-local")
        XCTAssertTrue(once.undismissingMaybe("their-local", gigId: "my-local").nightDismissals.isEmpty)
        let two = once.dismissingMaybe("their-local", gigId: "my-other")
        XCTAssertEqual(["their-local": ["my-other"]],
                       two.undismissingMaybe("their-local", gigId: "my-local").nightDismissals)
        XCTAssertEqual(two.nightDismissals, two.undismissingMaybe("their-local", gigId: "nobody").nightDismissals)
    }

}
