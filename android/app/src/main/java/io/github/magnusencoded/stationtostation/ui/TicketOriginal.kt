package io.github.magnusencoded.stationtostation.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.github.magnusencoded.stationtostation.data.boundedPageSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/*
 * The kept original at the door (#568): the ticket file itself, shown in place of a
 * redraw the app could not make. The venue issued that page, so it scans as issued.
 * The Swift twin is the original in `TicketAtTheDoor.swift`, over PDFKit.
 */

/** Pixels across a page is drawn at: sharp at full screen on any phone, small enough to hold. */
private const val ORIGINAL_WIDTH_PX = 1600

/**
 * [page] of [file], drawn white-backed at [widthPx] across, or null where it cannot be
 * (a file gone, or not a PDF or image). A page past the end is the last page. Blocking:
 * call it off the main thread.
 */
internal fun renderOriginal(file: File, page: Int, widthPx: Int = ORIGINAL_WIDTH_PX): Bitmap? = try {
    if (file.extension.equals("pdf", ignoreCase = true)) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (pdf.pageCount == 0) return null
                pdf.openPage(page.coerceIn(0, pdf.pageCount - 1)).use { p ->
                    // Bounded (#165): a page one point wide would otherwise ask for a
                    // bitmap millions of pixels tall at this fixed width.
                    val (width, height) = boundedPageSize(p.width, p.height, widthPx.toFloat() / p.width.coerceAtLeast(1))
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(AndroidColor.WHITE)
                        p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }
    } else {
        BitmapFactory.decodeFile(file.path)
    }
} catch (e: Exception) {
    null
}

/**
 * The Room's card for an Admission shown from its original: the page, small, and a tap
 * away from full screen at full brightness. [label] is the "Barcode 2 of 3" the Room
 * already says, or null for one.
 */
@Composable
internal fun OriginalAtTheDoor(
    file: File,
    page: Int,
    label: String?,
    border: Color,
    caption: Color,
    unrendered: @Composable () -> Unit,
) {
    // Null while drawing; a failed draw (a damaged, cut-off or locked PDF) says so.
    val bitmap by produceState<Result<Bitmap?>?>(null, file, page) {
        value = Result.success(withContext(Dispatchers.IO) { renderOriginal(file, page) })
    }
    var full by remember(file, page) { mutableStateOf(false) }
    val drawn = bitmap ?: return
    val shown = drawn.getOrNull() ?: return unrendered()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .border(1.dp, border, RoundedCornerShape(14.dp))
                .clickable { full = true }
                .padding(6.dp),
        ) {
            Image(
                shown.asImageBitmap(),
                contentDescription = "Your original ticket${label?.let { ", $it" }.orEmpty()}. Tap to show it full screen.",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            )
        }
        Text(
            "Your original ticket. Tap to show it full screen.",
            color = caption,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (full) OriginalFullScreen(shown, label) { full = false }
}

/** The page on white, filling the screen, at full brightness until it is closed. */
@Composable
private fun OriginalFullScreen(bitmap: Bitmap, label: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            if (window != null) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                }
            }
            onDispose { }
        }
        Box(
            Modifier.fillMaxSize().background(Color.White).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Your original ticket${label?.let { ", $it" }.orEmpty()}. Hold it up to be scanned; tap to close.",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp),
            )
        }
    }
}
