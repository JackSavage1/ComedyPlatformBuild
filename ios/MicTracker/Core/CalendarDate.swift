import Foundation

// =============================================================================
// CalendarDate — a plain calendar day (year, month, day) with NO time or zone.
//
// Why not just use `Date`? A `Date` is an instant in time. The database's
// `date` columns (date_performed, plan_date) are calendar days. Turning
// "2026-10-20" into a Date at UTC midnight and then showing it in New York
// would display Oct 19 — the classic off-by-one bug. Keeping days as
// (year, month, day) makes that impossible (spec 3.5 "Dates").
//
// All arithmetic (add days, weekday, ISO week) uses a "day number" (days since
// 1970-01-01) computed with pure integer math, so it never depends on time
// zones or daylight-saving changes. Conversion to/from `Date` uses a Gregorian
// calendar in the device's local time zone.
// =============================================================================
struct CalendarDate: Hashable, Comparable, Codable, Sendable, CustomStringConvertible {
    let year: Int
    let month: Int
    let day: Int

    // MARK: - Creating dates

    /// Creates a date. Out-of-range values roll over like a wall calendar:
    /// (2026, 13, 1) is 2027-01-01 and (2026, 2, 30) is 2026-03-02.
    init(year: Int, month: Int, day: Int) {
        let monthIndex = month - 1
        let normalizedYear = year + CalendarDate.floorDiv(monthIndex, 12)
        let normalizedMonth = CalendarDate.floorMod(monthIndex, 12) + 1
        let number = CalendarDate.daysFromCivil(year: normalizedYear, month: normalizedMonth, day: 1) + (day - 1)
        let civil = CalendarDate.civilFromDays(number)
        self.year = civil.year
        self.month = civil.month
        self.day = civil.day
    }

    /// Creates the date `dayNumber` days after 1970-01-01.
    init(dayNumber: Int) {
        let civil = CalendarDate.civilFromDays(dayNumber)
        self.year = civil.year
        self.month = civil.month
        self.day = civil.day
    }

    /// Parses "yyyy-MM-dd". Anything after the first 10 characters (like a
    /// time "T00:00:00") is ignored. Returns nil for invalid dates such as
    /// "2026-02-30" or "hello".
    init?(string: String?) {
        guard let raw = TextRules.trimmedOrNil(string), raw.count >= 10 else { return nil }
        let head = String(raw.prefix(10))
        let parts = head.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 3,
              parts[0].count == 4, parts[1].count == 2, parts[2].count == 2,
              let y = Int(parts[0]), let m = Int(parts[1]), let d = Int(parts[2]),
              (1...12).contains(m), d >= 1, d <= CalendarDate.daysInMonth(year: y, month: m)
        else { return nil }
        self.year = y
        self.month = m
        self.day = d
    }

    /// The calendar day that `date` falls on in `calendar`'s time zone
    /// (the device's local zone by default).
    init(date: Date, calendar: Calendar = CalendarDate.localCalendar) {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        self.init(year: parts.year ?? 1970, month: parts.month ?? 1, day: parts.day ?? 1)
    }

    /// Today, in the device's local time zone ("Today = device local date").
    static func today(calendar: Calendar = CalendarDate.localCalendar) -> CalendarDate {
        CalendarDate(date: Date(), calendar: calendar)
    }

