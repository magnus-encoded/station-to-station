package io.github.magnusencoded.stationtostation.data

/**
 * How this app names itself to every host it asks anything of.
 *
 * One string, because the answer to "who is this" does not depend on which host asked.
 * MusicBrainz requires it; clashfinder's data endpoint takes an account's own
 * credentials and so is meant to be called by a program, which this names.
 *
 * Never a browser's. This is not a browser, and a request that claims to be one is
 * lying to the host about what it is talking to.
 */
const val USER_AGENT =
    "StationToStation/1.0 ( https://github.com/magnus-encoded/station-to-station )"
