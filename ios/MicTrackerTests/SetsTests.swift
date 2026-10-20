import XCTest
@testable import MicTracker

// =============================================================================
// Tests for the My Sets tab's pure logic (Features/Sets/SetsLogic.swift,
// spec 6.6). 2026-10-20 is a Tuesday.
// =============================================================================
final class SetsTests: XCTestCase {

    // MARK: - Helpers

    private func day(_ year: Int, _ month: Int, _ day: Int) -> CalendarDate {
        CalendarDate(year: year, month: month, day: day)
    }

    private let today = CalendarDate(year: 2026, month: 10, day: 20)

    private let creek = Mic(id: 1, name: "Creek Mic", venue: "The Creek", neighborhood: "LIC", dayOfWeek: "Monday", startTime: "19:00", displayTime: "7:00 PM")
    private let gutter = Mic(id: 2, name: "Gutter Mic", venue: "The Gutter", dayOfWeek: "Wednesday", startTime: "20:30")
    private let early = Mic(id: 3, name: "Early Show", venue: "", dayOfWeek: "Monday", startTime: "17:00")
    private let mystery = Mic(id: 4, name: "Mystery Mic", venue: "Somewhere", dayOfWeek: "Funday", startTime: "21:00")

    private var micsByID: [Int: Mic] {
        Dictionary(uniqueKeysWithValues: [creek, gutter, early, mystery].map { ($0.id, $0) })
    }

    // MARK: - Draft

    func testBlankDraftUsesWebDefaults() {
        let draft = SetDraft(today: today)
        XCTAssertNil(draft.micID)
        XCTAssertEqual(draft.date, today)
        XCTAssertEqual(draft.setRating, 5)
        XCTAssertEqual(draft.crowdRating, 5)
        XCTAssertEqual(draft.crowdSize, "decent")
        XCTAssertEqual(draft.recordingType, "none")
        XCTAssertTrue(draft.wouldReturn)
        XCTAssertFalse(draft.gotFeedback)
        XCTAssertTrue(draft.tags.isEmpty)
    }

    func testPlannedRowPrefillClampsFutureDateAndUsesDefaults() {
        let planned = SetEntry(id: 9, openMicId: 2, datePerformed: day(2026, 10, 28), setRating: nil, wouldReturn: nil)
        let draft = SetDraft(entry: planned, today: today)
        XCTAssertEqual(draft.micID, 2)
        XCTAssertEqual(draft.date, today, "a future planned date is clamped to today")
        XCTAssertEqual(draft.setRating, 5)
        XCTAssertEqual(draft.crowdRating, 5)
        XCTAssertEqual(draft.crowdSize, "decent")
        XCTAssertTrue(draft.wouldReturn)
    }

    func testLoggedRowPrefillKeepsValues() {
        let logged = SetEntry(
            id: 5, openMicId: 1, datePerformed: day(2026, 10, 12),
            setRating: 9, crowdRating: 4, crowdSize: "PACKED",
            setList: "1. Subway", recordingUrl: "youtu.be/x", recordingType: "video",
            notes: "Killed", newMaterial: true, gotFeedback: true, feedbackNotes: "Tighten it",
            wouldReturn: false, tags: "killed, new_bit"
        )
        let draft = SetDraft(entry: logged, today: today)
        XCTAssertEqual(draft.date, day(2026, 10, 12))
        XCTAssertEqual(draft.setRating, 9)
        XCTAssertEqual(draft.crowdRating, 4)
        XCTAssertEqual(draft.crowdSize, "packed")
        XCTAssertEqual(draft.recordingType, "video")
        XCTAssertFalse(draft.wouldReturn)
        XCTAssertEqual(draft.tags, ["killed", "new_bit"])
    }