    /// A Gregorian calendar in the device's current time zone. Built fresh on
    /// every call so a time-zone change while the app runs is picked up.
    static var localCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone.current
        calendar.locale = Locale(identifier: "en_US_POSIX")
        calendar.firstWeekday = 2 // Monday
        return calendar
    }

    // MARK: - Converting back to Date

    /// Midnight at the start of this day in `calendar`'s time zone. Use this
    /// for DatePicker bindings and anything else that needs a `Date`.
    func date(in calendar: Calendar = CalendarDate.localCalendar) -> Date {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = day
        parts.hour = 0
        parts.minute = 0
        parts.second = 0
        return calendar.date(from: parts) ?? Date(timeIntervalSince1970: TimeInterval(dayNumber) * 86_400)
    }

    // MARK: - Arithmetic

    /// Days since 1970-01-01 (negative before that).
    var dayNumber: Int {
        CalendarDate.daysFromCivil(year: year, month: month, day: day)
    }

    func adding(days: Int) -> CalendarDate {
        CalendarDate(dayNumber: dayNumber + days)
    }

    func adding(weeks: Int) -> CalendarDate {
        adding(days: weeks * 7)
    }

    /// Same day-of-month `months` later, clamped to the month's length
    /// (Jan 31 + 1 month = Feb 28/29).
    func adding(months: Int) -> CalendarDate {
        let total = (year * 12 + (month - 1)) + months
        let newYear = CalendarDate.floorDiv(total, 12)
        let newMonth = CalendarDate.floorMod(total, 12) + 1
        let newDay = min(day, CalendarDate.daysInMonth(year: newYear, month: newMonth))
        return CalendarDate(year: newYear, month: newMonth, day: newDay)
    }

    /// Whole days from `self` to `other` (positive when `other` is later).
    func days(until other: CalendarDate) -> Int {
        other.dayNumber - dayNumber
    }

    /// Day of the week. 1970-01-01 was a Thursday.
    var weekday: Weekday {
        let index = CalendarDate.floorMod(dayNumber + 3, 7) // Monday = 0
        return Weekday(rawValue: index + 1) ?? .monday
    }

    /// The Monday of this date's week (weeks start Monday).
    var startOfWeek: CalendarDate {
        adding(days: -(weekday.rawValue - 1))
    }

    /// The Sunday that ends this date's week.
    var endOfWeek: CalendarDate {
        startOfWeek.adding(days: 6)
    }

    var startOfMonth: CalendarDate {
        CalendarDate(year: year, month: month, day: 1)
    }

    /// 1 for January 1st.
    var dayOfYear: Int {
        dayNumber - CalendarDate.daysFromCivil(year: year, month: 1, day: 1) + 1
    }

    /// ISO-8601 week: weeks start Monday, and week 1 is the week containing
    /// the year's first Thursday. Dec 31, 2026 is in 2026-W53; Jan 1, 2021
    /// was in 2020-W53.
    var isoWeek: ISOWeek {
        let thursday = adding(days: 4 - weekday.rawValue)
        return ISOWeek(year: thursday.year, week: (thursday.dayOfYear - 1) / 7 + 1)
    }

    static func < (lhs: CalendarDate, rhs: CalendarDate) -> Bool {
        if lhs.year != rhs.year { return lhs.year < rhs.year }
        if lhs.month != rhs.month { return lhs.month < rhs.month }
        return lhs.day < rhs.day
    }

    // MARK: - Formatting (always English, independent of device language)

    /// "2026-10-20" — the format the database and RPCs use.
    var isoString: String {
        String(format: "%04d-%02d-%02d", year, month, day)
    }

    var description: String { isoString }

    /// "10/20"
    var mmdd: String { String(format: "%02d/%02d", month, day) }

    /// "10/20/2026"
    var mmddyyyy: String { String(format: "%02d/%02d/%04d", month, day, year) }

    /// "2026-10" — used to group stats by month.
    var monthKey: String { String(format: "%04d-%02d", year, month) }

    /// "October"
    var monthName: String { CalendarDate.monthNames[month - 1] }

    /// "Oct"
    var shortMonthName: String { String(monthName.prefix(3)) }

    /// "Monday, October 20, 2026"
    var longText: String { "\(weekday.name), \(monthName) \(day), \(year)" }

    /// "Oct 20"
    var shortText: String { "\(shortMonthName) \(day)" }

    /// "Oct 20, 2026"
    var shortTextWithYear: String { "\(shortMonthName) \(day), \(year)" }

    /// "Mon 10/20"
    var weekdayAndMMDD: String { "\(weekday.shortName) \(mmdd)" }

    static let monthNames = [
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    ]

    // MARK: - Codable — encoded as "yyyy-MM-dd"

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        let raw = try container.decode(String.self)
        guard let parsed = CalendarDate(string: raw) else {
            throw DecodingError.dataCorruptedError(
                in: container,
                debugDescription: "Expected a yyyy-MM-dd date, got \"\(raw)\""
            )
        }
        self = parsed
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(isoString)
    }

    // MARK: - Calendar math helpers (pure integer math)

    static func isLeapYear(_ year: Int) -> Bool {
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0
    }

    static func daysInMonth(year: Int, month: Int) -> Int {
        switch month {
        case 1, 3, 5, 7, 8, 10, 12: return 31
        case 4, 6, 9, 11: return 30
        case 2: return isLeapYear(year) ? 29 : 28
        default: return 30
        }
    }

    /// Days since 1970-01-01 for a proleptic Gregorian date
    /// (Howard Hinnant's "days_from_civil" algorithm).
    static func daysFromCivil(year: Int, month: Int, day: Int) -> Int {
        let y = month <= 2 ? year - 1 : year
        let era = floorDiv(y, 400)
        let yearOfEra = y - era * 400                                       // 0...399
        let shiftedMonth = month > 2 ? month - 3 : month + 9                 // March = 0
        let dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1              // 0...365
        let dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146_097 + dayOfEra - 719_468
    }

    /// Inverse of `daysFromCivil` ("civil_from_days").
    static func civilFromDays(_ dayNumber: Int) -> (year: Int, month: Int, day: Int) {
        let z = dayNumber + 719_468
        let era = floorDiv(z, 146_097)
        let dayOfEra = z - era * 146_097                                     // 0...146096
        let yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        let dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        let shiftedMonth = (5 * dayOfYear + 2) / 153                         // March = 0
        let day = dayOfYear - (153 * shiftedMonth + 2) / 5 + 1
        let month = shiftedMonth < 10 ? shiftedMonth + 3 : shiftedMonth - 9
        let year = yearOfEra + era * 400 + (month <= 2 ? 1 : 0)
        return (year, month, day)
    }

    /// Division that rounds toward negative infinity (-1 / 7 == -1).
    static func floorDiv(_ a: Int, _ b: Int) -> Int {
        let q = a / b
        return (a % b != 0 && ((a < 0) != (b < 0))) ? q - 1 : q
    }

    /// Modulo that is never negative for a positive divisor (-1 mod 7 == 6).
    static func floorMod(_ a: Int, _ b: Int) -> Int {
        let r = a % b
        return r < 0 ? r + b : r
    }
}

/// An ISO-8601 week, e.g. 2026-W43.
struct ISOWeek: Hashable, Comparable, Sendable, CustomStringConvertible {
    let year: Int
    let week: Int

    var description: String { String(format: "%04d-W%02d", year, week) }

    static func < (lhs: ISOWeek, rhs: ISOWeek) -> Bool {
        lhs.year != rhs.year ? lhs.year < rhs.year : lhs.week < rhs.week
    }
}
