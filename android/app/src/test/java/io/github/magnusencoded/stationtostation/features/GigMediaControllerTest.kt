package io.github.magnusencoded.stationtostation.features

import io.github.magnusencoded.stationtostation.NOT_STAMPED
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.Band
import io.github.magnusencoded.stationtostation.data.StoredMedia
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.data.photos.PhotoRepository
import io.github.magnusencoded.stationtostation.features.gig.GigMediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class GigMediaControllerTest {

    private fun clip(id: String, personal: Boolean = true, offsets: List<Long> = emptyList()) =
        StoredMedia(id = id, kind = StoredMedia.Kind.VIDEO, ref = "ref-$id", personal = personal, songOffsets = offsets)

    private val store = TimelineStore(File.createTempFile("timelines", ".json").also { it.delete() })

    // The methods under test never touch it; a Context is unavailable off-device.
    private val photos: PhotoRepository = run {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe")
            .also { it.isAccessible = true }.get(null) as sun.misc.Unsafe
        unsafe.allocateInstance(PhotoRepository::class.java) as PhotoRepository
    }

    // A cancelled scope: persistence launches never run, the state change is what is asserted.
    private fun controller(fake: FakeState) = GigMediaController(
        fake.state, fake.update, store, photos, CoroutineScope(Job().apply { cancel() } + Dispatchers.Unconfined),
    )

    private fun withMedia(vararg media: StoredMedia) =
        FakeState(UiState(mediaBySetlist = mapOf("s1" to media.toList())))

    @Test
    fun `song offsets are padded with NOT_STAMPED to the setlist length`() {
        val fake = withMedia(clip("a", offsets = listOf(5L)))
        assertEquals(listOf(5L, NOT_STAMPED, NOT_STAMPED), controller(fake).songOffsets("a", 3))
    }

    @Test
    fun `song offsets are trimmed when the setlist shrinks`() {
        val fake = withMedia(clip("a", offsets = listOf(1L, 2L, 3L)))
        assertEquals(listOf(1L, 2L), controller(fake).songOffsets("a", 2))
    }

    @Test
    fun `an unknown recording has no stamps`() {
        assertEquals(listOf(NOT_STAMPED, NOT_STAMPED), controller(FakeState()).songOffsets("missing", 2))
    }

    @Test
    fun `stamping one song leaves its neighbours alone`() {
        val fake = withMedia(clip("a", offsets = listOf(10L, 20L, 30L)))
        controller(fake).stampSong("a", 1, 99L, 3)
        assertEquals(listOf(10L, 99L, 30L), fake.current.mediaBySetlist["s1"]!!.single().songOffsets)
    }

    @Test
    fun `a stamp outside the setlist is ignored`() {
        val fake = withMedia(clip("a", offsets = listOf(10L)))
        controller(fake).stampSong("a", 5, 99L, 1)
        assertEquals(listOf(10L), fake.current.mediaBySetlist["s1"]!!.single().songOffsets)
    }

    @Test
    fun `dragging a personal clip into the shared band clears its Personal bit`() {
        val fake = withMedia(clip("a"), clip("b"))
        controller(fake).moveGigMedia("s1", "a", Band.SHARED, 0)
        val media = fake.current.mediaBySetlist["s1"]!!
        assertFalse(media.first { it.id == "a" }.personal)
        assertEquals(setOf("a", "b"), media.map { it.id }.toSet())
    }
}