    func testMakeFieldsOrdersTagsAndNormalizesBlanks() {
        var draft = SetDraft(today: today)
        draft.tags = ["confident", "zzz_custom", "killed"]
        draft.setList = "   "
        draft.feedbackNotes = "ignored because got_feedback is off"
        let fields = draft.makeFields(micID: 1).normalized()
        XCTAssertEqual(fields.openMicId, 1)
        XCTAssertEqual(fields.tags, ["killed", "confident", "zzz_custom"])
        XCTAssertNil(fields.setList)
        XCTAssertNil(fields.feedbackNotes)
        XCTAssertEqual(fields.setRating, 5)
    }

    func testClampHelpers() {
        XCTAssertEqual(SetsLogic.clampedDate(day(2026, 11, 1), today: today), today)
        XCTAssertEqual(SetsLogic.clampedDate(day(2026, 10, 1), today: today), day(2026, 10, 1))
        XCTAssertEqual(SetsLogic.clampedRating(nil), 5)
        XCTAssertEqual(SetsLogic.clampedRating(0), 1)
        XCTAssertEqual(SetsLogic.clampedRating(12), 10)
        XCTAssertEqual(SetsLogic.clampedRating(7), 7)
    }

    func testOptionListsKeepUnknownCurrentValue() {
        XCTAssertEqual(SetsLogic.crowdSizeOptions(current: "decent"), ["empty", "sparse", "decent", "packed"])
        XCTAssertEqual(SetsLogic.crowdSizeOptions(current: "rowdy"), ["empty", "sparse", "decent", "packed", "rowdy"])
        XCTAssertEqual(SetsLogic.recordingTypeOptions(current: "none"), ["none", "video", "audio"])
        XCTAssertEqual(SetsLogic.tagOptions(including: ["killed"]), TagRules.options)
        XCTAssertEqual(SetsLogic.tagOptions(including: ["weird"]).last, "weird")
    }

    // MARK: - Mic picker

    func testMicPickerLabel() {
        XCTAssertEqual(SetsLogic.micPickerLabel(creek), "Mon — 7:00 PM — Creek Mic @ The Creek")
        XCTAssertEqual(SetsLogic.micPickerLabel(early), "Mon — 17:00 — Early Show")
    }

    func testMicPickerSectionsGroupByDaySortedByTime() {
        let sections = SetsLogic.micPickerSections([gutter, creek, mystery, early], search: "")
        XCTAssertEqual(sections.map { $0.title }, ["Monday", "Wednesday", "Other"])
        XCTAssertEqual(sections[0].mics.map { $0.id }, [3, 1], "Monday sorted by start time")
    }

    func testMicPickerSearch() {
        let sections = SetsLogic.micPickerSections([gutter, creek, mystery, early], search: "  gutter ")
        XCTAssertEqual(sections.count, 1)
        XCTAssertEqual(sections[0].mics.map { $0.id }, [2])
        // Neighborhood matches too.
        XCTAssertEqual(SetsLogic.micPickerSections([creek, gutter], search: "lic").first?.mics.map { $0.id }, [1])
    }

    // MARK: - Text

    func testPlannedRowTextAndHistoryResult() {
        let planned = SetEntry(id: 1, openMicId: 1, datePerformed: day(2026, 10, 20))
        XCTAssertEqual(SetsLogic.plannedRowText(planned, micsByID: micsByID), "📝 Log: Creek Mic @ The Creek — 10/20/2026")
        XCTAssertEqual(SetsLogic.historyResultText(planned), "PLANNED — tap to log details")

        let logged = SetEntry(id: 2, openMicId: 1, datePerformed: today, setRating: 8, crowdRating: 6)
        XCTAssertEqual(SetsLogic.historyResultText(logged), "Set: 8/10 · Crowd: 6/10")

        let noCrowd = SetEntry(id: 3, openMicId: 1, datePerformed: today, setRating: 3, crowdRating: nil)
        XCTAssertEqual(SetsLogic.historyResultText(noCrowd), "Set: 3/10 · Crowd: ?/10")

        let orphan = SetEntry(id: 4, openMicId: 99, datePerformed: today, setRating: 5)
        XCTAssertEqual(SetsLogic.micAndVenue(for: orphan, micsByID: micsByID), "Unknown mic")
    }

    // MARK: - History summary

