import SwiftUI

struct LogArrivalFrame: Equatable {
    var space: Double = 1
    var text: Double = 1
    var node: Bool = true
}
struct LogArrival {
    let index: Int
    let count: Int
    let started: Date
    var duration: Double { min(0.36, 3 / Double(max(1, count))) }
    func frame(elapsed: Double, reduceMotion: Bool = false) -> LogArrivalFrame {
        if reduceMotion { return LogArrivalFrame() }
        let progress = min(1, max(0, (elapsed - Double(index) * duration) / duration))
        return LogArrivalFrame(space: min(1, progress / 0.25),
                               text: min(1, max(0, (progress - 0.25) / 0.75)), node: progress >= 1)
    }
}
func logArrivalOrder(before: [String], after: [String], beforeTitles: [String] = [], afterTitles: [String] = []) -> [String] {
    after.enumerated().compactMap { index, key in
        guard !before.contains(key) else { return nil }
        if afterTitles.indices.contains(index), !afterTitles[index].isEmpty {
            let title = afterTitles[index].lowercased()
            if afterTitles.prefix(index + 1).filter({ $0.lowercased() == title }).count <= beforeTitles.filter({ $0.lowercased() == title }).count { return nil }
        }
        return key
    }
}

struct ArrivingLog<Content: View>: View {
    let keys: [String]
    let titles: [String]
    @ViewBuilder var content: (Int, LogArrivalFrame) -> Content
    @Environment(\.accessibilityReduceMotion) private var reduced
    @State private var previous: [String]?
    @State private var previousTitles: [String] = []
    @State private var arrivals: [String: LogArrival] = [:]
    @State private var finished = true
    var body: some View {
        TimelineView(.animation(paused: finished || reduced)) { tick in
            ForEach(Array(keys.enumerated()), id: \.element) { index, key in
                let pending = logArrivalOrder(before: previous ?? keys, after: keys, beforeTitles: previousTitles, afterTitles: titles).contains(key)
                content(index, pending && !reduced ? LogArrivalFrame(space: 0, text: 0, node: false) :
                    arrivals[key]?.frame(elapsed: tick.date.timeIntervalSince(arrivals[key]!.started), reduceMotion: reduced) ?? LogArrivalFrame())
            }
        }
        .onAppear { previous = keys; previousTitles = titles; arrivals = [:]; finished = true }
        .onChange(of: keys + titles) { _ in
            let next = keys
            let added = logArrivalOrder(before: previous ?? next, after: next, beforeTitles: previousTitles, afterTitles: titles)
            let now = Date()
            for (index, key) in added.enumerated() { arrivals[key] = LogArrival(index: index, count: added.count, started: now) }
            previous = next
            previousTitles = titles
            if !added.isEmpty { finished = false }
        }
        .task(id: keys + titles) {
            try? await Task.sleep(nanoseconds: 3_100_000_000)
            if !Task.isCancelled { finished = true }
        }
    }
}
struct ArrivalLayout: Layout {
    let fraction: Double
    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let size = subviews[0].sizeThatFits(proposal)
        return CGSize(width: size.width, height: size.height * fraction)
    }
    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews[0].place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: nil))
    }
}
