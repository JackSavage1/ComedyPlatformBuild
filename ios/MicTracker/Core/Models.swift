import Foundation

// =============================================================================
// Models — Swift versions of the database rows the app reads.
//
// Every model has a hand-written `init(from:)` instead of relying on the
// compiler-generated one. That lets us be forgiving about what the server
// sends (spec 3.5): missing keys and nulls never crash, numbers that arrive
// as floats (`30.0`) still become Ints, and blank strings become nil. Only
// `id` is required — a row without one is skipped (see `LossyDecodable`).
//
// Each model also has a plain memberwise-style initializer with defaults so
// tests and previews can build values without JSON.
// =============================================================================

// MARK: - open_mics

struct Mic: Identifiable, Hashable, Sendable, Decodable {
    let id: Int
    var name: String
    var venue: String
    var address: String?
    var neighborhood: String?
    var borough: String?
    /// Full English name, "Monday"…"Sunday" (see `weekday` for the parsed value).
    var dayOfWeek: String
    /// Stored "HH:MM" 24-hour time; may be malformed when it came from a scraper.
    var startTime: String
    var displayTime: String?
    var endTime: String?
    var cost: String?
    /// nil or 0 means unknown.
    var setLengthMin: Int?
    /// Raw value, e.g. "in_person" (see `signupMethodKind`).
    var signupMethod: String?
    var signupUrl: String?
    var signupNotes: String?
    var venueUrl: String?
    var instagram: String?
    /// Raw value, e.g. "high" (see `urgencyLevel`).
    var urgency: String?
    var urgencyNote: String?
    var advanceDays: Int
    /// The owner's own 0–10 rating of the mic.
    var micRating: Double?
    var notes: String?
    var latitude: Double?
    var longitude: Double?
    /// false = soft-deleted. Inactive mics are still loaded so set history can
    /// show their names.
    var isActive: Bool
    var isBiweekly: Bool

    init(
        id: Int,
        name: String = "",
        venue: String = "",
        address: String? = nil,
        neighborhood: String? = nil,
        borough: String? = nil,
        dayOfWeek: String = "Monday",
        startTime: String = "",
        displayTime: String? = nil,
        endTime: String? = nil,
        cost: String? = nil,
        setLengthMin: Int? = nil,
        signupMethod: String? = nil,
        signupUrl: String? = nil,
        signupNotes: String? = nil,
        venueUrl: String? = nil,
        instagram: String? = nil,
        urgency: String? = nil,
        urgencyNote: String? = nil,
        advanceDays: Int = 0,
        micRating: Double? = nil,
        notes: String? = nil,
        latitude: Double? = nil,
        longitude: Double? = nil,
        isActive: Bool = true,
        isBiweekly: Bool = false
    ) {
        self.id = id
        self.name = name
        self.venue = venue
        self.address = address
        self.neighborhood = neighborhood
        self.borough = borough
        self.dayOfWeek = dayOfWeek
        self.startTime = startTime
        self.displayTime = displayTime
        self.endTime = endTime
        self.cost = cost
        self.setLengthMin = setLengthMin
        self.signupMethod = signupMethod
        self.signupUrl = signupUrl
        self.signupNotes = signupNotes
        self.venueUrl = venueUrl
        self.instagram = instagram
        self.urgency = urgency
        self.urgencyNote = urgencyNote
        self.advanceDays = advanceDays
        self.micRating = micRating
        self.notes = notes
        self.latitude = latitude
        self.longitude = longitude
        self.isActive = isActive
        self.isBiweekly = isBiweekly
    }

