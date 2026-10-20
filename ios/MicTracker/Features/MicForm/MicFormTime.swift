import Foundation

// =============================================================================
// MicFormTime — glue between the Mic Form's native time picker and the two
// time columns (spec 6.9, rule 19). Pure Foundation, unit tested.
//
//   start_time   "19:00"    zero-padded 24-hour HH:mm (what the DB sorts by)
//   display_time "7:00 PM"  what people read; auto-filled from the picker,
//                           but the user may type their own ("7pm sharp").
//
// The display time follows the picker only while it still "belongs" to the
// picker — i.e. it's blank or equals the auto text for the previous time.
// A hand-typed display time is never overwritten.
// =============================================================================
enum MicFormTime {
    /// New mics start at 7:00 PM — most NYC mics are evening shows.
    static let defaultHour = 19
    static let defaultMinute = 0

    /// The hour/minute the picker should show for a stored start time,
    /// falling back to 7:00 PM when it's blank or unreadable.
    static func pickerComponents(for startTime: String?) -> (hour: Int, minute: Int) {
        if let parsed = TimeRules.hourAndMinute(from: startTime) {
            return (hour: parsed.hour, minute: parsed.minute)
        }
        return (hour: defaultHour, minute: defaultMinute)
    }

    /// A `Date` (today, local time) at `hour:minute`, for the DatePicker.
    static func pickerDate(
        hour: Int,
        minute: Int,
        calendar: Calendar = CalendarDate.localCalendar,
        reference: Date = Date()
    ) -> Date {
        calendar.date(bySettingHour: hour, minute: minute, second: 0, of: reference) ?? reference
    }

    /// The hour and minute the DatePicker produced.
    static func hourAndMinute(of date: Date, calendar: Calendar = CalendarDate.localCalendar) -> (hour: Int, minute: Int) {
        let parts = calendar.dateComponents([.hour, .minute], from: date)
        return (hour: parts.hour ?? defaultHour, minute: parts.minute ?? defaultMinute)
    }

    /// `fields` with a newly picked time applied: `startTime` becomes "HH:mm"
    /// and `displayTime` follows along unless the user typed their own.
    static func applying(hour: Int, minute: Int, to fields: MicFields) -> MicFields {
        var updated = fields
        let currentDisplay = TextRules.trimmedOrNil(fields.displayTime)
        let previousAutoDisplay: String? = TimeRules.hourAndMinute(from: fields.startTime).map { parsed in
            TimeRules.displayTimeString(hour: parsed.hour, minute: parsed.minute)
        }

        updated.startTime = TimeRules.startTimeString(hour: hour, minute: minute)
        if currentDisplay == nil || currentDisplay == previousAutoDisplay {
            updated.displayTime = TimeRules.displayTimeString(hour: hour, minute: minute)
        }
        return updated
    }

    /// True when a stored start time exists but can't be read (scraper junk
    /// like "7ish"); the form explains that picking a time replaces it.
    static func isUnreadable(_ startTime: String?) -> Bool {
        TextRules.trimmedOrNil(startTime) != nil && TimeRules.hourAndMinute(from: startTime) == nil
    }

    /// Defaults for "Add a Mic", matching the web form where it had defaults
    /// (Manhattan, Monday, in-person signup, normal urgency) plus a 7:00 PM
    /// start so the required time is always filled.
    static func newMicFields() -> MicFields {
        var fields = MicFields()
        fields.borough = BoroughRules.all.first
        fields.dayOfWeek = Weekday.monday.name
        fields.signupMethod = SignupMethod.inPerson.rawValue
        fields.urgency = Urgency.normal.rawValue
        fields.startTime = TimeRules.startTimeString(hour: defaultHour, minute: defaultMinute)
        fields.displayTime = TimeRules.displayTimeString(hour: defaultHour, minute: defaultMinute)
        return fields
    }
}
