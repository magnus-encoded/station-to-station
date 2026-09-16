package io.github.magnusencoded.stationtostation.data.gossip

import java.util.concurrent.atomic.AtomicLong

/**
 * Six numbers that say whether routing receipts did anything tonight (#444).
 *
 * ADR-0022 ships receipts and the ranking that reads them knowing that several independent
 * things could make the whole feature a no-op in the field, and that none of them is visible
 * from the outside: a receipt that is never authored, one that is never owed because the
 * **Pass** could not be addressed, one that is authored but never offered,
 * one that is offered but never delivered, and a ranking that never finds live credit for a
 * peer it can see all present identically — as "no preference", which is also what correct
 * looks like when nobody is around. The point of these counters is to tell those apart at the
 * end of a night with one line instead of a night of logcat.
 *
 * **Cheap and off the hot path by construction.** Six `AtomicLong`s incremented at a handful of places,
 * no allocation, no flow, no persistence. It follows [GossipRadioStatus]'s rule — diagnostic,
 * process-wide, losing everything to a process death is correct rather than a gap — with the
 * one difference that [GossipRadioStatus.radioStopped] wipes its state and this deliberately
 * does not: a tally that resets when the relay stops is a tally you can never read, because
 * stopping is when you go looking.
 *
 * These are counts of events, never of peers, and nothing here names anybody. A number is not
 * a routing fact about a person, which is the whole reason it is safe to log.
 */
object GossipTally {

    private val authored = AtomicLong()
    private val declined = AtomicLong()
    private val offered = AtomicLong()
    private val delivered = AtomicLong()
    private val windows = AtomicLong()
    private val creditHits = AtomicLong()

    /** A receipt this device wrote, on admitting a **Fact** it recognised as a **Contact**'s. */
    fun authored(count: Int = 1) { if (count > 0) authored.addAndGet(count.toLong()) }

    /**
     * A **Pass** this device owed nothing on because it could not address its sender
     * (ADR-0022 §2a: the **Pass** was signed as a **Gig**, carrying its signer's own request).
     *
     * One per **Pass**, not per **Fact**, because the decision is about the **Pass**. This is
     * the price of the addressing fix made visible: §2a argues the credit is deferred to that
     * neighbour's next **Pass** rather than lost, and the ratio of this to [authored] is what
     * decides whether that argument survives contact with a real room.
     */
    fun declined() { declined.incrementAndGet() }

    /** Receipts put into a **Pass** — offered is not delivered, and the gap is the interesting part. */
    fun offered(count: Int) { if (count > 0) offered.addAndGet(count.toLong()) }

    /** Receipts in a **Pass** the peer acknowledged to the last chunk. */
    fun delivered(count: Int) { if (count > 0) delivered.addAndGet(count.toLong()) }

    /**
     * One closed pick window: how many candidates it ranked, and how many of those the
     * ranker could find live credit for.
     *
     * [hits] is the number ADR-0022 calls the cheapest falsifier. If it stays at zero across a
     * whole night with a non-zero [windows], `gossipPreferredPeers` is a shuffle and nothing
     * more, and no amount of tuning the decay changes that.
     */
    fun ranked(candidates: Int, hits: Int) {
        if (candidates <= 0) return
        windows.incrementAndGet()
        if (hits > 0) creditHits.addAndGet(hits.toLong())
    }

    /** The whole night in one line, for a log at shutdown or a panel someone is staring at. */
    fun summary(): String = "receipts authored=${authored.get()} declined=${declined.get()} " +
        "offered=${offered.get()} delivered=${delivered.get()} · " +
        "pick windows=${windows.get()} credit hits=${creditHits.get()}"

    /** Only for tests; a running relay never resets its own tally. */
    internal fun reset() {
        listOf(authored, declined, offered, delivered, windows, creditHits).forEach { it.set(0) }
    }
}
