import Foundation
import Network

/// A minimal HTTPS GET that is forced over **IPv4** while keeping TLS SNI = the
/// hostname.
///
/// Why this exists: setlist.fm is fronted by CloudFront, whose **IPv6** edge
/// returns a blanket `406 Not Acceptable` to every request (verified on-device:
/// IPv4 → 200, IPv6 → 406, independent of the Accept header). iOS's Happy Eyeballs
/// prefers IPv6 whenever a network offers it — which most cellular carriers and
/// many home networks do — so `URLSession` lands on the broken path and the whole
/// setlist.fm data source dies on real devices. `URLSession` has no "prefer IPv4"
/// switch, and pointing it at an IPv4 literal fails because CloudFront rejects the
/// TLS handshake unless SNI = `api.setlist.fm`. `Network.framework` is the one API
/// that lets us do both: force the IP layer to v4 *and* connect by hostname (so
/// SNI is set correctly and the cert still validates).
///
/// It speaks just enough HTTP/1.1 for setlist.fm's read-only GET API: one request,
/// `Connection: close` so the body is delimited by EOF, `Accept-Encoding: identity`
/// so there's nothing to gunzip, and de-chunking for the one case CloudFront still
/// streams chunked. Not a general HTTP client — scoped to this one broken host.
enum IPv4Https {

    struct Response { let status: Int; let body: Data }

    static func get(url: URL, headers: [String: String], timeout: TimeInterval = 20) async throws -> Response {
        guard let host = url.host else { throw AppError("Bad URL (no host): \(url)") }
        guard let port = NWEndpoint.Port(rawValue: UInt16(url.port ?? 443)) else {
            throw AppError("Bad port")
        }
        var target = url.path.isEmpty ? "/" : url.path
        if let q = url.query { target += "?\(q)" }

        // TLS over TCP, then pin the internet layer to IPv4. Connecting by hostname
        // (not IP) makes Network.framework set the TLS server name to `host`, so the
        // certificate validates against `api.setlist.fm` — the whole point.
        let params = NWParameters.tls
        if let ip = params.defaultProtocolStack.internetProtocol as? NWProtocolIP.Options {
            ip.version = .v4
        }
        let conn = NWConnection(host: .name(host, nil), port: port, using: params)

        var request = "GET \(target) HTTP/1.1\r\n"
        request += "Host: \(host)\r\n"
        for (k, v) in headers { request += "\(k): \(v)\r\n" }
        request += "Accept-Encoding: identity\r\n"
        request += "Connection: close\r\n\r\n"

        let queue = DispatchQueue(label: "ipv4https")
        return try await withCheckedThrowingContinuation { cont in
            let lock = NSLock()
            var done = false
            func finish(_ r: Result<Response, Error>) {
                lock.lock(); let first = !done; done = true; lock.unlock()
                guard first else { return }
                conn.cancel()
                cont.resume(with: r)
            }

            queue.asyncAfter(deadline: .now() + timeout) {
                finish(.failure(AppError("setlist.fm request timed out")))
            }

            var received = Data()
            func readMore() {
                conn.receive(minimumIncompleteLength: 1, maximumLength: 1 << 16) { chunk, _, isComplete, error in
                    if let chunk { received.append(chunk) }
                    if let error { finish(.failure(error)); return }
                    if isComplete { finish(parse(received)); return }
                    readMore()
                }
            }

            conn.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    conn.send(content: Data(request.utf8), completion: .contentProcessed { err in
                        if let err { finish(.failure(err)) } else { readMore() }
                    })
                case .failed(let e):
                    finish(.failure(e))
                default:
                    // .waiting (e.g. no route yet) is left to the timeout rather
                    // than failed outright, since it can still recover to .ready.
                    break
                }
            }
            conn.start(queue: queue)
        }
    }

    private static func parse(_ raw: Data) -> Result<Response, Error> {
        let crlfcrlf = Data("\r\n\r\n".utf8)
        guard let sep = raw.range(of: crlfcrlf) else {
            return .failure(AppError("Malformed HTTP response"))
        }
        let headerData = raw.subdata(in: raw.startIndex..<sep.lowerBound)
        var body = raw.subdata(in: sep.upperBound..<raw.endIndex)
        guard let headerText = String(data: headerData, encoding: .utf8) else {
            return .failure(AppError("Bad HTTP headers"))
        }
        let lines = headerText.components(separatedBy: "\r\n")
        let statusParts = (lines.first ?? "").split(separator: " ")
        guard statusParts.count >= 2, let status = Int(statusParts[1]) else {
            return .failure(AppError("Bad status line: \(lines.first ?? "")"))
        }
        let chunked = lines.dropFirst().contains {
            let l = $0.lowercased()
            return l.hasPrefix("transfer-encoding:") && l.contains("chunked")
        }
        if chunked { body = dechunk(body) }
        return .success(Response(status: status, body: body))
    }

    /// De-chunk a fully-buffered chunked body (whole response is in hand thanks to
    /// `Connection: close`, so no need to stream).
    private static func dechunk(_ data: Data) -> Data {
        let crlf = Data("\r\n".utf8)
        var out = Data()
        var rest = data
        while let nl = rest.range(of: crlf) {
            let sizeField = rest.subdata(in: rest.startIndex..<nl.lowerBound)
            let sizeStr = String(data: sizeField, encoding: .utf8)?
                .split(separator: ";").first.map(String.init)?
                .trimmingCharacters(in: .whitespaces) ?? ""
            guard let size = Int(sizeStr, radix: 16), size > 0 else { break }
            let start = nl.upperBound
            let end = start + size
            guard end <= rest.endIndex else { break }
            out.append(rest.subdata(in: start..<end))
            let next = end + 2 // skip the CRLF that terminates the chunk
            guard next <= rest.endIndex else { break }
            rest = rest.subdata(in: next..<rest.endIndex)
        }
        return out
    }
}
