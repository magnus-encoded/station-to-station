package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/**
 * How many bytes of nonce a listener demands back.
 *
 * Thirty-two, matching the digest the signature is taken over anyway. The nonce exists so
 * that a recording of yesterday's **Pass** cannot be replayed as today's, and so that the
 * listener's own challenge proof is fresh; the only property that has to hold is that a
 * listener never issues the same one twice, which at this width it will not.
 */
const val GOSSIP_NONCE_BYTES = 32

/**
 * The hard ceiling on one **Pass**, in bytes.
 *
 * The transport's rule about how much memory a peer's write is allowed to cost before
 * anything has been decided at all — distinct from, and not a substitute for, the batch and
 * per-record bounds [encodePublicGossipPass] applies once the bytes are in hand. A hostile
 * relay must not be able to make this device hold a megabyte because it opened a GATT
 * connection.
 *
 * Sixty-four envelopes at the 8 KB per-record ceiling would be far more than this, so it is
 * this bound that ends a **Pass** in practice: the encoder stops appending records when the
 * next one would cross it. Anything larger arriving is refused whole rather than truncated —
 * half a **Pass** is not a **Pass**.
 */
const val GOSSIP_MAX_WIRE_BYTES = 40_000

/**
 * The most facts one **Pass** carries — the encoder's cap, the decoder's truncation, and the
 * most [PublicGossipState.offer] will hand over at once.
 *
 * A bound on *judgement* rather than on memory, which is what separates it from
 * [GOSSIP_MAX_WIRE_BYTES]: it is what stops one meeting costing this device an unbounded
 * number of signature verifications. The byte budget usually bites first; this is the backstop
 * for a **Pass** of very small records.
 *
 * **iOS holds the same sixty-four** (`gossipMaxBatch` in `Data/Gossip/GossipGatt.swift`). It is
 * a wire term — a peer that truncates at a different number hands over a **Pass** the other
 * side reads differently — so unlike the peer cooldown, the two cannot legitimately differ.
 */
const val GOSSIP_MAX_BATCH = 64

const val PUBLIC_GOSSIP_HEADER = "station-to-station/gossip-fact/2"
const val PUBLIC_GOSSIP_PASS = "station-to-station/gossip-pass/2"
const val PUBLIC_CARRY_MS = 15 * 60 * 1000L

/**
 * How long one receipt's credit for a neighbour survives (#444, story 41).
 *
 * **Provisional.** Nothing has measured it. It is two minutes because a neighbour that was
 * useful two minutes ago is probably still standing in the same part of the room, and one
 * that was useful an hour ago is probably not — a guess about how long a crowd holds still,
 * to be replaced by a figure from a real night. `sim/SWEEPS.md`, on the `gossip-sim` branch,
 * compares policies and is not evidence for this number; see `docs/adr/0022-gossip-receipts.md`.
 *
 * Deliberately its own constant rather than a fraction of [PUBLIC_CARRY_MS] or of the grace
 * period in [GossipPolicy][io.github.magnusencoded.stationtostation.data.gossip.gossipParticipationUntil].
 * The three answer different questions — how long a **Fact** is worth relaying, how long a
 * routing hint is worth trusting, how long the user stays in the night — and tying any two
 * of them together means tuning one silently retunes another.
 *
 * **iOS holds the same two minutes** (`publicReceiptMs` in `Data/Gossip/PublicGossip.swift`).
 * Local routing policy rather than a wire term, so the two may legitimately diverge once
 * either platform has a measurement.
 */
const val PUBLIC_RECEIPT_MS = 2 * 60 * 1000L
const val PUBLIC_MAX_HELD = 128
const val PUBLIC_MAX_SEEN = 8192

fun publicGossipAuthPayload(nonce: ByteArray): ByteArray =
    "station-to-station/gossip-auth/2\n${gossipBase64(nonce)}".toByteArray(Charsets.UTF_8)

data class PublicGossipChallenge(val nonce: ByteArray, val from: String)
private fun publicChallengeProof(nonce: ByteArray) =
    "station-to-station/gossip-challenge-proof/2\n${gossipBase64(nonce)}".toByteArray(Charsets.UTF_8)

