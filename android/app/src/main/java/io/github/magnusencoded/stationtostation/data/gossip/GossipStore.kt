package io.github.magnusencoded.stationtostation.data.gossip

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.time.Instant

/**
 * A file of its own, and excluded from backup — see `backup_rules.xml`.
 *
 * Not because it holds a credential, but because of what it holds *about other people*: a
 * relayed `GossipCheckIn` names a **Contact**'s stable identity key, a **Gig**, and the
 * minute they arrived, and some of those people are, by ADR-0019's disclosure clause,
 * strangers to the owner of this phone. Every entry expires within a night, so a copy in a
 * cloud backup would outlive the thing itself by years and would move other people's
 * whereabouts off the device they were relayed to. There is nothing here worth restoring: a
 * reinstall with an empty seen set costs one round of already-relayed messages being
 * accepted again, and they expire on their own that night.
 */
private val Context.gossipStore by preferencesDataStore(name = "gossip")

/**
 * The device's memory of the gossip channel: what it still carries, and when it last spoke
 * to each **Contact** (#416).
 *
 * A thin door onto DataStore, in the shape [SettingsRepository][io.github.magnusencoded.stationtostation.data.SettingsRepository]
 * already established — every decision about what belongs in the list is in
 * [GossipHeld.kt][GossipHeld], and this only writes it down.
 *
 * The per-peer cooldown is **not** persisted, and that is deliberate: it exists to bound
 * what one peer can spend of this device's battery and CPU while the radio is on, and the
 * radio stopping is already the harder version of that bound. Persisting it would only
 * delay the first **Pass** after a restart, which is the moment there is most to say.
 */
class GossipStore(private val context: Context) {

    private object Keys {
        val HELD = stringPreferencesKey("held")
    }

    /** Everything still live, pruned on the way out — a read is also a chance to forget. */
    suspend fun held(now: Instant = Instant.now()): List<GossipHeld> =
        pruneGossipHeld(decodeGossipHeld(context.gossipStore.data.first()[Keys.HELD]), now)

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
        context.gossipStore.edit { prefs ->
            val current = pruneGossipHeld(decodeGossipHeld(prefs[Keys.HELD]), now)
            written = pruneGossipHeld(edit(current), now)
            prefs[Keys.HELD] = encodeGossipHeld(written)
        }
        return written
    }
}
