import Foundation

// =============================================================================
// Map models — the pure (Foundation-only, unit-tested) logic behind the Map
// tab (spec 6.5): zoom presets, the day/borough filter, and which mics can
// be put on the map at all. The MapKit-specific bits (regions, map styles)
// live in MicMapView.swift.
// =============================================================================

// MARK: - Zoom presets

/// The "Quick Zoom" buttons, with the web app's centers and zoom levels.
enum MicMapZoomPreset: String, CaseIterable, Identifiable, Hashable {
    case allNYC
    case manhattan
    case brooklyn
    case queensBronx

    var id: String { rawValue }

    var title: String {
        switch self {
        case .allNYC: return "All NYC"
        case .manhattan: return "Manhattan"
        case .brooklyn: return "Brooklyn"
        case .queensBronx: return "Queens/Bronx"
        }
    }

    var latitude: Double {
        switch self {
        case .allNYC: return 40.7128
        case .manhattan: return 40.7580
        case .brooklyn: return 40.6782
        case .queensBronx: return 40.8000
        }
    }

    var longitude: Double {
        switch self {
        case .allNYC: return -73.9500
        case .manhattan: return -73.9855
        case .brooklyn: return -73.9442
        case .queensBronx: return -73.8800
        }
    }

    /// Web-map (Mercator tile) zoom level used by the Streamlit page.
    var zoom: Double {
        switch self {
        case .allNYC: return 10.5
        case .manhattan: return 12.5
        case .brooklyn: return 12
        case .queensBronx: return 11
        }
    }

    /// MapKit works in degrees, not zoom levels. At zoom z one 256-pt tile
    /// covers 360 / 2^z degrees of longitude (spec 6.5).
    var longitudeSpan: Double {
        360.0 / pow(2.0, zoom)
    }

    /// The latitude span covering the same ground distance (a degree of
    /// longitude is shorter than a degree of latitude away from the equator).
    var latitudeSpan: Double {
        longitudeSpan * cos(latitude * Double.pi / 180.0)
    }

    /// True when a camera centered at (latitude, longitude) is still sitting
    /// on this preset — used to un-highlight the preset after the user pans.
    func isCentered(latitude: Double, longitude: Double) -> Bool {
        let tolerance = longitudeSpan * 0.05
        return abs(latitude - self.latitude) <= tolerance && abs(longitude - self.longitude) <= tolerance
    }
}

// MARK: - Filter

/// The map's Day and Borough chips (both multi-select; empty = all).
struct MicMapFilter: Hashable {
    var days: Set<Weekday> = []
    var boroughs: Set<String> = []

    init() {}

    var isActive: Bool { !days.isEmpty || !boroughs.isEmpty }

    func matches(_ mic: Mic) -> Bool {
        if !days.isEmpty {
            guard let weekday = mic.weekday, days.contains(weekday) else { return false }
        }
        if !boroughs.isEmpty {
            guard let borough = TextRules.trimmedOrNil(mic.borough)?.lowercased(),
                  boroughs.contains(where: { $0.lowercased() == borough })
            else { return false }
        }
        return true
    }

    mutating func toggle(day: Weekday) {
        if days.contains(day) {
            days.remove(day)
        } else {
            days.insert(day)
        }
    }

    mutating func toggle(borough: String) {
        if boroughs.contains(borough) {
            boroughs.remove(borough)
        } else {
            boroughs.insert(borough)
        }
    }

    mutating func reset() {
        self = MicMapFilter()
    }
}

// MARK: - Rules

enum MicMapRules {

    /// Only active mics with real coordinates go on the map.
    static func mappableMics(_ mics: [Mic]) -> [Mic] {
        mics.filter { mic in
            guard mic.isActive, let latitude = mic.latitude, let longitude = mic.longitude else { return false }
            return isValidCoordinate(latitude: latitude, longitude: longitude)
        }
    }

    /// Latitude −90…90 and longitude −180…180 (and not the 0,0 "null island"
    /// placeholder some data sources use for "unknown").
    static func isValidCoordinate(latitude: Double, longitude: Double) -> Bool {
        guard latitude.isFinite, longitude.isFinite else { return false }
        guard (-90.0...90.0).contains(latitude), (-180.0...180.0).contains(longitude) else { return false }
        return !(latitude == 0 && longitude == 0)
    }

    /// Other mics at (practically) the same spot as `mic` — several mics at
    /// one venue share a pin, so the selected-mic card lists the rest.
    static func micsSharingLocation(with mic: Mic, in mics: [Mic]) -> [Mic] {
        guard let latitude = mic.latitude, let longitude = mic.longitude else { return [] }
        let tolerance = 0.00005 // ≈ 5 meters
        let others = mics.filter { other in
            guard other.id != mic.id, let otherLatitude = other.latitude, let otherLongitude = other.longitude else {
                return false
            }
            return abs(otherLatitude - latitude) < tolerance && abs(otherLongitude - longitude) < tolerance
        }
        return MicRules.sortedByDayAndTime(others)
    }

    /// True if any mic's borough isn't one of the five (legend shows "Other").
    static func hasOtherBorough(_ mics: [Mic]) -> Bool {
        let otherColor = BoroughRules.colorHex(for: nil)
        return mics.contains { BoroughRules.colorHex(for: $0.borough) == otherColor }
    }

    /// Rule 16: the date Mic Detail should use for a mic tapped on the map.
    static func nextDate(for mic: Mic, today: CalendarDate) -> CalendarDate? {
        guard let weekday = mic.weekday else { return nil }
        return ScheduleRules.nextOccurrence(of: weekday, from: today)
    }
}
