package io.github.magnusencoded.stationtostation.features.tour

import android.content.Context
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TourCharacter(
    val name: String,
    val username: String,
    val avatar: String,
    val selfie: String,
    val cutout: String,
    val lines: Map<String, Line>,
    val notes: Notes,
    val playlist: Playlist,
) {
    @Serializable
    data class Line(@SerialName("do") val instruction: String, val why: String? = null,
                    val ios: String? = null, val android: String? = null) {
        fun onAndroid() = copy(instruction = android ?: instruction)
    }
    @Serializable data class Notes(val gapFill: String, val setlistFill: String)
    @Serializable data class Playlist(val title: String, val description: String)

    fun line(mark: CoachMark): Line = lines.getValue(TourStep.entries.single { it.mark == mark }.name).onAndroid()

    fun validated(): TourCharacter = apply {
        require(listOf(name, username, avatar, selfie, cutout, notes.gapFill, notes.setlistFill,
            playlist.title, playlist.description).all { it.isNotBlank() }) { "Missing character value" }
        require(lines.keys == TourStep.entries.filter { it.mark != null }.map { it.name }.toSet()) { "Missing or unexpected card step" }
        lines.forEach { (step, line) ->
            fun check(text: String, limit: Int) {
                require(text.isNotBlank() && text.trim().split(Regex("\\s+")).size <= limit) { "$step exceeds $limit words or is blank" }
            }
            check(line.instruction, 20)
            line.why?.let { check(it, 30) }
            line.ios?.let { check(it, 20) }
            line.android?.let { check(it, 20) }
        }
    }

    companion object {
        fun decode(json: String): TourCharacter = Json.decodeFromString<TourCharacter>(json).validated()
        fun load(context: Context): TourCharacter = context.assets.open("character.json").bufferedReader().use { decode(it.readText()) }
    }
}