fun encodePublicGossipChallenge(nonce: ByteArray, from: String, sign: (ByteArray) -> ByteArray?): ByteArray? {
    if (nonce.size != 32) return null
    val proof = sign(publicChallengeProof(nonce)) ?: return null
    return "station-to-station/gossip-challenge/2\n${gossipBase64(nonce)}\n$from\n${gossipBase64(proof)}"
        .toByteArray(Charsets.UTF_8).takeIf { it.size <= 512 }
}

fun decodePublicGossipChallenge(bytes: ByteArray?): PublicGossipChallenge? {
    if (bytes == null || bytes.size > 512) return null
    val fields = bytes.toString(Charsets.UTF_8).split('\n')
    if (fields.size != 4 || fields[0] != "station-to-station/gossip-challenge/2") return null
    val nonce = gossipUnbase64(fields[1])?.takeIf { it.size == 32 } ?: return null
    val proof = gossipUnbase64(fields[3]) ?: return null
    if (!verifyChallenge(publicChallengeProof(nonce), proof, fields[2])) return null
    return PublicGossipChallenge(nonce, fields[2])
}

/** One public assertion. The author is a Gig key; attribution contains no readable durable key. */
@Serializable
data class GossipEnvelope(
    val id: String = "",
    val gigId: String,
    val formerIds: List<String> = emptyList(),
    val scope: String,
    val author: String,
    val createdAt: Long,
    val expiresAt: Long,
    val kind: String,
    val line: Int = -1,
    val text: String = "",
    val attribution: String = "",
    val signature: String = "",
) {
    fun fields(): List<String> = listOf(gigId, formerIds.joinToString(","), scope, author,
        createdAt.toString(), expiresAt.toString(), kind, line.toString(), gossipBase64(text.toByteArray(Charsets.UTF_8)), attribution)
    fun payload(): ByteArray = (listOf(PUBLIC_GOSSIP_HEADER) + fields()).joinToString("\n").toByteArray(Charsets.UTF_8)
    fun record(): String = (listOf(id) + fields() + signature).joinToString("\t")
    fun signed(sign: (ByteArray) -> ByteArray?): GossipEnvelope? =
        sign(payload())?.let { copy(id = gossipHash(payload()), signature = gossipBase64(it)) }
    fun valid(): Boolean {
        if (!isSafeGossipId(gigId) || !isSafeGossipId(scope) || formerIds.size > 32 || formerIds.any { !isSafeGossipId(it) }) return false
        if (kind !in setOf("log", "request", "witness", "receipt") || line !in -1..4096) return false
        if (createdAt < 0 || expiresAt <= createdAt || expiresAt - createdAt > 108_000_000) return false
        if (author.length > 256 || attribution.length > 1024 || signature.length > 256 || record().toByteArray().size > 8192) return false
        if (kind == "log" && (line < 0 || text.toByteArray(Charsets.UTF_8).size > 512)) return false
        if (kind == "request" && line != -1) return false
        if (kind in setOf("witness", "receipt") && line != -1) return false
        if (fields().any { it.contains('\n') || it.contains('\t') }) return false
        if (id != gossipHash(payload())) return false
        val sig = gossipUnbase64(signature) ?: return false
        if (!verifyChallenge(payload(), sig, author)) return false
        if (kind == "witness") {
            val claim = decodePublicEnvelope(text) ?: return false
            if (claim.kind != "request" || claim.author == author || !claim.valid() || !sameGig(claim)) return false
            if (createdAt < claim.createdAt || createdAt >= claim.expiresAt || expiresAt > claim.expiresAt) return false
        }
        return true
    }
    fun sameGig(other: GossipEnvelope): Boolean = (formerIds + gigId).any { it in other.formerIds || it == other.gigId }
}

fun gossipBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
fun gossipUnbase64(value: String): ByteArray? = runCatching { Base64.getDecoder().decode(value) }.getOrNull()
fun gossipHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun decodePublicEnvelope(record: String): GossipEnvelope? {
    if (record.toByteArray().size > 8192) return null
    val f = record.split('\t')
    if (f.size != 12) return null
    return GossipEnvelope(f[0], f[1], f[2].split(',').filter { it.isNotEmpty() }, f[3], f[4],
        f[5].toLongOrNull() ?: return null, f[6].toLongOrNull() ?: return null, f[7],
        f[8].toIntOrNull() ?: return null, gossipUnbase64(f[9])?.toString(Charsets.UTF_8) ?: return null, f[10], f[11])
}

