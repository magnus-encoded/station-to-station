package io.github.magnusencoded.stationtostation.features.tour

/** Each feature removes only the Demo world records it owns. */
fun interface DemoWorld {
    suspend fun purge()
}

/** Purge external handles before the records needed to identify them. */
class DemoWorldRegistry(private val parts: List<DemoWorld>) : DemoWorld {
    override suspend fun purge() {
        parts.forEach { it.purge() }
    }
}
