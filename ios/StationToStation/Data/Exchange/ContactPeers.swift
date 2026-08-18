import Foundation
import Network

/// Who is reachable over the same WiFi right now (#265): Bonjour via `NWBrowser`, thin
/// the same way `BleCardCentral` is thin — discovery only, no decisions inside it.
///
/// The twin of Android's `exchange/ContactPeers.kt`, and deliberately no more than a
/// twin. An mDNS service instance carries no room for a Friend card and this does not try
/// to give it one: **presence is not identity**. A discovered endpoint is somewhere to
/// open a TLS connection, nothing else; `mutualContactAuth` is what turns "something
/// answered" into "a known Contact answered".
///
/// **Not unit-tested**, matching the precedent `BleCardCentral`/`BleCardPeripheral` set:
/// a framework wrapper with nothing to assert beyond what the pure functions already
/// cover, kept thin enough that this stays true.
final class ContactPeers {

    /// Android's constant verbatim, minus the trailing dot `NWBrowser` supplies itself —
    /// the same bytes go out either way. Changing it on one platform and not the other is
    /// how two apps in the same room stop finding each other.
    static let serviceType = "_stationtostation._tcp"

    /// Every endpoint seen answering, once each. Called on `queue` — and *set* on it too,
    /// via `deliverTo`: the browse handler reads this from `queue` while the screen sets it
    /// from the main actor, which is a data race however harmless the assignment looks.
    private var onEndpoint: ((NWEndpoint) -> Void)?

    func deliverTo(_ handler: ((NWEndpoint) -> Void)?) {
        queue.async { self.onEndpoint = handler }
    }

    private let queue = DispatchQueue(label: "io.github.magnusencoded.stationtostation.peers")
    private var browser: NWBrowser?
    private var seen = Set<String>()
    /// What this device itself published, so its own advertisement answering back is not
    /// treated as a peer. Set once the listener says what name it actually got — Bonjour
    /// renames on collision, so the name asked for and the name registered are not always
    /// the same string.
    private var ownServiceName: String?

    func registered(_ name: String?) {
        queue.async { self.ownServiceName = name }
    }

    /// Starting the browser is what makes iOS raise its local-network prompt — there is no
    /// separate permission call — so *when this is called* is the entire gating mechanism
    /// (see `ExchangeView`). Denial is silent here: the browser simply never reports
    /// anybody, and every other way of meeting someone carries on untouched.
    func start() {
        if browser != nil { return }
        let parameters = NWParameters()
        parameters.includePeerToPeer = true
        let browser = NWBrowser(
            for: .bonjour(type: Self.serviceType, domain: nil), using: parameters
        )
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            guard let self else { return }
            for result in results {
                guard case let .service(name, _, _, _) = result.endpoint else { continue }
                if name == self.ownServiceName { continue }
                // Deliberately additive, same reasoning as Android's empty `onServiceLost`:
                // an address that stops answering has not un-happened, and a stale entry
                // just fails to connect later rather than showing something false now.
                if self.seen.insert(name).inserted { self.onEndpoint?(result.endpoint) }
            }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
        // On `queue` because that is where the browse handler reads them, and stopping
        // is called from whichever thread left the screen.
        queue.async {
            self.seen.removeAll()
            self.ownServiceName = nil
        }
    }
}
