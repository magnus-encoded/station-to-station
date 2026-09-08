import Foundation
import XCTest
@testable import StationToStation

/// The per-peer rate bound (#417). `gossipStormGate` says in its own doc comment that it
/// cannot apply this one — nothing in a pure function of a single batch knows the time
/// between calls — and names #416/#417 as the owners. This is that, and it is a pure function
/// too, so the bound is assertable without a radio on either platform.
final class GossipBudgetTests: XCTestCase {

    private let now = Date(timeIntervalSince1970: 1_788_555_600)

    private func admitted(_ admission: GossipAdmission) -> GossipPeerBudget? {
        guard case .admit(let budget) = admission else { return nil }
        return budget
    }

    func testAFirstHandoverFromAPeerIsAdmittedAndCharged() {
        let admission = gossipAdmit(GossipPeerBudget(), offered: 5, now: now)

        XCTAssertEqual(admitted(admission)?.messagesInWindow, 5)
        XCTAssertEqual(admitted(admission)?.lastHandoverAt, now)
        XCTAssertEqual(admitted(admission)?.windowStart, now)
    }

    /// Two phones in the same room meet again and again as iOS wakes the scan. The second
    /// meeting inside the cooldown is not an accusation — it is the ordinary case, and the
    /// right answer is to hang up quietly.
    func testTheSamePeerComingBackImmediatelyIsCooling() {
        let first = admitted(gossipAdmit(GossipPeerBudget(), offered: 5, now: now))!

        XCTAssertEqual(gossipAdmit(first, offered: 5, now: now.addingTimeInterval(1)), .cooling)
        XCTAssertEqual(gossipAdmit(first, offered: 5, now: now.addingTimeInterval(29)), .cooling)
    }

    func testAPeerIsHeardAgainOnceTheCooldownHasPassed() {
        let first = admitted(gossipAdmit(GossipPeerBudget(), offered: 5, now: now))!
        let later = now.addingTimeInterval(gossipPeerCooldown)

        XCTAssertEqual(admitted(gossipAdmit(first, offered: 3, now: later))?.messagesInWindow, 8)
    }

    /// The real bound on the attack the gate names: a **Contact** whose phone has been taken
    /// over can sign as many honest-looking check-ins as it likes, and still gets 240
    /// signature verifications an hour out of this device and no more.
    func testAPeerPastTheHourlyCeilingIsNotReadAtAll() {
        var budget = GossipPeerBudget()
        budget.windowStart = now
        budget.messagesInWindow = gossipPeerHourlyMessages - 1
        let later = now.addingTimeInterval(gossipPeerCooldown)

        XCTAssertEqual(admitted(gossipAdmit(budget, offered: 1, now: later))?.messagesInWindow,
                       gossipPeerHourlyMessages)
        XCTAssertEqual(gossipAdmit(budget, offered: 2, now: later), .flooding)
    }

    /// A peer handing over full batches as fast as the cooldown allows runs out inside the
    /// hour rather than costing an unbounded number of signature verifications.
    func testFullBatchesAsFastAsTheCooldownAllowsStillRunOutInsideTheHour() {
        var budget = GossipPeerBudget()
        var clock = now
        var admittedBatches = 0
        while clock.timeIntervalSince(now) < gossipPeerWindow {
            if let next = admitted(gossipAdmit(budget, offered: gossipMaxBatch, now: clock)) {
                budget = next
                admittedBatches += 1
            }
            clock = clock.addingTimeInterval(gossipPeerCooldown)
        }

        XCTAssertEqual(admittedBatches, gossipPeerHourlyMessages / gossipMaxBatch)
        XCTAssertLessThanOrEqual(budget.messagesInWindow, gossipPeerHourlyMessages)
    }

    func testAFloodingPeerIsJudgedNormallyOnceTheWindowRolls() {
        var budget = GossipPeerBudget()
        budget.windowStart = now
        budget.messagesInWindow = gossipPeerHourlyMessages
        let rolled = now.addingTimeInterval(gossipPeerWindow)

        XCTAssertEqual(admitted(gossipAdmit(budget, offered: 10, now: rolled))?.messagesInWindow, 10)
        XCTAssertEqual(admitted(gossipAdmit(budget, offered: 10, now: rolled))?.windowStart, rolled)
    }

    /// Charged on what was offered, not on what survived. Charging acceptance would make the
    /// budget free to exhaust with rubbish, which is the traffic it exists to stop.
    func testTheChargeIsWhatWasOfferedNotWhatWouldBeAccepted() {
        let admission = gossipAdmit(GossipPeerBudget(), offered: gossipMaxBatch, now: now)

        XCTAssertEqual(admitted(admission)?.messagesInWindow, gossipMaxBatch)
    }

    func testAnEmptyHandoverCostsNothingButStillStartsTheCooldown() {
        let admission = gossipAdmit(GossipPeerBudget(), offered: 0, now: now)

        XCTAssertEqual(admitted(admission)?.messagesInWindow, 0)
        XCTAssertEqual(admitted(admission)?.lastHandoverAt, now)
    }

    /// A phone whose clock jumped backwards must not be able to argue itself out of a
    /// cooldown, and must not be charged a negative amount.
    func testAClockThatWentBackwardsNeitherSkipsTheCooldownNorCreditsThePeer() {
        var budget = GossipPeerBudget()
        budget.lastHandoverAt = now
        budget.windowStart = now
        budget.messagesInWindow = 10
        let earlier = now.addingTimeInterval(-600)

        let admission = gossipAdmit(budget, offered: -5, now: earlier)

        XCTAssertEqual(admitted(admission)?.messagesInWindow, 0)
        XCTAssertEqual(admitted(admission)?.windowStart, earlier)
    }

    // --- Forgetting ---

    /// A device that kept one entry per **Contact** it had ever stood near would keep a
    /// slowly growing record of who it has been in a room with. That is the kind of history
    /// this app does not keep anywhere else.
    func testABudgetIsForgottenOnceItsWindowHasPassed() {
        let live = admitted(gossipAdmit(GossipPeerBudget(), offered: 1, now: now))!
        var stale = live
        stale.windowStart = now.addingTimeInterval(-gossipPeerWindow - 1)

        let kept = gossipPruneBudgets(["here": live, "gone": stale, "never": GossipPeerBudget()],
                                      now: now)

        XCTAssertEqual(Set(kept.keys), ["here"])
    }
}
