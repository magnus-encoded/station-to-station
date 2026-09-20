package io.github.magnusencoded.stationtostation.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.data.Friend
import io.github.magnusencoded.stationtostation.data.gossip.GossipBullet
import io.github.magnusencoded.stationtostation.data.gossip.GossipPresence
import io.github.magnusencoded.stationtostation.data.gossip.gossipBullet
import io.github.magnusencoded.stationtostation.data.gossip.gossipNearby
import kotlinx.coroutines.delay
import java.time.Instant

private val Amber = Color(0xFFE7B24C)

/** The bullet's off state: present, plainly not lit. Slate, the app's own quiet grey. */
private val Dim = Color(0xFF8A8F98)

/**
 * A **Gig**'s **Presence row**: that this phone is standing here, whether the radio is
 * standing here with it, and who else is (#500).
 *
 * One composable rather than a bullet the **Room** assembles beside the check-in mark, because
 * the three are one statement and the whole row is the tap target. Tapping it is how the
 * **Active Gig** is chosen, and the row is only offered when there is a choice to make — a
 * night that cannot **Gossip** draws no bullet and does nothing when touched, which is the
 * honest reading of a control with nothing behind it.
 *
 * The clock is here and not in the view model: a night's grace runs out with nobody doing
 * anything, and the bullet has to go out while somebody is looking at it. [onExpiry] hands that
 * same tick back up, because the *other* night's row — the one that inherits the amber — is a
 * different **Room** and only the store knows which night is next.
 *
 * [eligibleUntil] is the deadline with **no stop applied** (see `gossipBullet`).
 */
@Composable
fun GossipPresenceRow(
    eligibleUntil: Long?,
    active: Boolean,
    stopped: Boolean,
    friends: List<Friend>,
    onSelect: () -> Unit,
    onExpiry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val bullet = gossipBullet(eligibleUntil, active, stopped, now)
    // Only a night that can still **Gossip** is worth a clock or an animation. Every **Room** on
    // a timeline has a check-in line, most of them years old, and neither the tick nor the blink
    // would ever have anything to show on those.
    if (bullet != null) {
        LaunchedEffect(eligibleUntil) {
            while (true) {
                delay(1_000)
                now = System.currentTimeMillis()
                if (gossipBullet(eligibleUntil, active, stopped, now) == null) {
                    onExpiry()
                    return@LaunchedEffect
                }
            }
        }
    }
    // One transition for both colours: the blink is what says the radio's state is live rather
    // than a label, and an amber that pulsed differently from a dim one would read as two
    // different kinds of thing.
    val alpha by rememberInfiniteTransition(label = "gossip-bullet").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "gossip-bullet-alpha",
    )

    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .let { if (bullet == null) it else it.clickable(onClick = onSelect) }
                .padding(vertical = 6.dp),
        ) {
            if (bullet != null) {
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .alpha(alpha)
                        .background(if (bullet == GossipBullet.ON) Amber else Dim, CircleShape),
                )
            }
            Text("✓ checked in", color = Amber, fontSize = 13.sp)
        }
        NearbyContacts(friends)
    }
}

/**
 * "Ange is also here" — the one thing about the gossip relay a person actually wants told.
 *
 * It lives on the gig page: that is the screen somebody has open while standing at the
 * venue, and "who else is here" is the question being asked there. Its own file rather than
 * a composable inside that screen, because the notification says the same sentence from the
 * same [GossipPresence] map — two surfaces, one answer, and nowhere for them to drift apart.
 *
 * Deliberately *not* on the **Exchange** screen, which is for adding somebody you have not
 * met yet. That screen shows what the radios are doing; this one shows who is in the room.
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
