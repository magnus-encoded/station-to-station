package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import io.github.magnusencoded.stationtostation.AppViewModel
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.ui.laneColourOf
import io.github.magnusencoded.stationtostation.ui.railColor

private val Card = Color(0xFF1D1728)
private val Ink = Color(0xFFF1ECF8)
private val Muted = Color(0xFF8F87A0)
private val Amber = Color(0xFFE8A33D)

internal fun tourAccent(state: UiState): Color = state.friends.firstOrNull { it.demo }
    ?.let { laneColourOf(it, state.friends) }
    // Before the Exchange there is no Contact; use the first Contact's palette index.
    ?: railColor(0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TourOverlay(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var dockHeight by remember { mutableStateOf(155.dp) }
    val density = LocalDensity.current
    val dockTop = WindowInsets.isImeVisible && state.tour.step in setOf(TourStep.S14, TourStep.S15)
    Box(Modifier.fillMaxSize().background(Color(0xFF0E0B14))) {
        val hasCard = state.tour.running && state.coachMark != null && state.tour.step !in setOf(TourStep.S3, TourStep.S4)
        Box(Modifier.padding(top = if (dockTop && hasCard) dockHeight else 0.dp,
            bottom = if (!dockTop && hasCard) dockHeight else 0.dp)) { content() }
        if (state.tourUpgradePrompt) {
            UpgradePromptCard(onAccept = { viewModel.tour.acceptUpgradePrompt() },
                onDismiss = { viewModel.tour.dismissUpgradePrompt() })
        } else if (state.tour.running && state.tour.step !in setOf(TourStep.S3, TourStep.S4)) {
            state.coachMark?.let { mark ->
                CoachMarkCard(viewModel.tour.character, mark, tourAccent(state),
                    onAcknowledge = { viewModel.tour.acknowledgeCard() },
                    opener = viewModel.tour.opener,
                    onSkip = { viewModel.tour.dispatch(TourEvent.Skipped) },
                    modifier = (if (dockTop) Modifier.align(Alignment.TopCenter).statusBarsPadding()
                               else Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                        .onSizeChanged { dockHeight = with(density) { it.height.toDp() } })
            } ?: TextButton(onClick = { viewModel.tour.dispatch(TourEvent.Skipped) },
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()) { Text("Skip tour") }
        }
    }
}

@Composable
fun TourCoachMarkInline(viewModel: AppViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.tour.running || state.tour.step !in setOf(TourStep.S3, TourStep.S4)) return
    state.coachMark?.let { mark ->
        CoachMarkCard(viewModel.tour.character, mark, tourAccent(state),
            onAcknowledge = { viewModel.tour.acknowledgeCard() },
            onSkip = { viewModel.tour.dispatch(TourEvent.Skipped) }, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
internal fun CoachMarkCard(
    character: TourCharacter,
    mark: CoachMark,
    accent: Color,
    onAcknowledge: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    opener: String? = null,
) {
    val slant = with(LocalDensity.current) { 8.dp.toPx() }
    val line = character.line(mark, opener)
    val context = LocalContext.current
    val portrait = remember(character.cutout) {
        context.resources.getIdentifier(character.cutout, "drawable", context.packageName)
    }
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 155.dp).testTag("coachMark")) {
        Box(Modifier.align(Alignment.BottomEnd).width(124.dp).height(148.8.dp)) {
            val painter = painterResource(portrait)
            Image(painter, null, Modifier.fillMaxSize().offset(y = 2.dp).blur(4.dp),
                colorFilter = ColorFilter.tint(Color.Black.copy(alpha = .5f)))
            for ((x, y) in listOf(-.6f to 0f, .6f to 0f, 0f to -.6f, 0f to .6f)) {
                Image(painter, null, Modifier.fillMaxSize().offset(x.dp, y.dp),
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = .45f)))
            }
            Image(painter, null, Modifier.fillMaxSize())
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, top = 10.dp, bottom = 6.dp)
            .width((maxWidth - (124.dp * .94f) - 6.dp).coerceAtLeast(1.dp))) {
            Column(Modifier.fillMaxWidth().background(Card, RoundedCornerShape(14.dp))
                .border(1.dp, Color(0xFF3A3248), RoundedCornerShape(14.dp))
                .drawBehind { drawLine(accent, Offset(1.5.dp.toPx(), 0f), Offset(1.5.dp.toPx(), size.height), 3.dp.toPx()) }
                .padding(start = 14.dp, top = 12.dp, end = 11.dp, bottom = 7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(character.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(accent, GenericShape { size, _ ->
                    moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width - slant, size.height); lineTo(0f, size.height); close()
                }).padding(start = 9.dp, end = 16.dp, top = 2.dp, bottom = 2.dp))
                Text(line.instruction, color = Ink, fontFamily = FontFamily.Serif, fontSize = 14.sp,
                    lineHeight = 18.2.sp, fontWeight = FontWeight.SemiBold)
                line.why?.let { Text(it, color = Color(0xFFCFC7DE), fontFamily = FontFamily.Serif,
                    fontSize = 12.sp, lineHeight = 16.2.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onSkip, contentPadding = PaddingValues(2.dp)) { Text("Skip", color = Muted, fontSize = 12.sp) }
                    TextButton(onClick = onAcknowledge, contentPadding = PaddingValues(2.dp)) {
                        Text(if (mark == CoachMark.Line || mark == CoachMark.Gossip) "Got it" else "OK", color = Amber, fontSize = 12.sp)
                    }
                }
            }

        }
    }
}

/** App copy, not the Virtual friend's: offered once to an install that predates the Tour. */
@Composable
private fun BoxScope.UpgradePromptCard(onAccept: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .background(Card, RoundedCornerShape(16.dp))
            .padding(20.dp)
            .testTag("tourUpgradePrompt"),
    ) {
        Text("New: a guided tour", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Walk through the app at a demo gig. It takes a few minutes and leaves nothing behind.",
            color = Ink,
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onDismiss) { Text("No thanks", color = Muted) }
            TextButton(onClick = onAccept) { Text("Take the tour", color = Amber) }
        }
    }
}
