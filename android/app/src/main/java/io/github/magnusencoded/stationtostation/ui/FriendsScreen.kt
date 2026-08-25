package io.github.magnusencoded.stationtostation.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.magnusencoded.stationtostation.AppViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    viewModel: AppViewModel,
    onOpenShared: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var addQuery by remember { mutableStateOf("") }
    var linkQuery by remember { mutableStateOf("") }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.notice) {
        state.notice?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Friends") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().swipeRightToBack(onBack = onBack)) {
            Column(Modifier.padding(16.dp)) {
                Text("Your setlist.fm username", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Needed to find concerts you and a friend both attended, and to share " +
                        "your friend card. Friends aren't on any server — you swap cards directly.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.mySetlistFmUser,
                        onValueChange = viewModel::saveMySetlistFmUser,
                        label = { Text("setlist.fm username") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val uri = viewModel.myCardUri()
                                if (uri == null) {
                                    snackbarHostState.showSnackbar("Set your setlist.fm username first.")
                                    return@launch
                                }
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(
                                        Intent.EXTRA_TEXT,
                                        "Add me on Station to Station: $uri",
                                    )
                                }
                                context.startActivity(Intent.createChooser(send, "Share your friend card"))
                            }
                        },
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share your card")
                    }
                }
            }
            HorizontalDivider()

            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = addQuery,
                    onValueChange = { addQuery = it },
                    label = { Text("Add friend by setlist.fm username") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.addFriendByUsername(addQuery)
                        addQuery = ""
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Add") }
            }

            Row(
                Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = linkQuery,
                    onValueChange = { linkQuery = it },
                    label = { Text("Add from a shared Spotify playlist link") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.discoverFriendFromPlaylist(linkQuery)
                        linkQuery = ""
                    },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text("Find") }
            }

            if (state.friends.isEmpty()) {
                Text(
                    "No friends yet. Share your card, open a friend's card link, or add one " +
                        "by username above. Tap a friend to see concerts you both attended.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.friends, key = { it.setlistfm }) { friend ->
                    ListItem(
                        headlineContent = { Text(friend.name) },
                        supportingContent = { Text("@${friend.setlistfm}") },
                        trailingContent = {
                            IconButton(onClick = { viewModel.removeFriend(friend) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Remove ${friend.name}",
                                )
                            }
                        },
                        modifier = Modifier.clickable {
                            viewModel.openSharedConcerts(friend)
                            onOpenShared()
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
