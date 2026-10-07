package io.github.magnusencoded.stationtostation.features.tour

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The **Virtual friend**, read from `fixtures/tour/character/character.json`, which iOS reads
 * too. Swapping the character changes that file and its two drawables, never this code.
 */
@Serializable
data class TourCharacter(
    val name: String,
    val avatar: String,
    val selfie: String,
    val lines: Map<String, String>,
    val playlist: Playlist,
) {
    @Serializable
    data class Playlist(val title: String, val description: String)

    /** The friend's line for [step], keyed by the script table's step id. */
    fun line(step: TourStep): String = lines.getValue(step.name)

    companion object {
        const val ASSET = "character.json"

        fun parse(json: String): TourCharacter = Json.decodeFromString(serializer(), json)

        fun load(context: Context): TourCharacter =
            parse(context.assets.open(ASSET).bufferedReader().use { it.readText() })
    }
}
