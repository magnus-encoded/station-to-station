import Foundation

enum GigDeletionOutcome: Equatable { case deleted, kept }

/// What every place a **Gig** is stored can do: this phone.
protocol GigStorage {
    func delete(_ gigId: String) async -> GigDeletionOutcome
}