data class PublicGossipPass(val from: String, val proof: String, val batch: List<GossipEnvelope>)
fun encodePublicGossipPass(pass: PublicGossipPass): ByteArray? {
    if (listOf(pass.from, pass.proof).any { it.isBlank() || it.toByteArray(Charsets.UTF_8).size > 256 || it.contains('\n') || it.contains('\t') }) return null
    val encoded = StringBuilder("$PUBLIC_GOSSIP_PASS\n${pass.from}\t${pass.proof}")
    var size = encoded.toString().toByteArray(Charsets.UTF_8).size
    for (envelope in pass.batch.take(GOSSIP_MAX_BATCH)) {
        val record = envelope.record()
        val bytes = record.toByteArray(Charsets.UTF_8).size
        if (bytes > 8192 || size + bytes + 1 > GOSSIP_MAX_WIRE_BYTES) break
        encoded.append('\n').append(record)
        size += bytes + 1
    }
    return encoded.toString().toByteArray(Charsets.UTF_8)
}
fun decodePublicGossipPass(bytes: ByteArray?): PublicGossipPass? {
    if (bytes == null || bytes.size > GOSSIP_MAX_WIRE_BYTES) return null
    val lines = bytes.toString(Charsets.UTF_8).split('\n')
    if (lines.size < 2 || lines[0] != PUBLIC_GOSSIP_PASS) return null
    val claim = lines[1].split('\t')
    if (claim.size != 2 || claim.any { it.isBlank() || it.length > 256 }) return null
    return PublicGossipPass(claim[0], claim[1], lines.drop(2).take(GOSSIP_MAX_BATCH).mapNotNull(::decodePublicEnvelope))
}

@Serializable
data class PublicHeld(val envelope: GossipEnvelope, val until: Long, val delivered: MutableSet<String> = mutableSetOf())

