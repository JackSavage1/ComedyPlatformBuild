import Foundation

// =============================================================================
// SetsLogic — the pure (no SwiftUI) rules behind the My Sets tab (spec 6.6,
// a port of pages/2_🎤_My_Sets.py).
//
// Everything here is plain Foundation code so it can be unit tested
// (MicTrackerTests/SetsTests.swift). The views in this folder only lay
// things out; the decisions — what the form starts with, which rows a
// filter keeps, what the summary says — live here.
// =============================================================================

// MARK: - Tab mode

/// The two halves of the My Sets tab (the segmented control).
enum SetsMode: String, CaseIterable, Identifiable, Hashable {
    case log
    case history

    var id: String { rawValue }

    var title: String {
        switch self {
        case .log: return "Log a Set"
        case .history: return "History"
        }
    }
}

// MARK: - The Log a Set form's state

/// Everything the "Log a Set" form edits, in the shape the controls want
/// (plain Strings for text fields, Ints for sliders, a Set for tag chips).
///
/// `makeFields(micID:)` turns it into the `SetFields` value that AppStore
/// saves; `SetFields` then handles "blank text → null" and the tag joining.
struct SetDraft: Hashable {
    /// nil until the user picks a mic.
    var micID: Int?
    var date: CalendarDate
    /// 1–10 (slider).
    var setRating: Int
    /// 1–10 (slider).
    var crowdRating: Int
    /// "empty" / "sparse" / "decent" / "packed" (or an unknown raw value
    /// from older data, kept so editing doesn't silently change it).
    var crowdSize: String
    var setList: String
    var newMaterial: Bool
    var recordingURL: String
    /// "none" / "video" / "audio".
    var recordingType: String
    var notes: String
    var gotFeedback: Bool
    var feedbackNotes: String
    var wouldReturn: Bool
    var tags: Set<String>

    /// A blank form with the web form's defaults: ratings 5, crowd "decent",
    /// no recording, would return = yes, date = today.
    init(today: CalendarDate) {
        micID = nil
        date = today
        setRating = 5
        crowdRating = 5
        crowdSize = CrowdSize.decent.rawValue
        setList = ""
        newMaterial = false
        recordingURL = ""
        recordingType = RecordingType.noRecording.rawValue
        notes = ""
        gotFeedback = false
        feedbackNotes = ""
        wouldReturn = true
        tags = []
    }

    /// A form prefilled from an existing row — a planned row ("Log: …") or a
    /// logged set (Set Detail → Edit). A planned row has no ratings yet, so
    /// the defaults fill in. The date is clamped to today because the date
    /// picker can't go past today (a "Going" plan may be for next week).
    init(entry: SetEntry, today: CalendarDate) {
        self.init(today: today)
        micID = entry.openMicId
        date = SetsLogic.clampedDate(entry.datePerformed, today: today)
        setRating = SetsLogic.clampedRating(entry.setRating)
        crowdRating = SetsLogic.clampedRating(entry.crowdRating)
        crowdSize = CrowdSize(raw: entry.crowdSize)?.rawValue ?? CrowdSize.decent.rawValue
        setList = entry.setList ?? ""
        newMaterial = entry.newMaterial
        recordingURL = entry.recordingUrl ?? ""
        recordingType = RecordingType(raw: entry.recordingType)?.rawValue ?? RecordingType.noRecording.rawValue
        notes = entry.notes ?? ""
        gotFeedback = entry.gotFeedback
        feedbackNotes = entry.feedbackNotes ?? ""
        wouldReturn = entry.wouldReturn ?? true
        tags = Set(entry.tagList)
    }

    /// The value AppStore saves. (`SetFields` trims text, turns blanks into
    /// null and drops feedback notes unless "Got feedback?" is on.)
    func makeFields(micID: Int) -> SetFields {
        SetFields(
            openMicId: micID,
            datePerformed: date,
            setRating: setRating,
            crowdRating: crowdRating,
            crowdSize: crowdSize,
            setList: setList,
            recordingUrl: recordingURL,
            recordingType: recordingType,
            notes: notes,
            newMaterial: newMaterial,
            gotFeedback: gotFeedback,
            feedbackNotes: feedbackNotes,
            wouldReturn: wouldReturn,
            tags: SetsLogic.orderedTags(tags)
        )
    }
}

// MARK: - History filters and summary

/// The History tab's filters (spec 6.6 → History → Filters).
struct SetHistoryFilter: Hashable {
    /// The mic name to show, or "" for All. (A String rather than an
    /// optional keeps the SwiftUI Picker's tags simple.)
    var micName: String = ""
    /// 1 = no filter. Planned rows are always shown (they have no rating yet).
    var minRating: Int = 1
    /// Any-of: a set matches when it has at least one of these tags.
    var tags: Set<String> = []

    /// How many filters are switched on (for the "Filters (2)" label).
    var activeCount: Int {
        var count = 0
        if !micName.isEmpty { count += 1 }
        if minRating > 1 { count += 1 }
        if !tags.isEmpty { count += 1 }
        return count
    }
}

