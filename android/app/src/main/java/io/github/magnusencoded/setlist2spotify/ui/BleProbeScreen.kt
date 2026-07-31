package io.github.magnusencoded.setlist2spotify.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.magnusencoded.setlist2spotify.ble.BleAdvertiser
import io.github.magnusencoded.setlist2spotify.ble.BleScanner
import io.github.magnusencoded.setlist2spotify.ble.PeerHit
import java.text.SimpleDateFormat
import java.util.Locale

private val RUNTIME_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

// #18 field-test probe screen: advertise + scan raw BLE GATT between two phones
// and log discovery latency / RSSI / round-trip so the crowd-density questions
// in #18 have real numbers behind them, not just the desk research in #13.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    var label by remember { mutableStateOf(Build.MODEL ?: "phone") }
    var advertising by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val peers = remember { mutableStateListOf<PeerHit>() }
    var hasPermissions by remember { mutableStateOf(false) }

    fun appendLog(line: String) {
        log.add(0, "${timeFormat.format(System.currentTimeMillis())}  $line")
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        hasPermissions = result.values.all { it }
        if (!hasPermissions) appendLog("permissions denied: $result")
    }

    val advertiser = remember(label) { BleAdvertiser(context, label).apply { onLog = ::appendLog } }
    val scanner = remember {
        BleScanner(context).apply {
            onLog = ::appendLog
            onHit = { hit ->
                val i = peers.indexOfFirst { it.address == hit.address }
                if (i >= 0) peers[i] = hit else peers.add(hit)
            }
        }
    }

    DisposableEffect(advertising, advertiser) {
        if (advertising && hasPermissions) advertiser.start()
        onDispose { if (hasPermissions) advertiser.stop() }
    }
    DisposableEffect(scanning) {
        if (scanning && hasPermissions) scanner.start()
        onDispose { if (hasPermissions) scanner.stop() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE probe (#18)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "Empirical look at raw BLE GATT between two phones: discovery latency, " +
                    "RSSI, round-trip read. Foreground only on both ends.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("This phone's label") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (!hasPermissions) permissionLauncher.launch(RUNTIME_PERMISSIONS) else advertising = !advertising
                }) { Text(if (advertising) "Stop advertising" else "Start advertising") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (!hasPermissions) permissionLauncher.launch(RUNTIME_PERMISSIONS) else scanning = !scanning
                }) { Text(if (scanning) "Stop scanning" else "Start scanning") }
            }
            Spacer(Modifier.height(16.dp))
            Text("Peers seen (${peers.size})", style = MaterialTheme.typography.titleMedium)
            peers.forEach { peer ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${peer.address}  rssi=${peer.rssi}  discovered in ${peer.firstSeenLatencyMs}ms")
                    Button(onClick = {
                        scanner.readPeer(peer.address) { payload, roundTripMs ->
                            appendLog("read \"$payload\" from ${peer.address} in ${roundTripMs}ms")
                        }
                    }) { Text("Read") }
                }
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)
            LazyColumn {
                items(log) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