/** Transport memory and durable application assertions have deliberately different lifetimes. */
@Serializable
data class PublicGossipState(
    val facts: MutableMap<String, GossipEnvelope> = linkedMapOf(),
    val seen: MutableMap<String, Long> = linkedMapOf(),
    val held: MutableMap<String, PublicHeld> = linkedMapOf(),
    val blocked: MutableSet<String> = mutableSetOf(),
    val recognition: MutableMap<String, String> = mutableMapOf(),
    /**
     * What a recognised **Contact** was called, held here rather than looked up.
     *
     * Recognition cannot be revoked (story 16), so the name it resolves to must not depend
     * on the **Contact** still being on this device. Removing someone deletes their
     * `Friend` record, and with it the only live source of their name; without this map
     * their already-attributed **Facts** would quietly become "Nearby listener" — the app
     * pretending not to know something it does know. Local only: this never goes on the
     * wire, which carries `GossipEnvelope` and nothing else.
     */
    val contactNames: MutableMap<String, String> = mutableMapOf(),
    val useful: MutableMap<String, Long> = mutableMapOf(),
    /** Gig authors whose private key is on this device. Persisted so a radio-restored
     * process can still distinguish my claim from a stranger's after restart. */
    val localAuthors: MutableSet<String> = mutableSetOf(),
) {
    fun isBlocked(author: String): Boolean = author in blocked || recognition[author] in blocked

    /** Recognition is durable and never changes a Block; later Exchange only adds attribution. */
    fun recognizeContacts(contacts: Set<String>, names: Map<String, String> = emptyMap()) {
        val envelopes = facts.values + held.values.map { it.envelope }
        val claims = envelopes.filter { it.kind == "witness" && it.valid() }
            .mapNotNull { decodePublicEnvelope(it.text) }
        (envelopes + claims).forEach { envelope ->
            if (envelope.author !in recognition && envelope.valid()) {
                recognizeGossip(envelope, contacts)?.let { recognition[envelope.author] = it }
            }
        }
        // Refreshed for everyone recognised, not only the authors matched just now, so a
        // **Contact** who renames themselves is followed while they are still here. Only
        // ever written, never removed: that is the half that has to outlive them.
        recognition.values.toSet().forEach { durable -> names[durable]?.let { contactNames[durable] = it } }
    }

    /**
     * Who this device says authored [author]'s **Facts**: the live **Contact** name where
     * there still is one, the name held from recognition otherwise, and `null` for a
     * stranger, whose **Facts** are shown but unattributed.
     */
    fun attribution(author: String, live: Map<String, String> = emptyMap()): String? =
        recognition[author]?.let { live[it] ?: contactNames[it] }

    fun prune(now: Long) {
        seen.entries.removeAll { it.value <= now }
        held.entries.removeAll { it.value.until <= now || it.value.envelope.expiresAt <= now }
        useful.entries.removeAll { it.value <= now }
    }
    fun receive(envelope: GossipEnvelope, from: String, now: Long, local: Boolean = false): Boolean {
        prune(now)
        if (!envelope.valid() || envelope.expiresAt <= now || envelope.createdAt > now + 300000) return false
        // The transport has proved `from`; only the author can deliver one-hop controls.
        if (!local && envelope.kind in setOf("request", "receipt") && from != envelope.author) return false
        if (seen.containsKey(envelope.id)) { held.remove(envelope.id); return false }
        if (seen.size >= PUBLIC_MAX_SEEN) return false
        seen[envelope.id] = envelope.expiresAt
        // A request is durable evidence of what its author asserted. It is not witnessed
        // merely because it arrived directly; the separate witness is what strengthens it.
        if (envelope.kind != "receipt" && !isBlocked(envelope.author)) facts[envelope.id] = envelope
        if (local) localAuthors.add(envelope.author)
        if (local || envelope.kind !in setOf("request", "receipt")) {
            held[envelope.id] = PublicHeld(envelope, minOf(envelope.expiresAt, now + PUBLIC_CARRY_MS), mutableSetOf(from))
            while (held.size > PUBLIC_MAX_HELD || held.values.sumOf { it.envelope.record().toByteArray().size } > 128000) held.remove(held.keys.first())
        }
        // Credit is always for a neighbour *this* device should prefer, which is why the two
        // directions read different fields. A receipt this phone authored names the neighbour
        // that delivered the Fact, in `text`. A receipt arriving over the air names its own
        // sender, and `from` is the handle the transport proved — never `text`, which on a
        // stranger's receipt would be some third party's handle this device cannot route to.
        if (envelope.kind == "receipt") {
            val neighbour = if (local) envelope.text else from
            if (neighbour.isNotBlank()) useful[neighbour] = minOf(envelope.expiresAt, now + PUBLIC_RECEIPT_MS)
        }
        return true
    }
    fun offer(peer: String, now: Long, participationEnds: Map<String, Long> = emptyMap()): List<GossipEnvelope> {
        prune(now)
        // The encoder owns the byte budget, including the actual relay proof header.
        return held.values.filter { held ->
            val deadlines = (held.envelope.formerIds + held.envelope.gigId).mapNotNull(participationEnds::get)
            // A receipt is addressed, not gossiped: it names one neighbour and is worth
            // nothing to anyone else, who would only learn that this phone stood near them.
            val addressed = held.envelope.kind != "receipt" || held.envelope.text == peer
            addressed && peer !in held.delivered && (deadlines.isEmpty() || deadlines.any { now < it })
        }
            .sortedWith(compareByDescending<PublicHeld> { it.envelope.createdAt }.thenByDescending { it.envelope.id })
            .take(GOSSIP_MAX_BATCH).map { it.envelope }
    }
    fun delivered(peer: String, ids: List<String>) { ids.forEach { held[it]?.delivered?.add(peer) } }
    fun project(gigIds: Set<String>): List<GossipEnvelope> {
        val latestScope = facts.values.groupBy { it.author to it.scope }.mapValues { (_, values) -> values.maxWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id }) }
        return facts.values.filter { fact ->
            !isBlocked(fact.author) && latestScope[fact.author to fact.scope]?.let { (it.formerIds + it.gigId).any(gigIds::contains) } == true
        }.groupBy { Triple(it.author, it.scope, if (it.kind == "log") "line:${it.line}" else it.id) }
            .values.map { versions -> versions.maxWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id }) }
            .sortedWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id })
    }

    /** The locally-authored claim that can witness [request], if this phone checked into
     * the same Gig. A remote request is never enough to make this device a witness. */
    fun localClaimFor(request: GossipEnvelope): GossipEnvelope? = facts.values
        .filter { it.kind == "request" && it.author in localAuthors && it.sameGig(request) }
        .maxWithOrNull(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id })

    /** A relayed witness carries the arrival claim even when its one-hop request never reached us. */
    fun arrivals(gigIds: Set<String>): List<GossipEnvelope> = project(gigIds)
        .mapNotNull { fact -> when (fact.kind) {
            "request" -> fact
            "witness" -> decodePublicEnvelope(fact.text)
            else -> null
        } }
        .filter { !isBlocked(it.author) && it.author !in localAuthors }
        .distinctBy { it.id }

    /** Preserve self-assertion and witnessed evidence as two answers. */
    fun checkInEvidence(gigIds: Set<String>, author: String): Pair<Boolean, Boolean> {
        val claims = facts.values.filter {
            it.kind == "request" && it.author == author && (it.formerIds + it.gigId).any(gigIds::contains)
        }
        val mine = claims.map { it.id }.toSet()
        return (claims.isNotEmpty() to witnessedClaims().any { it.id in mine })
    }

    /** The claims some directly-present device signed a witness for, whoever wrote them. */
    private fun witnessedClaims(): List<GossipEnvelope> = facts.values
        .filter { it.kind == "witness" && !isBlocked(it.author) }
        .mapNotNull { decodePublicEnvelope(it.text) }

    /**
     * Every **Gig** id this phone claimed and a directly-present device witnessed (#442).
     *
     * One pass for the whole timeline, because the alternative - asking per **Gig** - would
     * mint a **Gig** identity per row just to learn its author key. Both the claim's current
     * id and the ids it was known by before are returned, so a setlist.fm id arriving after
     * the night still matches the row it belongs to.
     *
     * Self-assertion is deliberately not in here. That is StoredAttendance's answer and this
     * decorates it; a night nobody witnessed is still a night the user says they were at.
     */
    fun witnessedGigIds(): Set<String> = witnessedClaims()
        .filter { it.author in localAuthors }
        .flatMap { it.formerIds + it.gigId }.toSet()
}

