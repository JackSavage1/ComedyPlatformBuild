import Foundation

// =============================================================================
// ManageLogic — the pure (no SwiftUI) bits of the Manage screen (spec 6.8, a
// port of pages/4_⚙️_Settings.py plus the Map page's geocoding tools):
// grouping the scraper queue by source, row labels, searching mics and the
// geocoding counts. Unit tested in MicTrackerTests/ManageTests.swift.
// =============================================================================

/// The pending scraper listings from one source ("Bad Slava", "FireMics").
struct ManageScrapedGroup: Identifiable, Hashable {
    /// The raw source key, e.g. "badslava" (what `importAllScraped` takes).
    let source: String
    /// "Bad Slava"
    let title: String
    let listings: [ScrapedMic]

    var id: String { source }
}

/// How a scrape_log status should look.
enum ManageScrapeStatus: Hashable {
    case success
    case failure
    case other
}

enum ManageLogic {

    /// Shown under the import list.
    static let scraperCaption = "Scrapers run nightly on GitHub Actions. Duplicates are filtered automatically."

    // MARK: Import from Scrapers

    /// Groups listings by source, keeping the order they arrive in (AppStore
    /// already sorts them by source, then day, then name).
    static func scrapedGroups(_ listings: [ScrapedMic]) -> [ManageScrapedGroup] {
        var order: [String] = []
        var bySource: [String: [ScrapedMic]] = [:]
        for listing in listings {
            if bySource[listing.source] == nil {
                order.append(listing.source)
            }
            bySource[listing.source, default: []].append(listing)
        }
        return order.map { source in
            ManageScrapedGroup(
                source: source,
                title: ScrapedMic.displayName(forSource: source),
                listings: bySource[source] ?? []
            )
        }
    }

    /// "Monday — Comedy Night @ The Creek" (the day falls back to the raw
    /// text, or "Unknown day"; " @ venue" is left off when it's blank).
    static func scrapedTitle(_ listing: ScrapedMic) -> String {
        let day = listing.weekday?.name ?? TextRules.trimmedOrNil(listing.dayOfWeek) ?? "Unknown day"
        let venue = listing.venue.trimmingCharacters(in: .whitespacesAndNewlines)
        let nameAndVenue = venue.isEmpty ? listing.name : "\(listing.name) @ \(venue)"
        return "\(day) — \(nameAndVenue)"
    }

    /// "7:00 PM · $5" for a listing (cost falls back to "Free/TBD", rule 2).
    static func scrapedDetails(_ listing: ScrapedMic) -> String {
        TextRules.joinNonBlank([listing.timeText, MicRules.costText(listing.cost)], separator: " · ")
    }

    // MARK: Manage Mics

    /// "Mon — Comedy Night @ The Creek"
    static func micListLabel(_ mic: Mic) -> String {
        let day = mic.weekday?.shortName ?? TextRules.trimmedOrNil(String(mic.dayOfWeek.prefix(3))) ?? "?"
        let nameAndVenue = mic.venue.isEmpty ? mic.name : "\(mic.name) @ \(mic.venue)"
        return "\(day) — \(nameAndVenue)"
    }

    /// Mics whose name, venue, neighborhood, borough or day contains the
    /// search text (case-insensitive). Empty search → all of them, unchanged.
    static func searchMics(_ mics: [Mic], query: String) -> [Mic] {
        guard let needle = TextRules.trimmedOrNil(query)?.lowercased() else { return mics }
        return mics.filter { mic in
            let haystack = [mic.name, mic.venue, mic.neighborhood ?? "", mic.borough ?? "", mic.dayOfWeek]
            return haystack.contains { $0.lowercased().contains(needle) }
        }
    }

    // MARK: Geocoding

    /// "Mics with coordinates X / Y" over the active mics.
    static func coordinatesText(activeMics: [Mic]) -> String {
        let placed = activeMics.filter { $0.hasCoordinates }.count
        return "\(placed) / \(activeMics.count)"
    }

    /// How many of the mics missing coordinates have an address to look up
    /// (the ones "Geocode with device" will try).
    static func geocodableCount(_ missing: [Mic]) -> Int {
        missing.filter { TextRules.trimmedOrNil($0.address) != nil }.count
    }

    // MARK: Scrape History

    /// "success"/"ok" → green, anything with "error"/"fail" → red.
    static func scrapeStatus(_ raw: String?) -> ManageScrapeStatus {
        guard let status = TextRules.trimmedOrNil(raw)?.lowercased() else { return .other }
        if status.contains("error") || status.contains("fail") { return .failure }
        if status.contains("success") || status == "ok" || status == "done" { return .success }
        return .other
    }
}
