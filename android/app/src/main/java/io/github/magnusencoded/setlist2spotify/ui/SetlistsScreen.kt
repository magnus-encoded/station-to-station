package io.github.magnusencoded.setlist2spotify.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.setlist2spotify.AppViewModel
import io.github.magnusencoded.setlist2spotify.SetlistSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetlistsScreen(
    viewModel: AppViewModel,
    onSetlistPicked: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.setlistsTitle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            if (state.setlists.isEmpty() && !state.setlistsLoading) {
                Text(
                    "No setlists found.",
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.setlists, key = { it.id }) { setlist ->
                    val songCount = setlist.songs().size
                    ListItem(
                        headlineContent = {
                            val title = if (state.source == SetlistSource.USER) {
                                "${setlist.eventDate ?: "?"} · ${setlist.artist?.name ?: "Unknown artist"}"
                            } else {
                                setlist.eventDate ?: "Unknown date"
                            }
                            Text(title)
                        },
                        supportingContent = {
                            Column {
                                Text(setlist.venueLine())
                                setlist.tour?.name?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        trailingContent = { Text("$songCount songs") },
                        modifier = Modifier.clickable(enabled = songCount > 0) {
                            viewModel.selectSetlist(setlist)
                            onSetlistPicked()
                        },
                    )
                    HorizontalDivider()
                }
                item {
                    when {
                        state.setlistsLoading -> Row(
                            Modifier.fillMaxWidth().padding(24.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) { CircularProgressIndicator() }

                        state.setlists.size < state.setlistsTotal -> Row(
                            Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            OutlinedButton(onClick = { viewModel.loadMoreSetlists() }) {
                                Text("Load more (${state.setlists.size}/${state.setlistsTotal})")
                            }
                        }
                    }
                }
            }
        }
    }
}
