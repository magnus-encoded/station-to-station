package io.github.magnusencoded.stationtostation.data.gossip

import io.github.magnusencoded.stationtostation.data.exchange.verifyChallenge
import io.github.magnusencoded.stationtostation.data.isSafeGossipId
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

const val PUBLIC_GOSSIP_HEADER = "station-to-station/gossip-fact/2"
const val PUBLIC_GOSSIP_PASS = "station-to-station/gossip-pass/2"
const val PUBLIC_CARRY_MS = 15 * 60 * 1000L
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
    for (envelope in pass.batch.take(64)) {
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
    return PublicGossipPass(claim[0], claim[1], lines.drop(2).take(64).mapNotNull(::decodePublicEnvelope))
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
    val useful: MutableMap<String, Long> = mutableMapOf(),
) {
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
        // Direct requests and usefulness signals never enter the durable Gig record.
        if (envelope.kind in setOf("log", "witness") && envelope.author !in blocked) facts[envelope.id] = envelope
        if (local || envelope.kind !in setOf("request", "receipt")) {
            held[envelope.id] = PublicHeld(envelope, minOf(envelope.expiresAt, now + PUBLIC_CARRY_MS), mutableSetOf(from))
            while (held.size > PUBLIC_MAX_HELD || held.values.sumOf { it.envelope.record().toByteArray().size } > 128000) held.remove(held.keys.first())
        }
        if (envelope.kind == "receipt") useful[envelope.text] = minOf(envelope.expiresAt, now + 120000)
        return true
    }
    fun offer(peer: String, now: Long): List<GossipEnvelope> {
        prune(now)
        // The encoder owns the byte budget, including the actual relay proof header.
        return held.values.filter { peer !in it.delivered }
            .sortedWith(compareByDescending<PublicHeld> { it.envelope.createdAt }.thenByDescending { it.envelope.id })
            .take(64).map { it.envelope }
    }
    fun delivered(peer: String, ids: List<String>) { ids.forEach { held[it]?.delivered?.add(peer) } }
    fun project(gigIds: Set<String>): List<GossipEnvelope> {
        val latestScope = facts.values.groupBy { it.author to it.scope }.mapValues { (_, values) -> values.maxWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id }) }
        return facts.values.filter { fact ->
            fact.author !in blocked && latestScope[fact.author to fact.scope]?.let { (it.formerIds + it.gigId).any(gigIds::contains) } == true
        }.groupBy { Triple(it.author, it.scope, if (it.kind == "log") "line:${it.line}" else it.id) }
            .values.map { versions -> versions.maxWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id }) }
            .sortedWith(compareBy<GossipEnvelope> { it.createdAt }.thenBy { it.id })
    }
}
