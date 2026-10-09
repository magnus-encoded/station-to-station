package io.github.magnusencoded.stationtostation.features.tour
import io.github.magnusencoded.stationtostation.UiState
import io.github.magnusencoded.stationtostation.data.TimelineStore
import io.github.magnusencoded.stationtostation.features.planning.PlanningController
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TourPlanningRegressionTest {
    private inline fun <reified T> unused(): T {
        val type = Class.forName("sun.misc.Unsafe")
        val field = type.getDeclaredField("theUnsafe").apply { isAccessible = true }
        return type.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), T::class.java) as T
    }
    @Test fun pickedArtistIdentitySurvivesPlanning() = runBlocking {
        val dir = Files.createTempDirectory("tour-artist").toFile()
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        var state = UiState(tour = TourState(step = TourStep.S4, demoWorld = 1))
        val store = TimelineStore(java.io.File(dir, "timeline.json"))
        val controller = PlanningController({ state }, { state = it(state) }, store, unused(), unused(), scope, { throw it })
        try {
            controller.pickArtist(io.github.magnusencoded.stationtostation.data.musicbrainz.MbArtist(name = "Band", mbid = "artist-id"))
            controller.addGig("Band", "Room", "01-01-2099")
            while (scope.coroutineContext[Job]!!.children.any()) scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
            assertEquals("artist-id", state.plannedGigs.single().artist?.mbid)
            assertEquals("artist-id", store.load().planned().single().artist?.mbid)
        } finally { scope.cancel(); dir.deleteRecursively() }
    }
    @Test fun tourRefusesPastDatesAndMissingVenueBeforeCreatingAnything() = runBlocking {
        for ((date, venue) in listOf("01-01-2000" to "Room", "01-01-2099" to "")) {
            val dir = Files.createTempDirectory("tour-planning").toFile()
            val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
            var state = UiState(tour = TourState(step = TourStep.S4, demoWorld = 1))
            var additions = 0
            val controller = PlanningController({ state }, { state = it(state) },
                TimelineStore(java.io.File(dir, "timeline.json")), unused(), unused(), scope,
                { throw it }, { additions++ })
            try {
                controller.addGig("Band", venue, date)
                while (scope.coroutineContext[Job]!!.children.any()) scope.coroutineContext[Job]!!.children.toList().forEach { it.join() }
                assertTrue("Tour accepted $date / '$venue'", state.plannedGigs.isEmpty())
                assertEquals(0, additions)
                assertNotNull(state.error)
            } finally { scope.cancel(); dir.deleteRecursively() }
        }
    }
}
