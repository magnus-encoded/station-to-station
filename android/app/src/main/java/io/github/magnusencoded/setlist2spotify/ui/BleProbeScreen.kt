package io.github.magnusencoded.setlist2spotify.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import io.github.magnusencoded.setlist2spotify.ble.BleCardCentral
import io.github.magnusencoded.setlist2spotify.ble.BleCardPeripheral
import io.github.magnusencoded.setlist2spotify.ble.EXCHANGE_TIMEOUT_MS
import io.github.magnusencoded.setlist2spotify.ble.ExchangeTiming
import io.github.magnusencoded.setlist2spotify.ble.NearbyNameLimitProbe
import io.github.magnusencoded.setlist2spotify.ble.PeerHit
import io.github.magnusencoded.setlist2spotify.ble.ProbeCard
import io.github.magnusencoded.setlist2spotify.ble.SCAN_RESPONSE_NAME_BUDGET
import io.github.magnusencoded.setlist2spotify.data.nearby.NearbyPeers
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Locale
import kotlin.random.Random

// #30 probe screen: two Androids exchange a card over raw GATT and the phone
// reports where the time went. Throwaway measurement UI in the shape of the old
// #18 probe it replaces — nothing here is wired into the real connect screen.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleProbeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    // ponytail: a throwaway key, not the keystore identity #28 describes. The probe
    // measures bytes on the wire; whose bytes they are does not change the timing.
    val publicKey = remember { Base64.getEncoder().encodeToString(Random.nextBytes(32)) }
    var name by remember { mutableStateOf(Build.MODEL ?: "phone") }
    var setlistfm by remember { mutableStateOf("dizzi90") }
    val card = ProbeCard(name = name, publicKey = publicKey, setlistfm = setlistfm)

    var advertising by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }
    val peers = remember { mutableStateListOf<PeerHit>() }
    val runs = remember { mutableStateListOf<ExchangeTiming>() }
    var hasPermissions by remember { mutableStateOf(false) }
    val cardSize = card.bytes().size
    val nameProbe = remember { NearbyNameLimitProbe(context) }
    // Starts below the 131-byte ceiling so the first tap is a control that works.
    var nameProbeLength by remember { mutableStateOf(100) }

    fun appendLog(line: String) {
        log.add(0, "${timeFormat.format(System.currentTimeMillis())}  $line")
    }

    // Same permission set Nearby needs, so the one grant covers both halves of
    // the probe rather than two prompts that can disagree.
    val requiredPermissions = remember { NearbyPeers(context).requiredPermissions().toTypedArray() }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        hasPermissions = result.values.all { it }
        if (!hasPermissions) appendLog("permissions denied: $result")
    }

    val peripheral = remember(card) { BleCardPeripheral(context, card).apply { onLog = ::appendLog } }
    val central = remember {
        BleCardCentral(context).apply {
            onLog = ::appendLog
            onHit = { hit ->
                val i = peers.indexOfFirst { it.address == hit.address }
                if (i >= 0) peers[i] = hit else peers.add(hit)
            }
        }
    }

    DisposableEffect(advertising, peripheral) {
        if (advertising && hasPermissions) peripheral.start()
        onDispose { if (hasPermissions) peripheral.stop() }
    }
    DisposableEffect(scanning) {
        if (scanning && hasPermissions) central.start()
        onDispose { if (hasPermissions) central.stop() }
    }
    DisposableEffect(Unit) { onDispose { nameProbe.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GATT card probe (#30)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(
                "Advert = service UUID. Scan response = name ($SCAN_RESPONSE_NAME_BUDGET bytes). " +
                    "Card = characteristic read after an MTU bump. Budget: 2s ships, 6s fails; " +
                    "gives up at ${EXCHANGE_TIMEOUT_MS}ms and that is where QR takes over.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Display name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = setlistfm,
                onValueChange = { setlistfm = it },
                label = { Text("setlist.fm username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "This card is $cardSize bytes on the wire.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    if (!hasPermissions) permissionLauncher.launch(requiredPermissions) else advertising = !advertising
                }) { Text(if (advertising) "Stop advertising" else "Advertise") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (!hasPermissions) permissionLauncher.launch(requiredPermissions) else scanning = !scanning
                }) { Text(if (scanning) "Stop scanning" else "Scan") }
            }
            Spacer(Modifier.height(16.dp))

            Text("Nearby (${peers.size})", style = MaterialTheme.typography.titleMedium)
            peers.forEach { peer ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${peer.name ?: "(no name in scan response)"} · ${peer.address}")
                    Text(
                        "rssi ${peer.rssi} · discovered in ${peer.discoveryMs}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = {
                        appendLog("Connecting with ${peer.name ?: peer.address}…")
                        central.readCard(peer) { timing ->
                            runs.add(0, timing)
                            appendLog("exchange: ${timing.verdict}")
                        }
                    }) { Text("Get card") }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Exchanges (${runs.size})", style = MaterialTheme.typography.titleMedium)
            runs.forEach { run ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(run.verdict, style = MaterialTheme.typography.titleSmall)
                    Text(
                        "discovery ${run.discoveryMs}ms · connect ${run.connectMs ?: "-"}ms · " +
                            "mtu ${run.mtuMs ?: "-"}ms (${run.mtu ?: "-"}) · " +
                            "services ${run.servicesMs ?: "-"}ms · read ${run.readMs ?: "-"}ms",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    run.card?.let {
                        Text(
                            "card: ${it.name} · key ${it.publicKey.take(8)}… · ${run.cardBytes} bytes",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Nearby endpoint-name ceiling", style = MaterialTheme.typography.titleMedium)
            Text(
                "$cardSize of ${NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT} bytes used — " +
                    if (cardSize > NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT) {
                        "OVER. Nearby truncates silently, so the Android fast path needs connect-and-read too."
                    } else {
                        "fits, with ${NearbyNameLimitProbe.NEARBY_ENDPOINT_NAME_LIMIT - cardSize} bytes spare."
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Overflow raises no error: startAdvertising succeeds and the peer gets a chopped " +
                    "name. Confirm the ceiling with two phones — advertise on one, watch on the other.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = {
                    if (!hasPermissions) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        nameProbe.stop()
                        nameProbe.advertiseLength(nameProbeLength) { appendLog("Nearby: $it") }
                        // Each tap goes one step longer, so the pair walks past the
                        // ceiling and the watching phone shows where it stopped growing.
                        nameProbeLength += 20
                    }
                }) { Text("Advertise ${nameProbeLength}B name") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    if (!hasPermissions) {
                        permissionLauncher.launch(requiredPermissions)
                    } else {
                        nameProbe.watch(
                            onSeen = { claimed, received, seen ->
                                val verdict = if (claimed != null && claimed != received) " TRUNCATED" else ""
                                appendLog(
                                    "Nearby name: claimed ${claimed ?: "?"}B, received ${received}B$verdict " +
                                        "\"${seen.take(24)}…\"",
                                )
                            },
                            onFailure = ::appendLog,
                        )
                        appendLog("Nearby: watching endpoint names")
                    }
                }) { Text("Watch names") }
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            Text("Log", style = MaterialTheme.typography.titleMedium)
            // Plain list, not a LazyColumn: the page itself scrolls, and nesting the
            // two throws "infinite height".
            log.take(40).forEach { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
