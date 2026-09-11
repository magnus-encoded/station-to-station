package io.github.magnusencoded.stationtostation.data.gossip

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID

/**
 * Device-local gossip state, excluded from backup by both Android backup rule files.
 * Public v2 application facts survive relay expiry here, while seen IDs, outbox entries
 * and usefulness expire independently. Raw relay state does not enter timeline exports.
 *
 * One key, `public_v2`, plus the author-scope bindings. The v1 `held` key is gone with the
 * v1 pipeline: nothing read it, and a preferences key nobody reads is a stale copy of the
 * night waiting to be mistaken for the live one. An existing install's leftover `held`
 * entry is simply never touched again, and goes when the app's data does.
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
        val PUBLIC = stringPreferencesKey("public_v2")
        val SCOPES = stringPreferencesKey("author_scopes_v2")
    }

    /** Bind a random signing scope to the local Gig, never its mutable external ID.
     * Kept separately from relay state: expiry must not rotate an author's identity.
     */
    suspend fun authorScope(localGigId: String): String {
        require(localGigId.isNotBlank())
        var scope = ""
        data.edit { prefs ->
            val bindings = prefs[Keys.SCOPES]?.let {
                json.decodeFromString<Map<String, String>>(it)
            }.orEmpty()
            scope = bindings[localGigId] ?: UUID.randomUUID().toString()
            if (localGigId !in bindings) {
                prefs[Keys.SCOPES] = json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, String>>(),
                    bindings + (localGigId to scope),
                )
            }
        }
        return scope
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
}
