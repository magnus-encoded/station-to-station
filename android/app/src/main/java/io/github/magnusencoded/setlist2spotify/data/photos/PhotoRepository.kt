package io.github.magnusencoded.setlist2spotify.data.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** A gallery image taken around the time of a concert. */
data class GalleryPhoto(val uri: Uri, val takenAtMillis: Long)

/**
 * Finds photos the phone took during a show, so a setlist playlist can be
 * covered with a picture from the night it happened.
 */
class PhotoRepository(private val context: Context) {

    fun hasPermission(): Boolean = requiredPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Photos taken on [date], plus the small hours after it: a set that starts
     * at 23:00 is photographed on the following calendar day, and setlist.fm
     * dates the show by when it started.
     */
    suspend fun photosFrom(date: LocalDate, limit: Int = 20): List<GalleryPhoto> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val zone = ZoneId.systemDefault()
            val from = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val to = date.plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()

            // DATE_TAKEN comes from EXIF in milliseconds and is null on images
            // that carry no timestamp; DATE_ADDED is always set, in seconds, and
            // covers those.
            val selection = "(${MediaStore.Images.Media.DATE_TAKEN} BETWEEN ? AND ?) OR " +
                "(${MediaStore.Images.Media.DATE_TAKEN} IS NULL AND " +
                "${MediaStore.Images.Media.DATE_ADDED} BETWEEN ? AND ?)"
            val args = arrayOf(
                from.toString(),
                to.toString(),
                (from / 1000).toString(),
                (to / 1000).toString(),
            )