/// The four numbers at the top of History.
struct SetHistorySummary: Hashable {
    /// Sets with a rating.
    let loggedCount: Int
    /// "Going" rows that haven't been logged yet.
    let plannedCount: Int
    /// nil when nothing is logged yet.
    let averageRating: Double?
    /// The mic with the most logged sets (rule 6 — planned rows don't count).
    let mostVisitedName: String?
    let mostVisitedCount: Int
    /// Rule 11.
    let weekStreak: Int

    /// "12" or "12 (+2 planned)"
    var totalText: String {
        plannedCount > 0 ? "\(loggedCount) (+\(plannedCount) planned)" : "\(loggedCount)"
    }

    /// "7.4/10" ("0.0/10" before the first logged set, like the web app).
    var averageText: String {
        String(format: "%.1f/10", averageRating ?? 0)
    }

    /// "The Creek (4×)" or "N/A"
    var mostVisitedText: String {
        guard let name = mostVisitedName else { return "N/A" }
        return "\(name) (\(mostVisitedCount)×)"
    }

    /// "3 weeks"
    var streakText: String {
        "\(weekStreak) weeks"
    }
}

// MARK: - Mic picker

/// One weekday's worth of mics in the searchable mic picker.
struct SetMicPickerSection: Identifiable, Hashable {
    let id: String
    let title: String
    let mics: [Mic]
}

// MARK: - Rules

enum SetsLogic {

    // MARK: Form

    /// The date picker's maximum is today, so a later date becomes today.
    static func clampedDate(_ date: CalendarDate, today: CalendarDate) -> CalendarDate {
        min(date, today)
    }

    /// Sliders run 1–10; nil (a planned row) becomes the default 5.
    static func clampedRating(_ rating: Int?) -> Int {
        guard let rating = rating else { return 5 }
        return min(10, max(1, rating))
    }

    /// Tags in the fixed option order (rule 14), then any unknown tags from
    /// older data alphabetically — so saving never reshuffles them.
    static func orderedTags(_ tags: Set<String>) -> [String] {
        let known = TagRules.options.filter { tags.contains($0) }
        let extras = tags.subtracting(TagRules.options).sorted()
        return known + extras
    }

    /// The tag chips to offer: the fixed options plus any unknown tags the
    /// set already has (so they can still be removed).
    static func tagOptions(including selected: Set<String>) -> [String] {
        TagRules.options + selected.subtracting(TagRules.options).sorted()
    }

    /// Crowd-size choices: the four standard ones, plus the current value if
    /// it's something else (older data).
    static func crowdSizeOptions(current: String) -> [String] {
        let standard = CrowdSize.standardCases.map { $0.rawValue }
        if standard.contains(current) || TextRules.isBlank(current) { return standard }
        return standard + [current]
    }

    /// Recording-type choices: none / video / audio, plus the current value
    /// if it's something else.
    static func recordingTypeOptions(current: String) -> [String] {
        let standard = RecordingType.standardCases.map { $0.rawValue }
        if standard.contains(current) || TextRules.isBlank(current) { return standard }
        return standard + [current]
    }

    /// "Decent", "None", "Video" — the label for a crowd-size or
    /// recording-type raw value.
    static func optionTitle(_ raw: String) -> String {
        TextRules.prettify(raw)
    }

    // MARK: Mic picker

    /// "Mon — 7:00 PM — Comedy Night @ The Creek" (blank parts are skipped).
    static func micPickerLabel(_ mic: Mic) -> String {
        let day = mic.weekday?.shortName ?? String(mic.dayOfWeek.prefix(3))
        let nameAndVenue = mic.venue.isEmpty ? mic.name : "\(mic.name) @ \(mic.venue)"
        return TextRules.joinNonBlank([day, mic.timeText, nameAndVenue], separator: " — ")
    }

    /// True when the search text is empty, or appears (case-insensitively)
    /// in the mic's picker label or neighborhood.
    static func matchesSearch(_ mic: Mic, query: String) -> Bool {
        guard let needle = TextRules.trimmedOrNil(query)?.lowercased() else { return true }
        if micPickerLabel(mic).lowercased().contains(needle) { return true }
        if let neighborhood = mic.neighborhood?.lowercased(), neighborhood.contains(needle) { return true }
        return false
    }

    /// The mic picker's sections: Monday → Sunday (days with no matching
    /// mics are left out), each sorted by start time; mics whose day can't
    /// be read go last under "Other".
    static func micPickerSections(_ mics: [Mic], search: String) -> [SetMicPickerSection] {
        let matching = mics.filter { matchesSearch($0, query: search) }
        var sections: [SetMicPickerSection] = []
        for day in Weekday.allCases {
            let dayMics = matching.filter { $0.weekday == day }
            if !dayMics.isEmpty {
                sections.append(SetMicPickerSection(id: day.name, title: day.name, mics: MicRules.sortedByStartTime(dayMics)))
            }
        }
        let unknownDay = matching.filter { $0.weekday == nil }
        if !unknownDay.isEmpty {
            sections.append(SetMicPickerSection(id: "other", title: "Other", mics: MicRules.sortedByStartTime(unknownDay)))
        }
        return sections
    }

