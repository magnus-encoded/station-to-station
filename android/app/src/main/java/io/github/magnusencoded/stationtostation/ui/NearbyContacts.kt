package io.github.magnusencoded.stationtostation.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.gossip.GossipPresence
import io.github.magnusencoded.stationtostation.data.gossip.gossipNearby
import kotlinx.coroutines.delay
import java.time.Instant

private val Amber = Color(0xFFE7B24C)

/**
 * "Ange is also here" — the one thing about the gossip relay a person actually wants told.
 *
 * Its own file because it belongs on more than one screen and must say the same thing on
 * each. The gig page is the important one: that is the screen somebody has open while
 * standing at the venue, and "who else is here" is the question being asked there. The
 * **Exchange** screen carries it too, but that screen is for *adding* a person, which is a
 * different question that happens to have the same answer.
 *
 * It says nothing when nobody is nearby, deliberately. "Nobody is here" is a claim this phone
 * cannot make — a **Contact** in the same room with their phone in a pocket, or out of
 * Bluetooth range across a hall, is not absent — so an empty room gets silence rather than a
 * line asserting emptiness. That also keeps it out of the way on every screen it sits on,
 * every night nothing is happening.
 *
 * A **Contact** without a name shows as "Someone" rather than being dropped: they were heard
 * from, and losing them would report a quieter room than the one being stood in.
 */
@Composable
fun NearbyContacts(friends: List<Friend>, modifier: Modifier = Modifier) {
    val metAt by GossipPresence.metAt.collectAsStateWithLifecycle()
    // Presence is measured in minutes, so this only has to be often enough that somebody who
    // walked off stops being named within a few seconds of the window running out.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Instant.now()
            delay(10_000)
        }
    }

    val here = gossipNearby(metAt, now).map { key ->
        friends.firstOrNull { it.publicKey == key }?.name ?: "Someone"
    }
    if (here.isEmpty()) return

    Text(
        when (here.size) {
            1 -> "${here[0]} is also here"
            2 -> "${here[0]} and ${here[1]} are also here"
            else -> "${here[0]} and ${here.size - 1} others are also here"
        },
        color = Amber,
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier,
    )
    Spacer(Modifier.height(8.dp))
}
