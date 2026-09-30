import Foundation

enum GigMenuItem: Hashable { case openOnSetlistFm, delete }

/// Where a **Gig** on my **Line** is held.
enum GigStanding {
    /// Only this phone has it: typed in, planned, joined from a **Contact**.
    case hereOnly
    /// This phone holds its own record of it, and my setlist.fm attended list has it too.
    case hereAndSetlistFm
    /// Only my setlist.fm attended list has it, so a fresh import would bring it back.
    case setlistFmOnly

    var caption: String {
        switch self {
        case .hereOnly: return "Kept on this phone"
        case .hereAndSetlistFm: return "On this phone and on setlist.fm"
        case .setlistFmOnly: return "From your setlist.fm list"
        }
    }
}

/// A setlist.fm id does not decide it: a night attended here and linked to setlist.fm
/// afterwards is held here, and so is one that also turns up on my attended list.
func gigStanding(_ id: String, held: [String], attendedOnSetlistFm: [String]) -> GigStanding {
    let here = held.contains(id), there = attendedOnSetlistFm.contains(id)
    if here && there { return .hereAndSetlistFm }
    return there ? .setlistFmOnly : .hereOnly
}

/// What a long press on a **Gig** offers. Delete is there whenever this phone holds a record
/// of it, so a memory can always be let go. A night my setlist.fm attended list has is also
/// left from there, by marking it "I was not there", so that is offered too; alone, it is
/// the only way off it.
func gigMenu(_ standing: GigStanding, hasPage: Bool) -> [GigMenuItem] {
    switch standing {
    case .hereOnly: return [.delete]
    case .hereAndSetlistFm: return hasPage ? [.openOnSetlistFm, .delete] : [.delete]
    case .setlistFmOnly: return hasPage ? [.openOnSetlistFm] : []
    }
}
