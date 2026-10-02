package io.github.magnusencoded.stationtostation.data

enum class GigDeletionOutcome { DELETED, KEPT }

/** What every place a **Gig** is stored can do: this phone. */
interface GigStorage {
    suspend fun delete(gigId: String): GigDeletionOutcome
}
