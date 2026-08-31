package io.github.magnusencoded.stationtostation.data

/**
 * How this app names itself to every host it asks anything of.
 *
 * It started as MusicBrainz's requirement — an open database asks who is calling and how
 * to reach them, and that is the whole deal. It is not theirs any more: clashfinder's
 * data endpoint takes an account's own credentials, so it is meant to be called by
 * programs, and saying which program beats the HTTP library's default. One string,
 * because the answer to "who is this" should not depend on which host asked.
 *
 * Never a browser's. This is not a browser, and a request that claims to be one is
 * lying to the host about what it is talking to.
 */
const val USER_AGENT =
    "StationToStation/1.0 ( https://github.com/magnus-encoded/station-to-station )"
