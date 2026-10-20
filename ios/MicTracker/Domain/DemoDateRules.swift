import Foundation

// =============================================================================
// Demo date rules (spec 8.2) — how the bundled demo data gets real dates.
//
// DemoData.json stores dates RELATIVE to today ("weeks_ago": 2) so the demo
// always looks current. When demo mode starts, every relative date becomes a
// real one using the mic's `day_of_week` (so a Thursday mic's sets land on
// Thursdays):
//
//   past      the weekday's most recent date STRICTLY BEFORE today,
//             minus 7 × weeks_ago            → sets[], plans[] "when": "past"
//   upcoming  the weekday's next date ON OR AFTER today (rule 16),
//             plus 7 × weeks_ahead           → plans[] "when": "upcoming"
//   scrape    today minus days_ago, at hour:minute local time → scrape_log[]
//
// Pure functions of `today`, so they're easy to unit test.
// =============================================================================
enum DemoDateRules {

    /// The helper keys DemoData.json uses instead of real dates. They are
    /// dropped once the dates are resolved.
    static let helperKeys = ["weeks_ago", "weeks_ahead", "when", "days_ago", "hour", "minute"]

    /// The most recent `weekday` strictly before `today`, minus `weeksAgo`
    /// weeks. On a Tuesday, a Monday mic with weeks_ago 0 is yesterday, and a
    /// Tuesday mic with weeks_ago 0 is a week ago (never today).
    static func pastDate(on weekday: Weekday, weeksAgo: Int, today: CalendarDate) -> CalendarDate {
        // How many days back the previous such weekday is: 1…7.
        let daysBack = CalendarDate.floorMod(today.weekday.rawValue - weekday.rawValue - 1, 7) + 1
        return today.adding(days: -daysBack).adding(weeks: -weeksAgo)
    }

    /// The next `weekday` on or after `today` (rule 16), plus `weeksAhead`
    /// weeks.
    static func upcomingDate(on weekday: Weekday, weeksAhead: Int, today: CalendarDate) -> CalendarDate {
        ScheduleRules.nextOccurrence(of: weekday, from: today).adding(weeks: weeksAhead)
    }

    /// `today` minus `daysAgo` days, at `hour:minute` in `calendar`'s time
    /// zone (the device's local zone in the app).
    static func scrapeTime(
        daysAgo: Int,
        hour: Int,
        minute: Int,
        today: CalendarDate,
        calendar: Calendar = CalendarDate.localCalendar
    ) -> Date {
        let day = today.adding(days: -daysAgo)
        var parts = DateComponents()
        parts.year = day.year
        parts.month = day.month
        parts.day = day.day
        parts.hour = min(max(hour, 0), 23)
        parts.minute = min(max(minute, 0), 59)
        return calendar.date(from: parts) ?? day.date(in: calendar)
    }
}