            val photos = mutableListOf<GalleryPhoto>()
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                ),
                selection,
                args,
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val takenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext() && photos.size < limit) {
                    val takenAt = if (cursor.isNull(takenColumn)) {
                        cursor.getLong(addedColumn) * 1000
                    } else {
                        cursor.getLong(takenColumn)
                    }
                    photos += GalleryPhoto(
                        ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            cursor.getLong(idColumn),
                        ),
                        takenAt,
                    )
                }
            }
            photos
        }

    /**
     * Copies a system photo-picker pick into the app's own storage. The picker only
     * grants read access for the process that received it — gone the moment the app
     * is killed and relaunched, which is what left keepsakes blank. A durable copy,
     * re-exposed through our own FileProvider, survives that.
     */
    suspend fun persistCopy(uri: Uri): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val mime = context.contentResolver.getType(uri)
            val ext = mime?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) } ?: "jpg"
            val dir = File(context.filesDir, "gig_photos").apply { mkdirs() }
            val file = File(dir, "${UUID.randomUUID()}.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }

    /** The gig-photo picker also takes video, so a preview may come from either. */
    fun isVideo(uri: Uri): Boolean = context.contentResolver.getType(uri)?.startsWith("video/") == true

    /**
     * When the camera took [uri], for the record #97 keeps — not when it was
     * attached, which is admin rather than history.
     *
     * DATE_TAKEN comes from EXIF and is absent on anything without it; DATE_ADDED
     * (seconds, not millis) is the fallback and is at least the right order of
     * magnitude for a night. Null when the row answers neither, because a wrong
     * timestamp on a keepsake is worse than an honest gap. Must be read from the
     * *picked* uri: a copy the app made has no MediaStore row at all.
     */
    fun capturedAtMs(uri: Uri): Long? = runCatching {
        val columns = arrayOf(MediaStore.MediaColumns.DATE_TAKEN, MediaStore.MediaColumns.DATE_ADDED)
        context.contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val taken = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_TAKEN)
                .takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) }
            val added = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                .takeIf { it >= 0 && !cursor.isNull(it) }?.let { cursor.getLong(it) * 1000 }
            (taken ?: added)?.takeIf { it > 0 }
        }
    }.getOrNull()

    /**
     * A preview big enough to fill the cover-sized pager. Held in RGB_565: at
     * twenty photos the difference against ARGB_8888 is tens of megabytes, and
     * the uploaded cover is re-decoded from the original at full depth anyway.
     */
    suspend fun preview(uri: Uri, sizePx: Int = PREVIEW_PX): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                if (isVideo(uri)) videoFrame(uri, sizePx)
                else decodeScaled(uri, sizePx, Bitmap.Config.RGB_565)?.let { upright(uri, it) }
            }.getOrNull()
        }

    /** How long the clip runs, so a scrubber knows what it is scrubbing across. */
    suspend fun videoDurationMs(uri: Uri): Long = withContext(Dispatchers.IO) {
        runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            } finally {
                retriever.release()
            }
        }.getOrDefault(0L)
    }

    /** The frame at [atMs], for scrubbing a clip to the picture worth keeping. */
    suspend fun videoFrameAt(uri: Uri, atMs: Long, sizePx: Int = PREVIEW_PX): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { videoFrame(uri, sizePx, atMs) }.getOrNull() }

    /**
     * A frame of the clip, standing in for the video the same way a decoded bitmap
     * stands in for a photo. OPTION_CLOSEST rather than CLOSEST_SYNC once a time is
     * asked for: sync frames can sit seconds apart, so snapping to them would make a
     * scrubber feel stuck.
     */
    private fun videoFrame(uri: Uri, sizePx: Int, atMs: Long = 0L): Bitmap? {
        val retriever = MediaMetadataRetriever()
        val option = if (atMs > 0L) MediaMetadataRetriever.OPTION_CLOSEST
        else MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        return try {
            retriever.setDataSource(context, uri)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(atMs * 1000, option, sizePx, sizePx)
            } else {
                retriever.getFrameAtTime(atMs * 1000, option)
            }
        } finally {
            retriever.release()
        }
    }

    /**
     * The photo as Spotify wants a cover: a square JPEG small enough that its
     * base64 form stays inside the 256 KB the upload endpoint accepts. Base64
     * costs a third on top, so the JPEG itself is held well under that.
     */
    suspend fun coverJpeg(uri: Uri, frameMs: Long = 0L): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            // A video has no image to decode, so the cover comes from a frame of it —
            // whichever one the scrubber landed on. It arrives already upright.
            val decoded = if (isVideo(uri)) {
                videoFrame(uri, COVER_PX, frameMs) ?: return@runCatching null
            } else {
                upright(uri, decodeScaled(uri, COVER_PX) ?: return@runCatching null)
            }
            val square = centerCrop(decoded, COVER_PX)
            var quality = 90
            var bytes = square.toJpeg(quality)
            while (bytes.size > MAX_JPEG_BYTES && quality > 40) {
                quality -= 10
                bytes = square.toJpeg(quality)
            }
            bytes.takeIf { it.size <= MAX_JPEG_BYTES }
        }.getOrNull()
    }

    private fun decodeScaled(uri: Uri, target: Int, config: Bitmap.Config? = null): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
            config?.let { inPreferredConfig = it }
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * Cameras leave the pixels as the sensor read them and record the turn of
     * the phone in EXIF, so a portrait photo decodes on its side unless the
     * recorded rotation is applied.
     */
    private fun upright(uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** Largest power-of-two shrink that still leaves the short edge at [target]. */
    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (minOf(width, height) / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun centerCrop(source: Bitmap, size: Int): Bitmap {
        val edge = minOf(source.width, source.height)
        val square = Bitmap.createBitmap(
            source,
            (source.width - edge) / 2,
            (source.height - edge) / 2,
            edge,
            edge,
        )
        return if (edge <= size) square else Bitmap.createScaledBitmap(square, size, size, true)
    }

    companion object {
        private const val PREVIEW_PX = 512
        private const val COVER_PX = 640
        private const val MAX_JPEG_BYTES = 180_000

        /**
         * Android 13 split image reads out of the storage permission, and 14
         * added a "selected photos only" grant that reads as a separate
         * permission but still returns those photos through MediaStore.
         */
        fun requiredPermissions(): Array<String> = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

private fun Bitmap.toJpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