    enum CodingKeys: String, CodingKey {
        case id, name, venue, address, neighborhood, borough
        case dayOfWeek = "day_of_week"
        case startTime = "start_time"
        case displayTime = "display_time"
        case endTime = "end_time"
        case cost
        case setLengthMin = "set_length_min"
        case signupMethod = "signup_method"
        case signupUrl = "signup_url"
        case signupNotes = "signup_notes"
        case venueUrl = "venue_url"
        case instagram, urgency
        case urgencyNote = "urgency_note"
        case advanceDays = "advance_days"
        case micRating = "mic_rating"
        case notes, latitude, longitude
        case isActive = "is_active"
        case isBiweekly = "is_biweekly"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.requiredLenientInt(.id)
        self.name = c.lenientString(.name) ?? "Unnamed mic"
        self.venue = c.lenientString(.venue) ?? ""
        self.address = c.lenientString(.address)
        self.neighborhood = c.lenientString(.neighborhood)
        self.borough = c.lenientString(.borough)
        self.dayOfWeek = c.lenientString(.dayOfWeek) ?? ""
        self.startTime = c.lenientString(.startTime) ?? ""
        self.displayTime = c.lenientString(.displayTime)
        self.endTime = c.lenientString(.endTime)
        self.cost = c.lenientString(.cost)
        self.setLengthMin = c.lenientInt(.setLengthMin)
        self.signupMethod = c.lenientString(.signupMethod)
        self.signupUrl = c.lenientString(.signupUrl)
        self.signupNotes = c.lenientString(.signupNotes)
        self.venueUrl = c.lenientString(.venueUrl)
        self.instagram = c.lenientString(.instagram)
        self.urgency = c.lenientString(.urgency)
        self.urgencyNote = c.lenientString(.urgencyNote)
        self.advanceDays = max(0, c.lenientInt(.advanceDays) ?? 0)
        self.micRating = c.lenientDouble(.micRating)
        self.notes = c.lenientString(.notes)
        self.latitude = c.lenientDouble(.latitude)
        self.longitude = c.lenientDouble(.longitude)
        self.isActive = c.lenientBool(.isActive) ?? true
        self.isBiweekly = c.lenientBool(.isBiweekly) ?? false
    }
}

// MARK: - my_sets

/// One row of `my_sets`. Named `SetEntry` so it doesn't clash with Swift's
/// `Set` type. A row with `setRating == nil` is a *planned* set that "Going"
/// created and hasn't been logged yet.
struct SetEntry: Identifiable, Hashable, Sendable, Decodable {
    let id: Int
    var openMicId: Int?
    var datePerformed: CalendarDate
    /// 1–10; nil ⇒ planned (skeletal) row.
    var setRating: Int?
    var crowdRating: Int?
    /// Raw value: "empty", "sparse", "decent", "packed" (see `crowdSizeKind`).
    var crowdSize: String?
    var setList: String?
    var recordingUrl: String?
    /// Raw value: "none", "video", "audio" (see `recordingKind`).
    var recordingType: String?
    var notes: String?
    var newMaterial: Bool
    var gotFeedback: Bool
    var feedbackNotes: String?
    /// The database default is true; nil means "not recorded".
    var wouldReturn: Bool?
    /// Comma-separated, e.g. "killed,crowd_work" (see `tagList`).
    var tags: String?

    init(
        id: Int,
        openMicId: Int? = nil,
        datePerformed: CalendarDate,
        setRating: Int? = nil,
        crowdRating: Int? = nil,
        crowdSize: String? = nil,
        setList: String? = nil,
        recordingUrl: String? = nil,
        recordingType: String? = nil,
        notes: String? = nil,
        newMaterial: Bool = false,
        gotFeedback: Bool = false,
        feedbackNotes: String? = nil,
        wouldReturn: Bool? = true,
        tags: String? = nil
    ) {
        self.id = id
        self.openMicId = openMicId
        self.datePerformed = datePerformed
        self.setRating = setRating
        self.crowdRating = crowdRating
        self.crowdSize = crowdSize
        self.setList = setList
        self.recordingUrl = recordingUrl
        self.recordingType = recordingType
        self.notes = notes
        self.newMaterial = newMaterial
        self.gotFeedback = gotFeedback
        self.feedbackNotes = feedbackNotes
        self.wouldReturn = wouldReturn
        self.tags = tags
    }

    enum CodingKeys: String, CodingKey {
        case id
        case openMicId = "open_mic_id"
        case datePerformed = "date_performed"
        case setRating = "set_rating"
        case crowdRating = "crowd_rating"
        case crowdSize = "crowd_size"
        case setList = "set_list"
        case recordingUrl = "recording_url"
        case recordingType = "recording_type"
        case notes
        case newMaterial = "new_material"
        case gotFeedback = "got_feedback"
        case feedbackNotes = "feedback_notes"
        case wouldReturn = "would_return"
        case tags
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.requiredLenientInt(.id)
        self.openMicId = c.lenientInt(.openMicId)
        guard let date = CalendarDate(string: c.lenientString(.datePerformed)) else {
            throw DecodingError.dataCorruptedError(
                forKey: .datePerformed, in: c,
                debugDescription: "my_sets row has no valid date_performed"
            )
        }
        self.datePerformed = date
        self.setRating = c.lenientInt(.setRating)
        self.crowdRating = c.lenientInt(.crowdRating)
        self.crowdSize = c.lenientString(.crowdSize)
        self.setList = c.lenientString(.setList)
        self.recordingUrl = c.lenientString(.recordingUrl)
        self.recordingType = c.lenientString(.recordingType)
        self.notes = c.lenientString(.notes)
        self.newMaterial = c.lenientBool(.newMaterial) ?? false
        self.gotFeedback = c.lenientBool(.gotFeedback) ?? false
        self.feedbackNotes = c.lenientString(.feedbackNotes)
        self.wouldReturn = c.lenientBool(.wouldReturn)
        self.tags = c.lenientString(.tags)
    }
}

// MARK: - mic_plans

/// A Going / Cancelled mark for one mic on one date.
enum PlanStatus: String, CaseIterable, Hashable, Sendable {
    case going
    case cancelled

    init?(raw: String?) {
        guard let cleaned = TextRules.trimmedOrNil(raw)?.lowercased() else { return nil }
        self.init(rawValue: cleaned)
    }
}

struct MicPlan: Identifiable, Hashable, Sendable, Decodable {
    let id: Int
    var openMicId: Int
    var planDate: CalendarDate
    /// Raw status text; see `status`.
    var statusRaw: String

    /// nil if the server ever sends something other than going/cancelled.
    var status: PlanStatus? { PlanStatus(raw: statusRaw) }

    init(id: Int, openMicId: Int, planDate: CalendarDate, status: PlanStatus) {
        self.id = id
        self.openMicId = openMicId
        self.planDate = planDate
        self.statusRaw = status.rawValue
    }

    enum CodingKeys: String, CodingKey {
        case id
        case openMicId = "open_mic_id"
        case planDate = "plan_date"
        case statusRaw = "status"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.requiredLenientInt(.id)
        self.openMicId = try c.requiredLenientInt(.openMicId)
        guard let date = CalendarDate(string: c.lenientString(.planDate)) else {
            throw DecodingError.dataCorruptedError(
                forKey: .planDate, in: c,
                debugDescription: "mic_plans row has no valid plan_date"
            )
        }
        self.planDate = date
        self.statusRaw = c.lenientString(.statusRaw) ?? ""
    }
}

// MARK: - scraped_mics

/// A listing the nightly scraper found that isn't in `open_mics` yet.
struct ScrapedMic: Identifiable, Hashable, Sendable, Decodable {
    let id: Int
    /// "badslava" or "firemics".
    var source: String
    var name: String
    var venue: String
    var dayOfWeek: String?
    var address: String?
    var displayTime: String?
    var startTime: String?
    var cost: String?
    var signupUrl: String?
    /// "pending", "added" or "dismissed".
    var status: String
    var firstSeenAt: Date?

    init(
        id: Int,
        source: String,
        name: String,
        venue: String,
        dayOfWeek: String? = nil,
        address: String? = nil,
        displayTime: String? = nil,
        startTime: String? = nil,
        cost: String? = nil,
        signupUrl: String? = nil,
        status: String = "pending",
        firstSeenAt: Date? = nil
    ) {
        self.id = id
        self.source = source
        self.name = name
        self.venue = venue
        self.dayOfWeek = dayOfWeek
        self.address = address
        self.displayTime = displayTime
        self.startTime = startTime
        self.cost = cost
        self.signupUrl = signupUrl
        self.status = status
        self.firstSeenAt = firstSeenAt
    }

    enum CodingKeys: String, CodingKey {
        case id, source, name, venue
        case dayOfWeek = "day_of_week"
        case address
        case displayTime = "display_time"
        case startTime = "start_time"
        case cost
        case signupUrl = "signup_url"
        case status
        case firstSeenAt = "first_seen_at"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.requiredLenientInt(.id)
        self.source = c.lenientString(.source) ?? "unknown"
        self.name = c.lenientString(.name) ?? "Unnamed mic"
        self.venue = c.lenientString(.venue) ?? ""
        self.dayOfWeek = c.lenientString(.dayOfWeek)
        self.address = c.lenientString(.address)
        self.displayTime = c.lenientString(.displayTime)
        self.startTime = c.lenientString(.startTime)
        self.cost = c.lenientString(.cost)
        self.signupUrl = c.lenientString(.signupUrl)
        self.status = c.lenientString(.status) ?? "pending"
        self.firstSeenAt = TimestampParser.parse(c.lenientString(.firstSeenAt))
    }

    /// "Bad Slava" / "FireMics" / prettified unknown source.
    var sourceDisplayName: String { ScrapedMic.displayName(forSource: source) }

    static func displayName(forSource source: String) -> String {
        switch source.lowercased() {
        case "badslava": return "Bad Slava"
        case "firemics": return "FireMics"
        default: return TextRules.prettify(source)
        }
    }

    var weekday: Weekday? { Weekday(name: dayOfWeek) }

    /// Rule 1 applied to the listing.
    var timeText: String { MicRules.timeText(displayTime: displayTime, startTime: startTime) }
}

// MARK: - scrape_log

struct ScrapeLogEntry: Identifiable, Hashable, Sendable, Decodable {
    let id: Int
    var source: String?
    /// Parsed `last_scraped` (naive timestamps are treated as UTC — the
    /// scrapers run on UTC servers).
    var lastScraped: Date?
    /// The original text, shown if parsing failed.
    var lastScrapedRaw: String?
    var status: String?
    var notes: String?

    init(id: Int, source: String? = nil, lastScraped: Date? = nil, lastScrapedRaw: String? = nil, status: String? = nil, notes: String? = nil) {
        self.id = id
        self.source = source
        self.lastScraped = lastScraped
        self.lastScrapedRaw = lastScrapedRaw
        self.status = status
        self.notes = notes
    }

    enum CodingKeys: String, CodingKey {
        case id, source
        case lastScraped = "last_scraped"
        case status, notes
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.requiredLenientInt(.id)
        self.source = c.lenientString(.source)
        let raw = c.lenientString(.lastScraped)
        self.lastScrapedRaw = raw
        self.lastScraped = TimestampParser.parse(raw)
        self.status = c.lenientString(.status)
        self.notes = c.lenientString(.notes)
    }

    /// "Oct 20, 2026 at 3:00 AM" in the device's time zone, or the raw text.
    var lastScrapedText: String {
        if let date = lastScraped { return TimestampParser.displayString(for: date) }
        return lastScrapedRaw ?? "—"
    }
}

// MARK: - Lenient decoding helpers

extension KeyedDecodingContainer {

    /// A trimmed, non-blank string. Numbers are turned into text; anything
    /// else (null, missing, objects) is nil.
    func lenientString(_ key: Key) -> String? {
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return TextRules.trimmedOrNil(value)
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return String(value)
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key), value.isFinite {
            return String(value)
        }
        return nil
    }

    /// An Int from 30, 30.0, or "30". nil for anything else.
    func lenientInt(_ key: Key) -> Int? {
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return LenientNumber.int(from: value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            return LenientNumber.int(from: value)
        }
        return nil
    }

    /// Like `lenientInt`, but throws when the value is missing — used for `id`.
    func requiredLenientInt(_ key: Key) throws -> Int {
        guard let value = lenientInt(key) else {
            throw DecodingError.keyNotFound(
                key,
                DecodingError.Context(codingPath: codingPath, debugDescription: "Missing or invalid \(key.stringValue)")
            )
        }
        return value
    }

    /// A Double from 4.5, 4, or "4.5". NaN/infinity become nil.
    func lenientDouble(_ key: Key) -> Double? {
        if let value = try? decodeIfPresent(Double.self, forKey: key) {
            return value.isFinite ? value : nil
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return Double(value)
        }
        if let value = try? decodeIfPresent(String.self, forKey: key),
           let trimmed = TextRules.trimmedOrNil(value),
           let parsed = Double(trimmed), parsed.isFinite {
            return parsed
        }
        return nil
    }

    /// A Bool from true/false, 1/0, or "true"/"t"/"yes"/"1" (and their opposites).
    func lenientBool(_ key: Key) -> Bool? {
        if let value = try? decodeIfPresent(Bool.self, forKey: key) {
            return value
        }
        if let value = try? decodeIfPresent(Int.self, forKey: key) {
            return value != 0
        }
        if let value = try? decodeIfPresent(String.self, forKey: key) {
            switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
            case "true", "t", "yes", "y", "1", "on": return true
            case "false", "f", "no", "n", "0", "off": return false
            default: return nil
            }
        }
        return nil
    }
}

