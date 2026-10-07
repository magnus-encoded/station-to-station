package io.github.magnusencoded.stationtostation.features.tour

import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineCache
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.bandsOf
import io.github.magnusencoded.stationtostation.features.gig.AttachClaim
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The selfie (S18): the person's own attach on the demo **Gig**, and the **Virtual friend**'s
 * selfie, which arrives as **Received media** from the demo **Contact**.
 *
 * The purge removes the app's records, thumbnails and copies only. The person's photo stays
 * in their library: a gallery reference owns no bytes here. [offerable] keeps the demo
 * **Gig**'s media from a real **Contact**.
 */
class TourSelfieEffects(
    private val timelines: TimelineStore,
    private val friendKey: suspend () -> String?,
    private val selfieBytes: suspend () -> ByteArray?,
    private val storeSelfie: suspend (id: String, bytes: ByteArray) -> String?,
    private val discard: suspend (StoredMedia) -> Unit,
    private val update: ((UiState) -> UiState) -> Unit,
) : DemoWorld {
    // Writers and the purge take turns: what lands before the purge is swept by it, and what
    // asks after it finds the Demo world gone.
    private val writes = Mutex()

    /** A claim on one attach, refused once [active] is false: a skip can finish during the copy. */
    fun claim(active: () -> Boolean, added: (shared: Boolean) -> Unit): AttachClaim = AttachClaim { _, band, save ->
        writes.withLock {
            if (!active()) return@withLock false
            save()
            added(band == Band.SHARED)
            true
        }
    }

    suspend fun deliverFriendSelfie(gigId: String, now: Long, active: () -> Boolean) = writes.withLock {
        if (!active()) return
        val key = friendKey()?.takeIf { it.isNotBlank() } ?: return
        val held = timelines.load().media()[gigId].orEmpty()
        if (held.any { it.from == key }) return
        val bytes = selfieBytes() ?: return
        val id = UUID.randomUUID().toString()
        val ref = storeSelfie(id, bytes) ?: return
        val item = StoredMedia(id = id, kind = StoredMedia.Kind.PHOTO, ref = ref, capturedAt = now, from = key, personal = false)
        val media = bandsOf(held + item).let { it.shared + it.received + it.vault }
        timelines.saveMedia(gigId, media)
        update { it.copy(mediaBySetlist = it.mediaBySetlist + (gigId to media)) }
    }

    override suspend fun purge() = writes.withLock {
        val cache = timelines.load()
        val keys = demoKeys(cache)
        keys.flatMap { cache.gigMedia[it].orEmpty() }.distinctBy { it.id }.forEach { discard(it) }
        update { it.copy(mediaBySetlist = it.mediaBySetlist - keys) }
    }

    companion object {
        private fun demoKeys(cache: TimelineCache): Set<String> =
            cache.gigs.values.filter { it.demo }.flatMapTo(mutableSetOf()) { listOfNotNull(it.id, it.setlistId) }

        /** [cache] as this phone may offer a **Contact**: no media of a demo **Gig**, in any **Band**. */
        fun offerable(cache: TimelineCache): TimelineCache {
            val demo = demoKeys(cache)
            return if (demo.isEmpty()) cache else cache.copy(gigMedia = cache.gigMedia - demo)
        }
    }
}
