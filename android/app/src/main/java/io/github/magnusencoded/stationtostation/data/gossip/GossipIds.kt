package io.github.magnusencoded.stationtostation.data.gossip

/*
 * The id rule the public gossip v2 codec validates with.
 *
 * v1 still declares its own `isSafeGossipId`, `gossipExpiry` and `contactKeysOf` in
 * `data/GossipStormGate.kt` (package `data`). Only the id rule is needed by the v2 core, so
 * only it lives here for now; the other two move into this file when v1 is removed (#462).
 */

/**
 * Whether a **Gig** id or an author scope is safe to hold, compare and propagate.
 *
 * Deliberately **stricter than [isSafeMediaId][io.github.magnusencoded.stationtostation.data.isSafeMediaId]**,
 * and deliberately not it, though it is the same shape and exists for the same reason
 * (this id reaches a store as a key and could reach a file path). `isSafeMediaId` is
 * Unicode-aware on both platforms in ways that do not agree: Kotlin measures UTF-16 code
 * units and asks `Char.isLetterOrDigit`, Swift measures grapheme clusters and asks
 * `CharacterSet.alphanumerics`, so an astral-plane alphanumeric or a combining mark is
 * accepted by one twin and refused by the other. On a media id that is a nuisance; on a
 * gossip id it is an envelope that propagates through iPhones and dies at every Android
 * hop, which is the exact asymmetry a ported file exists to prevent.
 *
 * ASCII only, therefore: with no character above 0x7F, code units, scalars, graphemes and
 * bytes are all the same count, and the two implementations cannot read the rule
 * differently. Nothing real is lost — a gig id is a UUID or a setlist.fm id, and a scope is
 * a UUID this device minted.
 */
fun isSafeGossipId(id: String): Boolean =
    id.isNotEmpty() && id.length <= 64 && id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }
