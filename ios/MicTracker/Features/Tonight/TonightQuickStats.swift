import Foundation

// =============================================================================
// TonightQuickStats — the counting behind Tonight's "Quick stats" row
// (spec 6.2) and the Map's stats strip (spec 6.5).
//
// Pure Foundation code (no SwiftUI) so it can be unit tested. Every count
// follows the "blank = null" rule: a nil or whitespace-only neighborhood or
// borough is simply not counted.
// =============================================================================
enum TonightQuickStats {

    /// Number of distinct non-blank neighborhoods.
    static func neighborhoodCount(_ mics: [Mic]) -> Int {
        Set(mics.compactMap { TextRules.trimmedOrNil($0.neighborhood) }).count
    }

    /// Number of distinct non-blank boroughs.
    static func boroughCount(_ mics: [Mic]) -> Int {
        Set(mics.compactMap { TextRules.trimmedOrNil($0.borough) }).count
    }

    /// The borough with the most mics ("Most Mics In"). Ties go to the
    /// alphabetically first borough so the answer never flickers between
    /// refreshes. nil when no mic has a borough.
    static func topBorough(_ mics: [Mic]) -> String? {
        var counts: [String: Int] = [:]
        for mic in mics {
            if let borough = TextRules.trimmedOrNil(mic.borough) {
                counts[borough, default: 0] += 1
            }
        }
        let ranked = counts.sorted { lhs, rhs in
            lhs.value != rhs.value ? lhs.value > rhs.value : lhs.key < rhs.key
        }
        return ranked.first?.key
    }

    /// `topBorough`, or "N/A" like the web app.
    static func topBoroughText(_ mics: [Mic]) -> String {
        topBorough(mics) ?? "N/A"
    }
}