/**
 * Which of this device's own claims a **Pass** is signed for, and what may ride with it (#442).
 *
 * A one-hop request is admissible to the receiver only when the **Pass** proves the request
 * author's key, so carrying a request means signing as its author rather than as the nightly
 * relay. Only one author can be proved per **Pass**, so any *other* device's request is
 * dropped from this batch — it is not lost, it simply waits for a **Pass** of its own.
 *
 * Returns the request to sign as, or `null` to sign as the relay. Everything that is not a
 * request travels either way: a fact does not need its author on the envelope to be believed.
 */
fun passAuthor(batch: List<GossipEnvelope>, localAuthors: Set<String>): GossipEnvelope? =
    batch.firstOrNull { it.kind == "request" && it.author in localAuthors }

/**
 * The key a **Pass** proves that this device can also *address* later, or `null` when it
 * proves one it cannot.
 *
 * A receipt is delivered by naming a peer and waiting to meet it, and the only identity a
 * meeting ever presents is the one in the challenge, which is always the nightly relay key.
 * A **Pass** is signed as the relay too — except when it carries its signer's own request,
 * the one case [passAuthor] reaches for a Gig key for. That Gig key is a *signing* namespace,
 * never an addressing one: a receipt naming it names something no peer will ever equal, so it
 * would sit in `held` until it expired and its credit would sit in `useful` unreadable.
 *
 * So the relay key is read off the wire rule rather than guessed: a **Pass** the receiver
 * would admit a request from is signed as a Gig, and this device has no way to address its
 * sender. It authors no receipt then, rather than an undeliverable one. The neighbour's next
 * push carries no request — [passBatch] admits none without one to sign as — and is therefore
 * addressable.
 */
