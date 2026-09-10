package io.github.magnusencoded.stationtostation.data.gossip

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * Device-local gossip state, excluded from backup by both Android backup rule files.
 * Public v2 application facts survive relay expiry here, while seen IDs, outbox entries
 * and usefulness expire independently. Raw relay state does not enter timeline exports.
 * The legacy held key is retained for existing callers while authoring is replaced.
 */
private val Context.gossipStore by preferencesDataStore(name = "gossip")

/**
 * One transactional store shared by app authoring and background radio reception.
 * Every Context-backed instance uses the same DataStore delegate. The injectable
 * DataStore constructor lets restart/concurrency tests exercise real disk transactions.
 * Per-peer connection cooldowns stay in the radio; successful Envelope handoffs persist.
 */
class GossipStore(private val data: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.gossipStore)

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private object Keys {
        val HELD = stringPreferencesKey("held")
        val PUBLIC = stringPreferencesKey("public_v2")
    }

    /** Detached snapshots: radio and author observe the same committed transaction stream. */
    val publicStates = data.data.map { prefs -> decodePublic(prefs[Keys.PUBLIC]) }

    private fun decodePublic(value: String?): PublicGossipState =
        value?.let { json.decodeFromString<PublicGossipState>(it) } ?: PublicGossipState()

    /** Atomic across every caller. Expiring relay memory never deletes application facts. */
    suspend fun updatePublic(now: Long, edit: (PublicGossipState) -> Unit) {
        data.edit { prefs ->
            val state = decodePublic(prefs[Keys.PUBLIC])
            state.prune(now)
            edit(state)
            prefs[Keys.PUBLIC] = json.encodeToString(PublicGossipState.serializer(), state)
        }
    }

    /** Everything still live, pruned on the way out — a read is also a chance to forget. */
    suspend fun held(now: Instant = Instant.now()): List<GossipHeld> =
        pruneGossipHeld(decodeGossipHeld(data.data.first()[Keys.HELD]), now)

    /**
     * Read, prune, edit and write as one operation.
     *
     * `edit` rather than a read followed by a save: the radio's two halves both land here —
     * a peer pushing to this device and this device minting its own check-in — and a
     * read-modify-write pair would let one of them overwrite the other's message with a
     * list it had already read. Returns what was actually stored, so the caller acts on the
     * same list the disk now holds.
     */
    suspend fun update(
        now: Instant = Instant.now(),
        edit: (List<GossipHeld>) -> List<GossipHeld>,
    ): List<GossipHeld> {
        var written = emptyList<GossipHeld>()
        data.edit { prefs ->
            val current = pruneGossipHeld(decodeGossipHeld(prefs[Keys.HELD]), now)
            written = pruneGossipHeld(edit(current), now)
            prefs[Keys.HELD] = encodeGossipHeld(written)
        }
        return written
    }
}
