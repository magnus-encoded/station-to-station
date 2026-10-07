package io.github.magnusencoded.stationtostation.features.tour

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel

/** One dismissable card over the whole app; the **Tour** has its own overlay for coach marks. */
@Composable
fun ContextHintOverlay(viewModel: AppViewModel, content: @Composable () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        content()
        val hint = state.contextHint
        if (hint != null && !state.tour.running) {
            Card(
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                    .testTag("contextHint"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xF21D1728)),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(hint.text, modifier = Modifier.weight(1f), color = Color(0xFFEDE9F2))
                    TextButton(onClick = { viewModel.tour.hints.dismiss() }) {
                        Text("Dismiss hint", color = Color(0xFFE7B24C))
                    }
                }
            }
        }
    }
}
