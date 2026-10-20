import Foundation

// =============================================================================
// Payloads — what the app SENDS to the database.
//
// Two rules from spec 3.5 drive this file:
//   * INSERT payloads leave out null keys, so database defaults apply.
//   * UPDATE payloads send an explicit `null` for every field the user
//     cleared — otherwise the old value would silently stay.
//
// The forms (Mic Form, Log a Set) edit a `MicFields` / `SetFields` value and
// hand it to `AppStore`, which wraps it in the right payload type.
// =============================================================================

// MARK: - Mic

/// Every editable column of `open_mics`, as the Mic Form edits them.
struct MicFields: Hashable, Sendable {
    var name: String = ""
    var venue: String = ""
    var address: String?
    var neighborhood: String?
    var borough: String?
    var dayOfWeek: String = Weekday.monday.name
    /// "HH:mm" 24-hour, e.g. "19:00".
    var startTime: String = ""
    /// e.g. "7:00 PM".
    var displayTime: String?
    var cost: String?
    /// 0 / nil = unknown (saved as null).
    var setLengthMin: Int?
    var signupMethod: String?
    var signupUrl: String?
    var signupNotes: String?
    var venueUrl: String?
    var instagram: String?
    /// "normal", "medium" or "high".
    var urgency: String = Urgency.normal.rawValue
    var urgencyNote: String?
    var advanceDays: Int = 0
    var isBiweekly: Bool = false
    var notes: String?
    var latitude: Double?
    var longitude: Double?

    init() {}

    /// Prefills the form from an existing mic (Edit).
    init(mic: Mic) {
        name = mic.name
        venue = mic.venue
        address = mic.address
        neighborhood = mic.neighborhood
        borough = mic.borough
        dayOfWeek = mic.weekday?.name ?? mic.dayOfWeek
        startTime = mic.startTime
        displayTime = mic.displayTime
        cost = mic.cost
        setLengthMin = mic.setLengthMin
        signupMethod = mic.signupMethod
        signupUrl = mic.signupUrl
        signupNotes = mic.signupNotes
        venueUrl = mic.venueUrl
        instagram = mic.instagram
        urgency = mic.urgencyLevel.rawValue
        urgencyNote = mic.urgencyNote
        advanceDays = mic.advanceDays
        isBiweekly = mic.isBiweekly
        notes = mic.notes
        latitude = mic.latitude
        longitude = mic.longitude
    }

    /// Trimmed copy: blank text → nil, set length 0 → nil, negative advance
    /// days → 0, unknown urgency → "normal".
    func normalized() -> MicFields {
        var copy = self
        copy.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.venue = venue.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.address = TextRules.trimmedOrNil(address)
        copy.neighborhood = TextRules.trimmedOrNil(neighborhood)
        copy.borough = TextRules.trimmedOrNil(borough)
        copy.dayOfWeek = dayOfWeek.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.startTime = startTime.trimmingCharacters(in: .whitespacesAndNewlines)
        copy.displayTime = TextRules.trimmedOrNil(displayTime)
        copy.cost = TextRules.trimmedOrNil(cost)
        if let minutes = setLengthMin, minutes > 0 {
            copy.setLengthMin = minutes
        } else {
            copy.setLengthMin = nil
        }
        copy.signupMethod = TextRules.trimmedOrNil(signupMethod)
        copy.signupUrl = TextRules.trimmedOrNil(signupUrl)
        copy.signupNotes = TextRules.trimmedOrNil(signupNotes)
        copy.venueUrl = TextRules.trimmedOrNil(venueUrl)
        copy.instagram = TextRules.trimmedOrNil(instagram)
        copy.urgency = Urgency(raw: urgency).rawValue
        copy.urgencyNote = TextRules.trimmedOrNil(urgencyNote)
        copy.advanceDays = max(0, advanceDays)
        copy.notes = TextRules.trimmedOrNil(notes)
        return copy
    }