    // MARK: Names

    /// The set's mic name, or "Unknown mic" (the mic row may be missing).
    static func micName(for entry: SetEntry, micsByID: [Int: Mic]) -> String {
        guard let id = entry.openMicId, let mic = micsByID[id] else { return "Unknown mic" }
        return mic.name
    }

    /// "Comedy Night @ The Creek" (or just the name when the venue is blank).
    static func micAndVenue(for entry: SetEntry, micsByID: [Int: Mic]) -> String {
        let name = micName(for: entry, micsByID: micsByID)
        guard let id = entry.openMicId, let venue = TextRules.trimmedOrNil(micsByID[id]?.venue) else { return name }
        return "\(name) @ \(venue)"
    }

    /// The planned-set button: "📝 Log: Comedy Night @ The Creek — 10/20/2026"
    static func plannedRowText(_ entry: SetEntry, micsByID: [Int: Mic]) -> String {
        "📝 Log: \(micAndVenue(for: entry, micsByID: micsByID)) — \(entry.datePerformed.mmddyyyy)"
    }

    // MARK: History

    /// History's one-line result: "Set: 8/10 · Crowd: 6/10", or the planned
    /// prompt. A missing crowd rating shows "?" like the web app.
    static func historyResultText(_ entry: SetEntry) -> String {
        guard let setRating = entry.setRating else { return "PLANNED — tap to log details" }
        let crowd = entry.crowdRating.map { String($0) } ?? "?"
        return "Set: \(setRating)/10 · Crowd: \(crowd)/10"
    }

    /// The summary row (rule 6 for Most Visited, rule 11 for the streak).
    static func historySummary(sets: [SetEntry], micsByID: [Int: Mic], today: CalendarDate) -> SetHistorySummary {
        let logged = sets.filter { $0.isLogged }
        let ratings = logged.compactMap { $0.setRating }.map { Double($0) }
        let average: Double? = ratings.isEmpty ? nil : SetRules.average(ratings)

        // Most visited: rule 6's logged-visit count per mic (the same number
        // as Mic Detail's "Been here N×"). Ties go to the alphabetically
        // first name so the tile never flickers between refreshes.
        var top: (name: String, count: Int)? = nil
        for (micID, count) in SetRules.loggedVisitCounts(sets) {
            guard let mic = micsByID[micID] else { continue }
            if let current = top {
                let isBetter = count > current.count
                    || (count == current.count && mic.name.lowercased() < current.name.lowercased())
                if isBetter { top = (mic.name, count) }
            } else {
                top = (mic.name, count)
            }
        }

        return SetHistorySummary(
            loggedCount: logged.count,
            plannedCount: sets.count - logged.count,
            averageRating: average,
            mostVisitedName: top?.name,
            mostVisitedCount: top?.count ?? 0,
            weekStreak: SetRules.weekStreak(loggedDates: logged.map { $0.datePerformed }, today: today)
        )
    }

    /// The mic-filter options: names of the mics that appear in the sets
    /// (logged or planned), sorted. "All" is added by the view.
    static func historyMicNames(sets: [SetEntry], micsByID: [Int: Mic]) -> [String] {
        var names = Set<String>()
        for entry in sets {
            if let id = entry.openMicId, let mic = micsByID[id] {
                names.insert(mic.name)
            }
        }
        return names.sorted { $0.lowercased() < $1.lowercased() }
    }

    /// The tag-filter options: every tag used on any set, sorted.
    static func historyTagOptions(sets: [SetEntry]) -> [String] {
        var tags = Set<String>()
        for entry in sets {
            tags.formUnion(entry.tagList)
        }
        return tags.sorted()
    }

    /// Applies History's filters, keeping the incoming (newest-first) order.
    ///
    /// * mic: exact name match ("" = all). A name that no longer appears in
    ///   the data is ignored rather than hiding everything.
    /// * min rating: logged sets need `set_rating >= minRating`; planned rows
    ///   always pass.
    /// * tags: any-of; a set with no tags fails once any tag is selected.
    static func filteredHistory(sets: [SetEntry], micsByID: [Int: Mic], filter: SetHistoryFilter) -> [SetEntry] {
        let knownNames = Set(historyMicNames(sets: sets, micsByID: micsByID))
        let micName = knownNames.contains(filter.micName) ? filter.micName : ""

        return sets.filter { entry in
            if !micName.isEmpty {
                guard let id = entry.openMicId, micsByID[id]?.name == micName else { return false }
            }
            if let rating = entry.setRating, rating < filter.minRating {
                return false
            }
            if !filter.tags.isEmpty {
                let entryTags = Set(entry.tagList)
                if entryTags.isDisjoint(with: filter.tags) { return false }
            }
            return true
        }
    }
}
