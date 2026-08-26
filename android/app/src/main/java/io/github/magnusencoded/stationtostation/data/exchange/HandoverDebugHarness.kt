package io.github.magnusencoded.stationtostation.data.exchange

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.wifi.WifiManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLSocket

/**
 * The manual, two-device capture rig #142's own Testing Decisions call for: "the
 * encryption claim is verified empirically, not by unit test." This is not app UI —
 * it exists only to give a packet capture something real to point at, run twice: once
 * with [insecure] `= true` as the positive control ("we can read our own traffic"),
 * once `= false` as the claim under test ("now we cannot"), same rig both times.
 *
 * Triggered only by an explicit intent in a debug build (`MainActivity`'s handling of
 * `ACTION_HANDOVER_DEBUG`) — never reachable from any screen, so it cannot ship or be
 * tapped by accident. Not unit-tested: it is real device sockets and `AndroidKeyStore`,
 * the same reasons [generateHandoverIdentity] is not.
 *
 * Each run sends one synthetic "photo" ([syntheticTestPhoto] — generated on the device,
 * never a real one, because nothing personal may end up in a capture at all, armed or
 * not) and one line of text shaped like a **Log** entry, so a single run gives the
 * capture both of the issue's reconstruction targets.
 */

private const val DEBUG_PORT = 8942

fun syntheticTestPhoto(): ByteArray {
    val bmp = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(Color.rgb(24, 20, 40))
    val paint = Paint().apply {
        color = Color.WHITE
        textSize = 22f
        isAntiAlias = true
    }
    val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    canvas.drawText("station-to-station #142 capture test", 16f, 100f, paint)
    canvas.drawText(stamp, 16f, 130f, paint)
    return ByteArrayOutputStream().use { out ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
}

private fun testLogLine(): ByteArray =
    "Paper Cranes at the Old Church, 12 Jun — worth the queue.".toByteArray(Charsets.UTF_8)

private fun localWifiAddress(context: Context): String {
    val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val ip = wifi?.connectionInfo?.ipAddress ?: return "unknown (not on wifi?)"
    return "%d.%d.%d.%d".format(ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
}

private fun outDir(context: Context): File =
    (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }

/** Runs the host side once: accept one connection, authenticate it (secure mode only),
 * send the two test artifacts, then stop. Blocking — call from a background thread. */
fun runHandoverDebugHost(context: Context, linkKeyHex: String, insecure: Boolean, log: (String) -> Unit) {
    val linkKey = decodeHex(linkKeyHex)
    log("hosting on ${localWifiAddress(context)}:$DEBUG_PORT — insecure=$insecure")

    val serverSocket: ServerSocket
    if (insecure) {
        serverSocket = ServerSocket(DEBUG_PORT)
    } else {
        val (cert, keyStore) = generateHandoverIdentity(sessionId = "debug")
        val fingerprint = encodeHex(certFingerprint(cert))
        log("fingerprint (pass to the join side with --fingerprint): $fingerprint")
        serverSocket = sslServerContext(keyStore, CharArray(0), handoverAlias("debug"))
            .serverSocketFactory.createServerSocket(DEBUG_PORT)
    }

    serverSocket.use { ss ->
        log("waiting for a connection...")
        val socket = ss.accept()
        socket.use {
            if (socket is SSLSocket) socket.startHandshake()
            if (!insecure) {
                val ok = verifyLinkKey(socket, linkKey)
                log("link key verified: $ok")
                if (!ok) return@use
            }
            writeFrame(socket.getOutputStream(), testLogLine())
            val photo = syntheticTestPhoto()
            writeItem(socket, id = "debug-photo", length = photo.size.toLong(), body = photo.inputStream())
            writeEndOfItems(socket)
            log("sent test log line and a ${photo.size}-byte test photo")
        }
    }
    if (!insecure) forgetHandoverIdentity(sessionId = "debug")
    log("host session done")
}

/** Runs the join side once: connect, authenticate, receive the two test artifacts, and
 * write them to external files where `adb pull` can retrieve them for reconstruction. */
fun runHandoverDebugJoin(
    context: Context,
    hostIp: String,
    linkKeyHex: String,
    fingerprintHex: String?,
    insecure: Boolean,
    log: (String) -> Unit,
) {
    val linkKey = decodeHex(linkKeyHex)
    log("connecting to $hostIp:$DEBUG_PORT — insecure=$insecure")

    val socket: Socket = if (insecure) {
        Socket(hostIp, DEBUG_PORT)
    } else {
        val fingerprint = fingerprintHex?.let(::decodeHex)
            ?: throw IllegalArgumentException("secure mode needs --fingerprint from the host's log")
        (sslClientContext(fingerprint).socketFactory.createSocket(hostIp, DEBUG_PORT) as SSLSocket)
            .also { it.startHandshake() }
    }

    socket.use {
        if (!insecure) proveLinkKey(socket, linkKey)

        val logFrame = readFrame(socket.getInputStream())
        log("received log line: ${logFrame?.toString(Charsets.UTF_8)}")

        val (id, length) = readItemHeader(socket) ?: run { log("no item arrived"); return }
        val out = File(outDir(context), "handover-debug-$id.png")
        FileOutputStream(out).use { fos -> copyExactly(socket.getInputStream(), fos, length) }
        log("received $length-byte item '$id', written to ${out.absolutePath}")
        readItemHeader(socket) // drains the end-of-items marker
    }
    log("join session done")
}

/** The invite's own hex, shared with [HandoverInvite] rather than written twice. */
private fun decodeHex(hex: String): ByteArray =
    requireNotNull(decodeHexOrNull(hex)) { "not hex: '$hex'" }
