package io.github.magnusencoded.stationtostation.ble

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * The card as it goes over the wire in the #30 GATT probe.
 *
 * Same deep link the QR fallback and Nearby already carry
 * (`station-to-station://friend?u=…&name=…&sid=…`) with one addition: `k`, the
 * ECDSA P-256 public key that #28/#257 made the identity (X.509
 * SubjectPublicKeyInfo, base64 — ~91 bytes in, ~124 chars out). Unknown query
 * parameters are ignored by
 * [io.github.magnusencoded.stationtostation.data.friendFromUri], so an old
 * build reading a new card still gets a usable friend.
 *
 * Plain string building rather than `android.net.Uri` so the size budget — the
 * whole point of the probe — is checkable in a JVM unit test.
 *
 * **Not the Nearby fast path's budget.** [ProbeCard] only ever rides a GATT
 * read (this file) or the QR/deep-link share, both well clear of Nearby's
 * 131-byte endpoint-name ceiling — see `AppViewModel.myCard()` for why the key
 * is deliberately absent from the Friend Nearby advertises instead.
 */
data class ProbeCard(
    val name: String,
    /** X.509 SubjectPublicKeyInfo, ECDSA P-256, base64. */
    val publicKey: String,
    val setlistfm: String? = null,
    val spotifyId: String? = null,
) {
    fun encode(): String = buildString {
        append("station-to-station://friend?name=").append(esc(name))
        append("&k=").append(esc(publicKey))
        setlistfm?.let { append("&u=").append(esc(it)) }
        spotifyId?.let { append("&sid=").append(esc(it)) }
    }

    /** What actually goes into the characteristic. UTF-8, no framing. */
    fun bytes(): ByteArray = encode().toByteArray(StandardCharsets.UTF_8)
}

private fun esc(s: String) = URLEncoder.encode(s, "UTF-8")

fun parseProbeCard(payload: String): ProbeCard? {
    val query = payload.substringAfter("://friend?", missingDelimiterValue = "")
    if (query.isEmpty()) return null
    val q = query.split('&').mapNotNull { pair ->
        val i = pair.indexOf('=')
        if (i <= 0) null else URLDecoder.decode(pair.substring(0, i), "UTF-8") to
            URLDecoder.decode(pair.substring(i + 1), "UTF-8")
    }.toMap()
    val key = q["k"]?.takeIf { it.isNotBlank() } ?: return null
    return ProbeCard(
        name = q["name"]?.takeIf { it.isNotBlank() } ?: q["u"].orEmpty(),
        publicKey = key,
        setlistfm = q["u"]?.takeIf { it.isNotBlank() },
        spotifyId = q["sid"]?.takeIf { it.isNotBlank() },
    )
}

/**
 * How much of a 31-byte scan response is left for the display name once the
 * advertising header and a manufacturer-data record are paid for.
 *
 * 31 total − 2 (length + AD type) − 2 (company id) = 27. A 128-bit service-data
 * record would instead cost 18 and leave 13, which is why the probe uses
 * manufacturer data. See [BleCardPeripheral] for what iOS can and cannot match.
 */
const val SCAN_RESPONSE_NAME_BUDGET = 27

/**
 * Whether [advertisement] fits Nearby's endpoint name.
 *
 * Checked rather than trusted, and **not** truncated the way a scan-response name is: a
 * cut-off URI does not parse, so half a card is no card. The caller's only useful move is
 * to refuse and say so, because Nearby reports nothing — over Bluetooth Classic the name is
 * silently truncated, over BLE the advertisement is silently dropped, and either way the
 * owner is invisible with no diagnostic. That is #272's failure mode, so it is worth a
 * check even though the string is ours: `AppViewModel.myCard()` uses the setlist.fm
 * username for both `u` and `name`, and [isPlausibleSetlistFmUser] admits 64 characters of
 * any script, which is enough to overflow on a name nobody would call invalid.
 */
fun fitsAnEndpointName(advertisement: String): Boolean =
    advertisement.toByteArray(StandardCharsets.UTF_8).size <=
        NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT

/** Longest prefix of [name] whose UTF-8 encoding fits in [budget] bytes. */
fun truncateToBytes(name: String, budget: Int = SCAN_RESPONSE_NAME_BUDGET): String {
    var end = name.length
    while (end > 0 && name.substring(0, end).toByteArray(StandardCharsets.UTF_8).size > budget) end--
    // Never cut an emoji in half: a lone high surrogate encodes as '?'.
    if (end in 1 until name.length && Character.isHighSurrogate(name[end - 1])) end--
    return name.substring(0, end)
}
