import Foundation

/// The **Demo world**'s **Gig**: the one added at S4, remembered across launches so a
/// skip or the final purge can take it back.
@MainActor
final class TourAddGigEffects: DemoWorld {
    private static let key = "tour.demoGigIds"
    private let store: UserDefaults
    private let deleteGig: (String) -> Void

    init(store: UserDefaults = .standard, deleteGig: @escaping (String) -> Void) {
        self.store = store
        self.deleteGig = deleteGig
    }

    var demoGigIds: [String] { store.stringArray(forKey: Self.key) ?? [] }

    func record(_ gigId: String) {
        guard !demoGigIds.contains(gigId) else { return }
        store.set(demoGigIds + [gigId], forKey: Self.key)
    }

    /// `lookUpBand` is the add sheet's own artist completion, which already asks
    /// MusicBrainz as the person types; the Tour adds no second lookup.
    func lookUpBand() {}

    func purge() {
        demoGigIds.forEach(deleteGig)
        store.removeObject(forKey: Self.key)
    }
}

/// Every record kind the Tour creates, each purged by the step that made it.
struct DemoWorldRegistry: DemoWorld {
    let parts: [DemoWorld]

    func purge() { parts.forEach { $0.purge() } }
}