    func testHistorySummaryCountsLoggedAndPlannedSeparately() {
        let sets = [
            SetEntry(id: 1, openMicId: 1, datePerformed: day(2026, 10, 19), setRating: 8),
            SetEntry(id: 2, openMicId: 1, datePerformed: day(2026, 10, 13), setRating: 6),
            SetEntry(id: 3, openMicId: 2, datePerformed: day(2026, 10, 6), setRating: 7),
            // Planned rows don't count as visits (rule 6 FIX).
            SetEntry(id: 4, openMicId: 2, datePerformed: day(2026, 10, 22)),
            SetEntry(id: 5, openMicId: 2, datePerformed: day(2026, 10, 23)),
        ]
        let summary = SetsLogic.historySummary(sets: sets, micsByID: micsByID, today: today)
        XCTAssertEqual(summary.loggedCount, 3)
        XCTAssertEqual(summary.plannedCount, 2)
        XCTAssertEqual(summary.totalText, "3 (+2 planned)")
        XCTAssertEqual(summary.averageText, "7.0/10")
        XCTAssertEqual(summary.mostVisitedText, "Creek Mic (2×)")
        XCTAssertEqual(summary.weekStreak, 3)
        XCTAssertEqual(summary.streakText, "3 weeks")
    }

    func testHistorySummaryWithOnlyPlannedRows() {
        let sets = [SetEntry(id: 1, openMicId: 1, datePerformed: today)]
        let summary = SetsLogic.historySummary(sets: sets, micsByID: micsByID, today: today)
        XCTAssertEqual(summary.totalText, "0 (+1 planned)")
        XCTAssertEqual(summary.averageText, "0.0/10")
        XCTAssertEqual(summary.mostVisitedText, "N/A")
        XCTAssertEqual(summary.weekStreak, 0)
    }

    // MARK: - History filters

    func testFilterOptions() {
        let sets = [
            SetEntry(id: 1, openMicId: 2, datePerformed: today, setRating: 8, tags: "killed,crowd_work"),
            SetEntry(id: 2, openMicId: 1, datePerformed: today, setRating: 4, tags: "bombed"),
            SetEntry(id: 3, openMicId: 99, datePerformed: today, setRating: 4),
        ]
        XCTAssertEqual(SetsLogic.historyMicNames(sets: sets, micsByID: micsByID), ["Creek Mic", "Gutter Mic"])
        XCTAssertEqual(SetsLogic.historyTagOptions(sets: sets), ["bombed", "crowd_work", "killed"])
    }

    func testFilterByMicRatingAndTags() {
        let sets = [
            SetEntry(id: 1, openMicId: 1, datePerformed: today, setRating: 9, tags: "killed"),
            SetEntry(id: 2, openMicId: 1, datePerformed: today, setRating: 3, tags: "bombed,nervous"),
            SetEntry(id: 3, openMicId: 2, datePerformed: today, setRating: 7),
            SetEntry(id: 4, openMicId: 1, datePerformed: today), // planned
        ]

        var filter = SetHistoryFilter()
        XCTAssertEqual(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter).map { $0.id }, [1, 2, 3, 4])

        filter.micName = "Creek Mic"
        XCTAssertEqual(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter).map { $0.id }, [1, 2, 4])

        filter.minRating = 5
        XCTAssertEqual(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter).map { $0.id }, [1, 4],
                       "planned rows always pass the rating filter")

        filter = SetHistoryFilter(micName: "", minRating: 1, tags: ["nervous", "killed"])
        XCTAssertEqual(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter).map { $0.id }, [1, 2])
        XCTAssertEqual(filter.activeCount, 1)
    }

    func testUnknownMicFilterIsIgnored() {
        let sets = [SetEntry(id: 1, openMicId: 1, datePerformed: today, setRating: 9)]
        let filter = SetHistoryFilter(micName: "Deleted Mic", minRating: 1, tags: [])
        XCTAssertEqual(SetsLogic.filteredHistory(sets: sets, micsByID: micsByID, filter: filter).map { $0.id }, [1])
    }
}