fun passRelay(pass: PublicGossipPass): String? =
    pass.from.takeIf { from -> pass.batch.none { it.kind == "request" && it.author == from } }

/**
 * The batch [passAuthor] leaves admissible, given the request it chose to sign as and the
 * key [signer] the **Pass** will actually be signed with.
 *
 * Both one-hop kinds are governed here for the same reason: the receiver admits a `request`
 * or a `receipt` only when the **Pass** proves its author, so anything else this device is
 * carrying would simply be refused at the other end. A receipt whose turn this is not is not
 * lost — it waits for a **Pass** signed as the relay, exactly as another device's request does.
 *
 * [signer] has no default on purpose. An empty signer matches no author, so a defaulted call
 * silently drops every receipt in the batch — a whole feature turned off by an argument nobody
 * typed. Making it required means a caller has to say which key the **Pass** is signed with,
 * which is the one thing this function cannot guess.
 */
fun passBatch(batch: List<GossipEnvelope>, request: GossipEnvelope?, signer: String): List<GossipEnvelope> =
    batch.filter { envelope -> when (envelope.kind) {
        "request" -> request != null && envelope.author == request.author
        "receipt" -> envelope.author == signer
        else -> true
    } }

/**
 * The witness this device owes a **request** it just admitted, or nothing (#442, story 8).
 *
 * The whole of the self-witness rule in one named place: [PublicGossipState.localClaimFor]
 * answers "did *I* check into this **Gig**", and only an answer makes a witness. It lived in
 * `GossipService` as a fold over the batch, where no unit test could reach it — a rule that
 * decides what this phone signs for a stranger should not be reachable only through a BLE
 * callback. [sign] is taken per claim rather than given, because the key a witness is signed
 * with is the *local* claim's Gig key, which the caller cannot know before this function has
 * picked the claim.
 *
 * Nothing about the request's own admissibility is re-decided here: `receive` already applied
 * the one-hop rule that a `request` is believed only from its author, and it is the caller's
 * business to have admitted it first.
 */
fun witnessFor(
    state: PublicGossipState,
    request: GossipEnvelope,
    now: Long,
    sign: (GossipEnvelope) -> ((ByteArray) -> ByteArray?),
): GossipEnvelope? {
    val local = state.localClaimFor(request) ?: return null
    return witnessRequest(request, local, now, sign(local))
}

/** A direct witness is its own signed fact and embeds the complete signed claim. */
fun witnessRequest(
    request: GossipEnvelope,
    witness: GossipEnvelope,
    now: Long,
    sign: (ByteArray) -> ByteArray?,
): GossipEnvelope? {
    if (request.kind != "request" || witness.kind != "request" || !request.valid() ||
        request.author == witness.author || !request.sameGig(witness) || now < request.createdAt || now >= request.expiresAt
    ) return null
    return GossipEnvelope(
        gigId = witness.gigId, formerIds = witness.formerIds, scope = witness.scope,
        author = witness.author, createdAt = now, expiresAt = minOf(request.expiresAt, witness.expiresAt),
        kind = "witness", text = request.record(), attribution = witness.attribution,
    ).signed(sign)
}

/**
 * The receipt owed for a **Fact** this device has just admitted, or nothing (#444, stories 35-37).
 *
 * Pure and one-shot: it is handed the delivering neighbour and whether the Fact was recognised
 * as a **Contact**'s, and it authors a **Fact** of kind `receipt` naming that neighbour. It
 * cannot be reached except from the receive path, which is what makes story 37 structural
 * rather than a rule — attribution that arrives later, through `recognizeContacts` after an
 * **Exchange**, runs somewhere else entirely and authors nothing. There is deliberately no
 * timestamp comparison here to enforce that; a comparison would imply lateness is reachable.
 *
 * [recognised] is the caller's answer to "is this a Contact's, *now*". [from] is whatever
 * handle the transport proved, and the receipt is worth nothing to anyone but that neighbour —
 * [PublicGossipState.offer] is where that is enforced.
 *
 * [author] is the key the **Pass** carrying this will be signed with, because the receiver
 * admits a receipt only from its author; [passBatch] holds the other half of that bargain.
 */
