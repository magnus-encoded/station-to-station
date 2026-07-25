package io.github.magnusencoded.setlist2spotify.data.photos

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId

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
    suspend fun photosFrom(date: LocalDate, limit: Int = 30): List<GalleryPhoto> =
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

    suspend fun thumbnail(uri: Uri, sizePx: Int = THUMBNAIL_PX): Bitmap? =
        withContext(Dispatchers.IO) { runCatching { decodeScaled(uri, sizePx) }.getOrNull() }

    /**
     * The photo as Spotify wants a cover: a square JPEG small enough that its
     * base64 form stays inside the 256 KB the upload endpoint accepts. Base64
     * costs a third on top, so the JPEG itself is held well under that.
     */
    suspend fun coverJpeg(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = decodeScaled(uri, COVER_PX) ?: return@runCatching null
            val square = centerCrop(bitmap, COVER_PX)
            var quality = 90
            var bytes = square.toJpeg(quality)
            while (bytes.size > MAX_JPEG_BYTES && quality > 40) {
                quality -= 10
                bytes = square.toJpeg(quality)
            }
            bytes.takeIf { it.size <= MAX_JPEG_BYTES }
        }.getOrNull()
    }

    private fun decodeScaled(uri: Uri, target: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
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
        private const val THUMBNAIL_PX = 256
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
