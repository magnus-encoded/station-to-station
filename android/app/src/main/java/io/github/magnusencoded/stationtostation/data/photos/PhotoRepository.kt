package io.github.magnusencoded.stationtostation.data.photos

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
import io.github.magnusencoded.stationtostation.data.StoredMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
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
     * Photos taken on [date], plus the small hours after it. The window itself is
     * [photoWindow]'s — shared logic (ADR-0001), asserted by the same cases on
     * both platforms; this is only the query that runs it.
     */
    suspend fun photosFrom(date: LocalDate, limit: Int = 20): List<GalleryPhoto> =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext emptyList()
            val window = photoWindow(date)
            val from = window.first
            val to = window.last

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
                    val takenAt = capturedAtMs(
                        taken = if (cursor.isNull(takenColumn)) null else cursor.getLong(takenColumn),
                        added = if (cursor.isNull(addedColumn)) null else cursor.getLong(addedColumn) * 1000,
                    ) ?: continue
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
            capturedAtMs(taken, added)
        }
    }.getOrNull()

    /**
     * The content hash [io.github.magnusencoded.stationtostation.data.OfferedMedia.hash] and
     * [io.github.magnusencoded.stationtostation.data.GalleryItem.hash] compare (#257) — same
     * bytes, same hash, is all either field's contract asks for.
     *
     * Whole-file SHA-256 for a photo. For a video, only the first 64 KiB plus the byte
     * count, not a head+tail sample: reading 233 MB to decide whether to send 233 MB is
     * the wrong trade this exists to avoid, and a head-only sample is still enough entropy
     * to tell two different videos apart in practice. A deliberate simplification against
     * a literal head-and-tail scheme — safe only because "the sender's business" is the
     * actual contract here, not a specific algorithm.
     */
    suspend fun mediaHash(uri: Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            if (isVideo(uri)) {
                val size = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@runCatching null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val head = ByteArray(VIDEO_HASH_SAMPLE_BYTES)
                    var offset = 0
                    while (offset < head.size) {
                        val read = input.read(head, offset, head.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    digest.update(head, 0, offset)
                } ?: return@runCatching null
                digest.update(size.toString().toByteArray())
            } else {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(8192)
                    var read = input.read(buffer)
                    while (read >= 0) {
                        digest.update(buffer, 0, read)
                        read = input.read(buffer)
                    }
                } ?: return@runCatching null
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

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

    // --- Thumbnails: the durable floor (#98) ---------------------------------

    /** The grid tier's file for [mediaId]. Written once at **Attach**, never evicted. */
    fun gridThumbFile(mediaId: String): File =
        File(File(context.filesDir, Thumbnails.GRID_DIR), Thumbnails.fileName(mediaId))

    /** The full-screen tier's file for [mediaId]. A cache: absent is normal. */
    fun cacheThumbFile(mediaId: String): File =
        File(File(context.cacheDir, Thumbnails.CACHE_DIR), Thumbnails.fileName(mediaId))

    /**
     * Both derived copies of [uri], for the item that will be known as [mediaId].
     * True when the durable tier landed; false is a **failed attach**.
     *
     * Generated here — at **Attach** — and not lazily at first display, because the
     * source is guaranteed readable exactly once: the moment the user picks it.
     * Every way a keepsake breaks (#97) is that guarantee expiring later, so lazy
     * generation would mean the floor exists only for the media nobody lost. This
     * is the one moment the app can still get the bytes, which is why a failure is
     * loud rather than a record with nothing behind it.
     *
     * A video's grid tier is a poster frame, from the same path [preview] uses.
     */
    suspend fun generateThumbnails(mediaId: String, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                // One decode, at the larger of the two edges, then scaled down twice.
                // Decoding the source twice is the obvious alternative and costs a
                // full re-read of a file that may be arriving from iCloud.
                val source = if (isVideo(uri)) {
                    videoFrame(uri, Thumbnails.FULL_EDGE_PX)
                } else {
                    decodeScaled(uri, Thumbnails.FULL_EDGE_PX)?.let { upright(uri, it) }
                } ?: return@runCatching false

                // The cache tier first, so a failure to write it still leaves the
                // durable tier as the last thing that happened.
                writeTier(cacheThumbFile(mediaId), source, Thumbnails.FULL_EDGE_PX, Thumbnails.FULL_QUALITY)
                writeTier(gridThumbFile(mediaId), source, Thumbnails.GRID_EDGE_PX, Thumbnails.GRID_QUALITY)
            }.getOrDefault(false)
        }

    private fun writeTier(file: File, source: Bitmap, maxEdge: Int, quality: Int): Boolean {
        val (w, h) = thumbnailSize(source.width, source.height, maxEdge)
        if (w <= 0 || h <= 0) return false
        val scaled = if (w == source.width && h == source.height) source
        else Bitmap.createScaledBitmap(source, w, h, true)
        file.parentFile?.mkdirs()
        file.writeBytes(scaled.toJpeg(quality))
        return file.length() > 0
    }

    /** The durable copy, if it is there. Null means fall back to the source. */
    suspend fun gridThumbnail(mediaId: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = gridThumbFile(mediaId)
        if (!file.exists()) null else runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** The full-screen copy, if the cache still holds it. */
    suspend fun cachedFullThumbnail(mediaId: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = cacheThumbFile(mediaId)
        if (!file.exists()) null else runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    /** Removing means removing: the record goes, and so do the bytes it owned. */
    suspend fun deleteThumbnails(mediaId: String): Unit = withContext(Dispatchers.IO) {
        gridThumbFile(mediaId).delete()
        cacheThumbFile(mediaId).delete()
    }

    /**
     * Where a Contact's landed media (#257) is written to — the same tier and
     * FileProvider exposure [persistCopy] uses, so [ownsBytes]/[deleteOwnedBytes] treat
     * a received item identically to a picked photo, deletable the same way.
     */
    fun receivedMediaFile(mediaId: String, kind: String): File {
        val dir = File(context.filesDir, "gig_photos").apply { mkdirs() }
        val ext = if (kind == StoredMedia.Kind.VIDEO) "mp4" else "jpg"
        return File(dir, "$mediaId.$ext")
    }

    /** The FileProvider ref for a file [receivedMediaFile] or [persistCopy] wrote — what a
     * ref must be for [ownsBytes] to recognise it later. */
    fun fileProviderRef(file: File): String =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file).toString()

    /**
     * Length and an open stream for [ref], for #257's [ContactExchange] to send onward —
     * works the same for a MediaStore gallery ref and an app-owned FileProvider ref,
     * since both only ever go through [ContentResolver][android.content.ContentResolver],
     * never a raw file path.
     */
    fun mediaSource(ref: String): Pair<Long, java.io.InputStream>? = runCatching {
        val uri = Uri.parse(ref)
        val length = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return@runCatching null
        val stream = context.contentResolver.openInputStream(uri) ?: return@runCatching null
        length to stream
    }.getOrNull()

    /**
     * Whether this app holds the **only** copy of a photo — the one question a
     * delete has to ask before it is allowed to be quiet about itself.
     *
     * [persistCopy] lands a picked photo under our own FileProvider, and from then
     * on nothing outside the app points at those bytes. A ref still addressing
     * `content://media/…` is the system gallery's: our record is a pointer, and
     * dropping it leaves the photograph exactly where it was.
     */
    fun ownsBytes(ref: String): Boolean =
        ref.startsWith("content://${context.packageName}.fileprovider/")

    /**
     * The full-res copy this app owns, deleted along with its thumbnails. A no-op
     * for a ref that only points into the gallery — see [ownsBytes].
     */
    suspend fun deleteOwnedBytes(mediaId: String, ref: String): Unit = withContext(Dispatchers.IO) {
        if (ownsBytes(ref)) {
            Uri.parse(ref).lastPathSegment?.let { File(File(context.filesDir, "gig_photos"), it).delete() }
        }
        deleteThumbnails(mediaId)
    }

    /**
     * Gives back what the app can under storage pressure.
     *
     * **Eviction touches the cache tier only, ever.** Stated as an invariant rather
     * than a policy, because it is the one rule whose breach silently destroys the
     * product's core promise — and it is why the two tiers live in two directories
     * rather than one with a naming convention.
     */
    suspend fun evictThumbnailCache(): Unit = withContext(Dispatchers.IO) {
        File(context.cacheDir, Thumbnails.CACHE_DIR).deleteRecursively()
    }

    companion object {
        private const val PREVIEW_PX = 512
        private const val COVER_PX = 640
        private const val MAX_JPEG_BYTES = 180_000
        private const val VIDEO_HASH_SAMPLE_BYTES = 65_536

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