fun receiptFor(
    fact: GossipEnvelope,
    from: String,
    recognised: Boolean,
    author: String,
    now: Long,
    sign: (ByteArray) -> ByteArray?,
): GossipEnvelope? {
    if (!recognised || fact.kind == "receipt" || from.isBlank() || author.isBlank()) return null
    if (listOf(from, author).any { it.contains('\n') || it.contains('\t') }) return null
    return GossipEnvelope(
        gigId = fact.gigId, formerIds = fact.formerIds, scope = fact.scope,
        author = author, createdAt = now, expiresAt = now + PUBLIC_RECEIPT_MS,
        kind = "receipt", text = from,
    ).signed(sign)
}

/**
 * The receipts owed to one neighbour for one batch, which is at most one per **Gig** record.
 *
 * [receiptFor] copies `gigId`, `formerIds` and `scope` from the **Fact** and fills everything
 * else from the batch, so two **Facts** of the same record — two lines of one `log`, the
 * ordinary case — author byte-identical receipts with the same `id`. Fed one at a time into
 * `receive`, the second is a duplicate, and the Storm gate answers a duplicate by dropping the
 * held copy: two recognised **Facts** from a neighbour used to yield no receipt at all.
 *
 * A receipt says "this neighbour handed me something I wanted", which is a fact about the
 * neighbour and not about the line, so one per record is the whole of what there was to say.
 * De-duplicating here rather than in `receive` keeps [receiptFor] pure and leaves the Storm
 * gate exactly where it was.
 *
 * Every filter [receiptFor] would apply runs *before* the de-duplication, never after. A batch
 * admitted from a neighbour can contain a `receipt` of its own, and a receipt carries the
 * `gigId`, `formerIds` and `scope` of the **Fact** it was for — so it can share a record
 * identity with a **Fact** in the same batch. De-duplicating first would let it win the slot
 * and then yield nothing, silently swallowing the receipt that record actually owed.
 */
fun receiptsFor(
    facts: List<GossipEnvelope>,
    from: String,
    recognised: (GossipEnvelope) -> Boolean,
    author: String,
    now: Long,
    sign: (ByteArray) -> ByteArray?,
): List<GossipEnvelope> = facts.filter { it.kind != "receipt" && recognised(it) }
    .distinctBy { Triple(it.gigId, it.formerIds, it.scope) }
    .mapNotNull { receiptFor(it, from, true, author, now, sign) }

/** Self-contained changed lines; timestamps on StoredLog remain the original observations. */
fun gossipLogChanges(before: io.github.magnusencoded.stationtostation.data.StoredLog,
                     after: io.github.magnusencoded.stationtostation.data.StoredLog): Map<Int, String> {
    val old = before.songs.indices.associate { before.lineNumberAt(it) to before.songs[it] }
    val new = after.songs.indices.associate { after.lineNumberAt(it) to after.songs[it] }
    return (old.keys + new.keys).mapNotNull { line ->
        val text = new[line] ?: ""
        if (old[line] == new[line]) null else line to text
    }.toMap()
}


/** A display row retains every source; alignment never writes into a local Log. */
data class GossipLogRow(val base: Int?, val text: String?, val facts: List<GossipEnvelope> = emptyList())

fun weaveGossip(base: List<String?>, facts: List<GossipEnvelope>): List<GossipLogRow> {
    var rows = base.mapIndexed { index, text -> GossipLogRow(index, text) }
    // Each author supplies an ordered sequence. Align sequences, never a set of titles:
    // two occurrences within a sequence must remain two occurrences on screen.
    facts.filter { it.kind == "log" }.groupBy { it.author + "\n" + it.scope }.toSortedMap().values.forEach { versions ->
        val source = versions.sortedBy { it.line }
        rows = io.github.magnusencoded.stationtostation.data.weaveSetlist(rows.map { it.text }, source.map { it.text }).map { match ->
            val previous = match.published?.let { rows[it] }
            val fact = match.logged?.let { source[it] }
            GossipLogRow(previous?.base, previous?.text ?: fact?.text,
                previous?.facts.orEmpty() + listOfNotNull(fact))
        }
    }
    return rows
}