/// Number coercions shared by the lenient decoders.
enum LenientNumber {
    /// 30.0 → 30, 29.6 → 30. nil for NaN/infinite/absurdly large values
    /// (converting those to Int would crash).
    static func int(from value: Double) -> Int? {
        guard value.isFinite, abs(value) < 1e15 else { return nil }
        return Int(value.rounded())
    }

    static func int(from text: String) -> Int? {
        guard let trimmed = TextRules.trimmedOrNil(text) else { return nil }
        if let exact = Int(trimmed) { return exact }
        if let double = Double(trimmed) { return int(from: double) }
        return nil
    }
}

/// Decodes one array element, turning a failure into `nil` instead of
/// failing the whole array. `APIClient` uses it so one bad row (say, a set
/// with no date) can't hide every other row.
struct LossyDecodable<Value: Decodable>: Decodable {
    let value: Value?

    init(from decoder: Decoder) throws {
        value = try? Value(from: decoder)
    }
}

// MARK: - Timestamps

/// Parses Postgres timestamps such as
/// "2026-10-20T03:00:00", "2026-10-20 03:00:00.123456" and
/// "2026-10-20T03:00:00.123456+00:00". Naive timestamps (no zone) are UTC.
enum TimestampParser {

    static func parse(_ raw: String?) -> Date? {
        guard let text = TextRules.trimmedOrNil(raw) else { return nil }
        let characters = Array(text)
        guard characters.count >= 10, let date = CalendarDate(string: String(characters[0..<10])) else { return nil }

        var hour = 0, minute = 0, second = 0
        var fraction = 0.0
        var offsetSeconds = 0
        var index = 10

        // Time part: [T or space]HH:MM[:SS[.ffffff]]
        if index < characters.count, characters[index] == "T" || characters[index] == "t" || characters[index] == " " {
            index += 1
            guard let h = readNumber(characters, &index, digits: 2) else { return nil }
            hour = h
            guard index < characters.count, characters[index] == ":" else { return nil }
            index += 1
            guard let m = readNumber(characters, &index, digits: 2) else { return nil }
            minute = m
            if index < characters.count, characters[index] == ":" {
                index += 1
                guard let s = readNumber(characters, &index, digits: 2) else { return nil }
                second = s
                if index < characters.count, characters[index] == "." || characters[index] == "," {
                    index += 1
                    var digits = ""
                    while index < characters.count, characters[index].isASCII, characters[index].isNumber {
                        digits.append(characters[index])
                        index += 1
                    }
                    fraction = Double("0." + digits) ?? 0
                }
            }
        }

        // Zone part: Z | ±HH | ±HH:MM | ±HHMM (missing → UTC)
        if index < characters.count {
            let sign = characters[index]
            if sign == "Z" || sign == "z" {
                offsetSeconds = 0
            } else if sign == "+" || sign == "-" {
                index += 1
                guard let offsetHours = readNumber(characters, &index, digits: 2) else { return nil }
                var offsetMinutes = 0
                if index < characters.count, characters[index] == ":" { index += 1 }
                if let parsedMinutes = readNumber(characters, &index, digits: 2) { offsetMinutes = parsedMinutes }
                offsetSeconds = (offsetHours * 3600 + offsetMinutes * 60) * (sign == "-" ? -1 : 1)
            } else {
                return nil
            }
        }

        guard hour < 24, minute < 60, second < 61 else { return nil }
        let secondsSinceEpoch = Double(date.dayNumber) * 86_400
            + Double(hour * 3600 + minute * 60 + second)
            + fraction
            - Double(offsetSeconds)
        return Date(timeIntervalSince1970: secondsSinceEpoch)
    }

    /// Reads exactly `digits` ASCII digits starting at `index`, advancing it.
    private static func readNumber(_ characters: [Character], _ index: inout Int, digits: Int) -> Int? {
        guard index + digits <= characters.count else { return nil }
        let slice = characters[index..<(index + digits)]
        guard slice.allSatisfy({ $0.isASCII && $0.isNumber }), let value = Int(String(slice)) else { return nil }
        index += digits
        return value
    }

    /// "Oct 20, 2026 at 3:00 AM" in the device's local time zone.
    static func displayString(for date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = TimeZone.current
        formatter.dateFormat = "MMM d, yyyy 'at' h:mm a"
        return formatter.string(from: date)
    }
}
