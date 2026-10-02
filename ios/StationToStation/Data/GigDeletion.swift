import Foundation

extension UiState {
    /// Drops the **Gig** from screen state, including the cached attended list, or it returns when my **Line** is next read.
    /// The selection closes only for this **Gig**: a long-press delete must not close another
    /// **Gig**'s **Room**. One **Log** is held in memory, so it clears with the selection.
    mutating func deleteGig(_ gigId: String, mine me: String) {
        plannedGigs.removeAll { $0.id == gigId }
        timelineShows.removeAll { $0.id == gigId }
        showsByFriend[me] = (showsByFriend[me] ?? []).filter { $0.id != gigId }
        attendanceByGig[gigId] = nil
        mediaBySetlist[gigId] = nil
        playlistsBySetlist[gigId] = nil
        calendarEventByGig[gigId] = nil
        if selectedSetlist?.id == gigId {
            selectedSetlist = nil
            gigLog = StoredLog()
        }
    }
}

/// The storage decides first. The screen follows only a `.deleted`; a `.kept` changes nothing.
@MainActor
func deleteFromStorage(
    _ gigId: String,
    storage: some GigStorage,
    thenOnScreen deleteOnScreen: () -> Void
) async -> GigDeletionOutcome {
    let outcome = await storage.delete(gigId)
    if outcome == .deleted { deleteOnScreen() }
    return outcome
}
