import SwiftUI

/// A **Contact**'s offer, opened (#405): yes files it on this Night and joins the two
/// Nights, no leaves this Night exactly as it was, "Not now" leaves the question waiting.
/// Its own modifier so the Night's body stays within what the type checker will take.
struct MediaOfferAlert: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Binding var answering: String?
    let show: FmSetlist

    private var waiting: WaitingOffer? {
        guard let night = answering, let offer = model.state.mediaOffers[night], !offer.media.isEmpty
        else { return nil }
        return WaitingOffer(night: night, offer: offer)
    }

    private func sender(_ offer: MediaOffer) -> String {
        mediaOfferSenderName(offer, friends: model.state.friends)
    }

    private func message(_ offer: MediaOffer) -> String {
        let theirs = [offer.artist, offer.venue].filter { !$0.isEmpty }.joined(separator: " at ")
        let from = theirs.isEmpty ? "" : "From their night: \(theirs). "
        return from + "Accept puts them on this night, as the same night. Decline leaves it as it is."
    }

    func body(content: Content) -> some View {
        let current = waiting
        let title = current.map { mediaOfferLine($0.offer, sender: sender($0.offer)) } ?? ""
        let shown = Binding<Bool>(get: { current != nil }, set: { if !$0 { answering = nil } })
        return content.alert(title, isPresented: shown, presenting: current) { offer in
            Button("Accept") {
                model.contacts.acceptMediaOffer(offer.night, key: show.id)
                answering = nil
            }
            .accessibilityLabel("Accept \(sender(offer.offer))'s offer onto this night")
            Button("Decline") {
                model.contacts.declineMediaOffer(offer.night)
                answering = nil
            }
            .accessibilityLabel("Decline \(sender(offer.offer))'s offer")
            Button("Not now", role: .cancel) { answering = nil }
        } message: { offer in
            Text(message(offer.offer))
        }
    }
}

/// Whose offer this is: the Contact's name, or "A Contact" when their key is not on my list.
func mediaOfferSenderName(_ offer: MediaOffer, friends: [Friend]) -> String {
    offer.media.lazy.compactMap(\.from).first.flatMap { friends.nameOf($0) } ?? "A Contact"
}

/// "Mia offered 3 photos", counted the way a person would say it. Android's `offerLine`.
func mediaOfferLine(_ offer: MediaOffer, sender: String) -> String {
    let n = offer.media.count
    let kinds = Set(offer.media.map(\.kind))
    let only = kinds.count == 1 ? kinds.first : nil
    let what: String
    if only == StoredMedia.Kind.photo {
        what = n == 1 ? "a photo" : "\(n) photos"
    } else if only == StoredMedia.Kind.video {
        what = n == 1 ? "a video" : "\(n) videos"
    } else if only == StoredMedia.Kind.note {
        what = n == 1 ? "a note" : "\(n) notes"
    } else {
        what = "\(n) things"
    }
    return "\(sender) offered \(what)"
}
