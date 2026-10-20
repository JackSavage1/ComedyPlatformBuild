import XCTest
@testable import MicTracker

// Tests for the Mic Form's time-picker glue (spec 6.9, rule 19).
final class MicFormTests: XCTestCase {

    private func fields(start: String, display: String?) -> MicFields {
        var fields = MicFields()
        fields.startTime = start
        fields.displayTime = display
        return fields
    }

    func testPickingATimeSetsBothColumns() {
        let updated = MicFormTime.applying(hour: 19, minute: 0, to: fields(start: "", display: nil))
        XCTAssertEqual(updated.startTime, "19:00")
        XCTAssertEqual(updated.displayTime, "7:00 PM")
    }

    func testAutoDisplayTimeFollowsThePicker() {
        let updated = MicFormTime.applying(hour: 21, minute: 30, to: fields(start: "19:00", display: "7:00 PM"))
        XCTAssertEqual(updated.startTime, "21:30")
        XCTAssertEqual(updated.displayTime, "9:30 PM")
    }

    func testHandTypedDisplayTimeIsKept() {
        let updated = MicFormTime.applying(hour: 21, minute: 30, to: fields(start: "19:00", display: "7pm sharp"))
        XCTAssertEqual(updated.startTime, "21:30")
        XCTAssertEqual(updated.displayTime, "7pm sharp")
    }

    func testBlankDisplayTimeIsFilledIn() {
        let updated = MicFormTime.applying(hour: 0, minute: 5, to: fields(start: "19:00", display: "   "))
        XCTAssertEqual(updated.startTime, "00:05")
        XCTAssertEqual(updated.displayTime, "12:05 AM")
    }

    func testPickerComponentsFallBackToSevenPM() {
        XCTAssertEqual(MicFormTime.pickerComponents(for: "18:45").hour, 18)
        XCTAssertEqual(MicFormTime.pickerComponents(for: "18:45").minute, 45)
        XCTAssertEqual(MicFormTime.pickerComponents(for: "7ish").hour, 19)
        XCTAssertEqual(MicFormTime.pickerComponents(for: nil).minute, 0)
    }

    func testPickerDateRoundTrips() {
        let date = MicFormTime.pickerDate(hour: 20, minute: 15)
        let parts = MicFormTime.hourAndMinute(of: date)
        XCTAssertEqual(parts.hour, 20)
        XCTAssertEqual(parts.minute, 15)
    }

    func testUnreadableStartTimeDetection() {
        XCTAssertTrue(MicFormTime.isUnreadable("7ish"))
        XCTAssertFalse(MicFormTime.isUnreadable("19:00"))
        XCTAssertFalse(MicFormTime.isUnreadable(""))
        XCTAssertFalse(MicFormTime.isUnreadable(nil))
    }

    func testNewMicDefaultsPassValidationOnceNamed() {
        var fields = MicFormTime.newMicFields()
        XCTAssertEqual(fields.startTime, "19:00")
        XCTAssertEqual(fields.displayTime, "7:00 PM")
        XCTAssertEqual(fields.borough, "Manhattan")
        XCTAssertEqual(fields.dayOfWeek, "Monday")
        XCTAssertEqual(fields.signupMethod, "in_person")
        XCTAssertEqual(fields.validationError, "Name, Venue, Day of Week, and Start Time are required!")
        fields.name = "Comedy Night"
        fields.venue = "The Club"
        XCTAssertNil(fields.validationError)
    }
}
