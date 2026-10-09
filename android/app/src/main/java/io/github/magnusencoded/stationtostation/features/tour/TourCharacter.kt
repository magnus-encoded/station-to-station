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
    val history: List<History> = emptyList(),
) {
    @Serializable
    data class Line(@SerialName("do") val instruction: String, val why: String? = null,
                    val ios: String? = null, val android: String? = null, val fallback: String? = null,
                    val screens: Map<String, Line> = emptyMap()) {
        fun onAndroid() = copy(instruction = android ?: instruction)
    }
    @Serializable data class Notes(val gapFill: String, val setlistFill: String)
    @Serializable data class History(val artist: String, val date: String, val venue: String, val city: String)
    @Serializable data class Playlist(val title: String, val description: String)

    fun line(mark: CoachMark, opener: String? = null, screen: String = "line"): Line {
        val base = lines.getValue(TourStep.entries.single { it.mark == mark }.name)
        val selected = base.screens[screen] ?: base.screens[if (screen == "roomAfterPhoto") "room" else "line"] ?: base
        val line = selected.copy(why = selected.why ?: base.why).onAndroid()
        return line.copy(instruction = if ("{opener}" in line.instruction) {
            opener?.takeIf { it.isNotBlank() }?.let { line.instruction.replace("{opener}", it) } ?: requireNotNull(line.fallback)
        } else line.instruction)
    }

    fun validated(): TourCharacter = apply {
        require(listOf(name, username, avatar, selfie, cutout, notes.gapFill, notes.setlistFill,
            playlist.title, playlist.description).all { it.isNotBlank() }) { "Missing character value" }
        require(lines.keys == TourStep.entries.filter { it.mark != null }.map { it.name }.toSet()) { "Missing or unexpected card step" }
        require(history.isNotEmpty() && history.all {
            listOf(it.artist, it.venue, it.city).all(String::isNotBlank) &&
                io.github.magnusencoded.stationtostation.data.parseFmDate(it.date) != null
        }) { "Missing or invalid demo history" }
        val screens = setOf("line", "lineEmpty", "room", "roomAfterPhoto", "exchange")
        require(lines.values.all { line ->
            line.screens.keys.all { it in screens } && line.screens.values.all { it.screens.isEmpty() }
        }) { "Unknown or nested screen" }
        lines.flatMap { (step, line) -> listOf(step to line) + line.screens.map { step to it.value } }.forEach { (step, line) ->
            fun check(text: String, limit: Int) {
                require(text.isNotBlank() && text.trim().split(Regex("\\s+")).size <= limit) { "$step exceeds $limit words or is blank" }
            }
            listOfNotNull(line.instruction, line.why, line.ios, line.android, line.fallback).forEach { text ->
                val stripped = if (step == "S14" && text == line.instruction) text.replace("{opener}", "") else text
                require('{' !in stripped && '}' !in stripped) { "$step has an unknown placeholder" }
            }
            if ("{opener}" in line.instruction) require(!line.fallback.isNullOrBlank())
            line.fallback?.let { check(it, 20) }
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