    /// The exact web-app message, or nil when the required fields are filled.
    var validationError: String? {
        let n = normalized()
        if n.name.isEmpty || n.venue.isEmpty || n.dayOfWeek.isEmpty || n.startTime.isEmpty {
            return "Name, Venue, Day of Week, and Start Time are required!"
        }
        return nil
    }
}

/// The `open_mics` column names used by the payloads below.
enum MicColumn: String, CodingKey {
    case name, venue, address, neighborhood, borough
    case dayOfWeek = "day_of_week"
    case startTime = "start_time"
    case displayTime = "display_time"
    case cost
    case setLengthMin = "set_length_min"
    case signupMethod = "signup_method"
    case signupUrl = "signup_url"
    case signupNotes = "signup_notes"
    case venueUrl = "venue_url"
    case instagram, urgency
    case urgencyNote = "urgency_note"
    case advanceDays = "advance_days"
    case isBiweekly = "is_biweekly"
    case notes, latitude, longitude
    case isActive = "is_active"
    case micRating = "mic_rating"
}

/// POST /rest/v1/open_mics — nil fields are left out; `is_active` is true.
struct MicInsertPayload: Encodable {
    let fields: MicFields

    func encode(to encoder: Encoder) throws {
        let f = fields.normalized()
        var c = encoder.container(keyedBy: MicColumn.self)
        try c.encode(f.name, forKey: .name)
        try c.encode(f.venue, forKey: .venue)
        try c.encodeIfPresent(f.address, forKey: .address)
        try c.encodeIfPresent(f.neighborhood, forKey: .neighborhood)
        try c.encodeIfPresent(f.borough, forKey: .borough)
        try c.encode(f.dayOfWeek, forKey: .dayOfWeek)
        try c.encode(f.startTime, forKey: .startTime)
        try c.encodeIfPresent(f.displayTime, forKey: .displayTime)
        try c.encodeIfPresent(f.cost, forKey: .cost)
        try c.encodeIfPresent(f.setLengthMin, forKey: .setLengthMin)
        try c.encodeIfPresent(f.signupMethod, forKey: .signupMethod)
        try c.encodeIfPresent(f.signupUrl, forKey: .signupUrl)
        try c.encodeIfPresent(f.signupNotes, forKey: .signupNotes)
        try c.encodeIfPresent(f.venueUrl, forKey: .venueUrl)
        try c.encodeIfPresent(f.instagram, forKey: .instagram)
        try c.encode(f.urgency, forKey: .urgency)
        try c.encodeIfPresent(f.urgencyNote, forKey: .urgencyNote)
        try c.encode(f.advanceDays, forKey: .advanceDays)
        try c.encode(f.isBiweekly, forKey: .isBiweekly)
        try c.encodeIfPresent(f.notes, forKey: .notes)
        try c.encodeIfPresent(f.latitude, forKey: .latitude)
        try c.encodeIfPresent(f.longitude, forKey: .longitude)
        try c.encode(true, forKey: .isActive)
    }
}

/// PATCH /rest/v1/open_mics?id=eq.X — every editable field is sent, with an
/// explicit null for cleared ones. Coordinates are only sent when
/// `includeCoordinates` is true (the address changed and was re-geocoded),
/// so editing a mic's notes never wipes its map pin.
struct MicUpdatePayload: Encodable {
    let fields: MicFields
    var includeCoordinates: Bool = false

    func encode(to encoder: Encoder) throws {
        let f = fields.normalized()
        var c = encoder.container(keyedBy: MicColumn.self)
        try c.encode(f.name, forKey: .name)
        try c.encode(f.venue, forKey: .venue)
        try c.encodeNullable(f.address, forKey: .address)
        try c.encodeNullable(f.neighborhood, forKey: .neighborhood)
        try c.encodeNullable(f.borough, forKey: .borough)
        try c.encode(f.dayOfWeek, forKey: .dayOfWeek)
        try c.encode(f.startTime, forKey: .startTime)
        try c.encodeNullable(f.displayTime, forKey: .displayTime)
        try c.encodeNullable(f.cost, forKey: .cost)
        try c.encodeNullable(f.setLengthMin, forKey: .setLengthMin)
        try c.encodeNullable(f.signupMethod, forKey: .signupMethod)
        try c.encodeNullable(f.signupUrl, forKey: .signupUrl)
        try c.encodeNullable(f.signupNotes, forKey: .signupNotes)
        try c.encodeNullable(f.venueUrl, forKey: .venueUrl)
        try c.encodeNullable(f.instagram, forKey: .instagram)
        try c.encode(f.urgency, forKey: .urgency)
        try c.encodeNullable(f.urgencyNote, forKey: .urgencyNote)
        try c.encode(f.advanceDays, forKey: .advanceDays)
        try c.encode(f.isBiweekly, forKey: .isBiweekly)
        try c.encodeNullable(f.notes, forKey: .notes)
        if includeCoordinates {
            try c.encodeNullable(f.latitude, forKey: .latitude)
            try c.encodeNullable(f.longitude, forKey: .longitude)
        }
    }
}

/// PATCH just `mic_rating` (Mic Detail's "Update rating").
struct MicRatingPayload: Encodable {
    let micRating: Double?

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: MicColumn.self)
        try c.encodeNullable(micRating, forKey: .micRating)
    }
}

/// PATCH just latitude/longitude (Manage → Geocoding).
struct CoordinatesPayload: Encodable {
    let latitude: Double?
    let longitude: Double?

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: MicColumn.self)
        try c.encodeNullable(latitude, forKey: .latitude)
        try c.encodeNullable(longitude, forKey: .longitude)
    }
}

// MARK: - Set

/// Every editable column of `my_sets`, as the Log a Set form edits them.
struct SetFields: Hashable, Sendable {
    var openMicId: Int
    var datePerformed: CalendarDate
    var setRating: Int?
    var crowdRating: Int?
    /// "empty" / "sparse" / "decent" / "packed".
    var crowdSize: String?
    var setList: String?
    var recordingUrl: String?
    /// "none" / "video" / "audio".
    var recordingType: String?
    var notes: String?
    var newMaterial: Bool
    var gotFeedback: Bool
    var feedbackNotes: String?
    var wouldReturn: Bool
    /// Tag names; saved comma-joined (rule 14), or null when empty.
    var tags: [String]

    /// Defaults match the web form: ratings 5, crowd "decent", recording
    /// "none", would return = yes.
    init(
        openMicId: Int,
        datePerformed: CalendarDate,
        setRating: Int? = 5,
        crowdRating: Int? = 5,
        crowdSize: String? = CrowdSize.decent.rawValue,
        setList: String? = nil,
        recordingUrl: String? = nil,
        recordingType: String? = RecordingType.noRecording.rawValue,
        notes: String? = nil,
        newMaterial: Bool = false,
        gotFeedback: Bool = false,
        feedbackNotes: String? = nil,
        wouldReturn: Bool = true,
        tags: [String] = []
    ) {
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

    /// Prefills the form from an existing row (Edit, or logging a planned set).
    /// A planned row has no ratings yet, so the web defaults (5/5/decent) fill in.
    init(set entry: SetEntry) {
        self.init(
            openMicId: entry.openMicId ?? 0,
            datePerformed: entry.datePerformed,
            setRating: entry.setRating ?? 5,
            crowdRating: entry.crowdRating ?? 5,
            crowdSize: entry.crowdSize ?? CrowdSize.decent.rawValue,
            setList: entry.setList,
            recordingUrl: entry.recordingUrl,
            recordingType: entry.recordingType ?? RecordingType.noRecording.rawValue,
            notes: entry.notes,
            newMaterial: entry.newMaterial,
            gotFeedback: entry.gotFeedback,
            feedbackNotes: entry.feedbackNotes,
            wouldReturn: entry.wouldReturn ?? true,
            tags: entry.tagList
        )
    }

    /// Trimmed copy: blank text → nil; feedback notes dropped unless
    /// "Got feedback?" is on (same as the web form).
    func normalized() -> SetFields {
        var copy = self
        copy.crowdSize = TextRules.trimmedOrNil(crowdSize)
        copy.setList = TextRules.trimmedOrNil(setList)
        copy.recordingUrl = TextRules.trimmedOrNil(recordingUrl)
        copy.recordingType = TextRules.trimmedOrNil(recordingType)
        copy.notes = TextRules.trimmedOrNil(notes)
        copy.feedbackNotes = gotFeedback ? TextRules.trimmedOrNil(feedbackNotes) : nil
        copy.tags = TagRules.parse(TagRules.join(tags))
        return copy
    }
}

/// The `my_sets` column names used by the payloads below.
enum SetColumn: String, CodingKey {
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

/// POST /rest/v1/my_sets — nil fields left out.
struct SetInsertPayload: Encodable {
    let fields: SetFields

    func encode(to encoder: Encoder) throws {
        let f = fields.normalized()
        var c = encoder.container(keyedBy: SetColumn.self)
        try c.encode(f.openMicId, forKey: .openMicId)
        try c.encode(f.datePerformed.isoString, forKey: .datePerformed)
        try c.encodeIfPresent(f.setRating, forKey: .setRating)
        try c.encodeIfPresent(f.crowdRating, forKey: .crowdRating)
        try c.encodeIfPresent(f.crowdSize, forKey: .crowdSize)
        try c.encodeIfPresent(f.setList, forKey: .setList)
        try c.encodeIfPresent(f.recordingUrl, forKey: .recordingUrl)
        try c.encodeIfPresent(f.recordingType, forKey: .recordingType)
        try c.encodeIfPresent(f.notes, forKey: .notes)
        try c.encode(f.newMaterial, forKey: .newMaterial)
        try c.encode(f.gotFeedback, forKey: .gotFeedback)
        try c.encodeIfPresent(f.feedbackNotes, forKey: .feedbackNotes)
        try c.encode(f.wouldReturn, forKey: .wouldReturn)
        try c.encodeIfPresent(TagRules.join(f.tags), forKey: .tags)
    }
}

/// PATCH /rest/v1/my_sets?id=eq.X — every field sent, explicit nulls.
struct SetUpdatePayload: Encodable {
    let fields: SetFields

    func encode(to encoder: Encoder) throws {
        let f = fields.normalized()
        var c = encoder.container(keyedBy: SetColumn.self)
        try c.encode(f.openMicId, forKey: .openMicId)
        try c.encode(f.datePerformed.isoString, forKey: .datePerformed)
        try c.encodeNullable(f.setRating, forKey: .setRating)
        try c.encodeNullable(f.crowdRating, forKey: .crowdRating)
        try c.encodeNullable(f.crowdSize, forKey: .crowdSize)
        try c.encodeNullable(f.setList, forKey: .setList)
        try c.encodeNullable(f.recordingUrl, forKey: .recordingUrl)
        try c.encodeNullable(f.recordingType, forKey: .recordingType)
        try c.encodeNullable(f.notes, forKey: .notes)
        try c.encode(f.newMaterial, forKey: .newMaterial)
        try c.encode(f.gotFeedback, forKey: .gotFeedback)
        try c.encodeNullable(f.feedbackNotes, forKey: .feedbackNotes)
        try c.encode(f.wouldReturn, forKey: .wouldReturn)
        try c.encodeNullable(TagRules.join(f.tags), forKey: .tags)
    }
}

// MARK: - Explicit nulls

extension KeyedEncodingContainer {
    /// Encodes the value, or an explicit JSON `null` when it's nil.
    mutating func encodeNullable<T: Encodable>(_ value: T?, forKey key: Key) throws {
        if let value = value {
            try encode(value, forKey: key)
        } else {
            try encodeNil(forKey: key)
        }
    }
}
