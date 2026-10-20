import Foundation
import Observation

// =============================================================================
// AppStore — the app's single source of truth.
//
// It holds everything loaded from Supabase (mics, sets, plans, the scraper
// queue) and owns every write the app can make (spec 3.3–3.4). Screens read
// it with `@Environment(AppStore.self) private var store` and call its
// methods; because the class is `@Observable`, SwiftUI re-renders exactly
// the views that read a property when that property changes.
//
// Conventions for screens:
//   * Mutations are `async`, return `true` / a value on success, and on
//     failure put a user-facing message in `errorMessage` (MainTabView shows
//     it as a dismissible banner) and return `false` / nil.
//   * After every successful write the affected data is re-fetched.
//   * `isLoading` is true during `refreshAll()`, `isBusy` during writes.
// =============================================================================

/// Live progress of "Geocode with device" (Manage → Geocoding).
struct GeocodeProgress: Hashable, Sendable {
    var total: Int
    var done: Int
    var succeeded: Int
    var currentMicName: String?
}

/// Result of "Geocode with device".
struct GeocodeSummary: Hashable, Sendable {
    var attempted: Int
    var geocoded: Int

    /// "Geocoded 3 of 5"
    var message: String { "Geocoded \(geocoded) of \(attempted)" }
}

@Observable
@MainActor
final class AppStore {

    // MARK: - Services

    let config: AppConfig
    let auth: AuthService
    let api: APIClient
    let geocoder: GeocodingService

    // MARK: - Data

    /// Every mic, including deactivated ones (set history needs their names).
    private(set) var mics: [Mic] = []
    /// `is_active != false`, sorted Monday→Sunday then by start time.
    private(set) var activeMics: [Mic] = []
    /// Every mic by id (including inactive).
    private(set) var micsByID: [Int: Mic] = [:]
    /// All `my_sets` rows, newest first (logged AND planned).
    private(set) var sets: [SetEntry] = []
    /// Logged-set count per mic id (rule 6).
    private(set) var loggedVisitCounts: [Int: Int] = [:]
    /// Plans for every date window loaded so far (see `loadPlans`).
    private(set) var plans: [MicPlan] = []
    /// Pending scraper listings (Manage → Import).
    private(set) var scrapedMics: [ScrapedMic] = []
    /// Scraper run history, newest first.
    private(set) var scrapeLog: [ScrapeLogEntry] = []

    // MARK: - Status

    /// True while `refreshAll()` runs.
    private(set) var isLoading = false
    /// True once the first `refreshAll()` succeeded.
    private(set) var hasLoadedOnce = false
    private(set) var lastRefreshed: Date?
    private(set) var isLoadingScraped = false
    private(set) var isLoadingScrapeLog = false
    /// Non-nil while "Geocode with device" runs.
    private(set) var geocodeProgress: GeocodeProgress?
    /// Number of writes in flight.
    private(set) var busyCount = 0
    /// True while any write is in flight.
    var isBusy: Bool { busyCount > 0 }
    /// The latest user-facing error. Set it to nil (or call `clearError()`)
    /// to dismiss the banner.
    var errorMessage: String?

    /// Calendar days whose plans have been fetched.
    @ObservationIgnored private var loadedPlanDays: Set<CalendarDate> = []

    // MARK: - Init

    init(config: AppConfig = AppConfig.fromBundle()) {
        self.config = config
        let authService = AuthService(config: config)
        self.auth = authService
        self.api = APIClient(config: config, auth: authService)
        self.geocoder = GeocodingService()
    }

    // MARK: - Auth (thin pass-throughs so screens only need the store)

    var authState: AuthState { auth.state }

    func bootstrap() async {
        await auth.bootstrap()
    }

    func signIn(email: String, password: String) async {
        // Drop a banner left over from the previous session (e.g. the
        // "JWT expired" that signed us out).
        errorMessage = nil
        await auth.signIn(email: email, password: password)
    }

    func signOut() async {
        await auth.signOut()
        clearData()
    }

    /// Re-runs the `is_app_admin` check (the Not Authorized screen's Retry).
    func checkAdmin() async {
        await auth.checkAccess()
    }

    // MARK: - Lookups

    func mic(id: Int?) -> Mic? {
        guard let id = id else { return nil }
        return micsByID[id]
    }

    /// Active mics on a weekday, sorted by start time (rule: sort within a day).
    func activeMicsOnDay(_ weekday: Weekday) -> [Mic] {
        MicRules.sortedByStartTime(activeMics.filter { $0.weekday == weekday })
    }

    /// Going / Cancelled for a mic on a date (nil = no plan, or not loaded).
    func planStatus(micID: Int, on date: CalendarDate) -> PlanStatus? {
        plans.first { $0.openMicId == micID && $0.planDate == date }?.status
    }

    /// Mic ids marked Going on any date in `start...end`.
    func goingMicIDs(from start: CalendarDate, through end: CalendarDate) -> Set<Int> {
        Set(plans.filter { $0.status == .going && $0.planDate >= start && $0.planDate <= end }.map { $0.openMicId })
    }

    /// Rule 6: number of logged sets at this mic.
    func loggedVisitCount(micID: Int) -> Int {
        loggedVisitCounts[micID] ?? 0
    }

    /// Sets with a rating (real performances), newest first.
    var loggedSets: [SetEntry] { sets.filter { $0.isLogged } }

    /// Planned rows ("Going" but not logged yet), oldest first.
    var plannedSets: [SetEntry] {
        sets.filter { $0.isPlanned }.sorted { lhs, rhs in
            lhs.datePerformed != rhs.datePerformed ? lhs.datePerformed < rhs.datePerformed : lhs.id < rhs.id
        }
    }

    /// The mic a set was performed at (may be inactive).
    func mic(for entry: SetEntry) -> Mic? {
        mic(id: entry.openMicId)
    }

    /// "Mic Name" for a set, or "Unknown mic".
    func micName(for entry: SetEntry) -> String {
        mic(for: entry)?.name ?? "Unknown mic"
    }

    /// Active mics with no latitude/longitude yet.
    var micsMissingCoordinates: [Mic] {
        activeMics.filter { !$0.hasCoordinates }
    }

    /// Distinct non-blank neighborhoods of active mics, sorted.
    var activeNeighborhoods: [String] {
        Array(Set(activeMics.compactMap { TextRules.trimmedOrNil($0.neighborhood) })).sorted()
    }

    // MARK: - Loading

    /// Loads mics, sets and the default plans window (last week … 3 weeks
    /// out, the Calendar's range). Call on appear and on pull-to-refresh.
    func refreshAll() async {
        guard config.isConfigured else { return }
        isLoading = true
        defer { isLoading = false }

        let window = AppStore.defaultPlanWindow(today: CalendarDate.today())
        do {
            async let micsRequest: [Mic] = fetchMics()
            async let setsRequest: [SetEntry] = fetchSets()
            async let plansRequest: [MicPlan] = fetchPlans(from: window.start, through: window.end)
            let (newMics, newSets, newPlans) = try await (micsRequest, setsRequest, plansRequest)

            apply(mics: newMics)
            apply(sets: newSets)
            replacePlans(newPlans, from: window.start, through: window.end)
            hasLoadedOnce = true
            lastRefreshed = Date()
        } catch {
            report(error)
        }
    }

    /// Loads (and merges in) the plans for `start...end`.
    func loadPlans(from start: CalendarDate, to end: CalendarDate) async {
        do {
            let fetched = try await fetchPlans(from: start, through: end)
            replacePlans(fetched, from: start, through: end)
        } catch {
            report(error)
        }
    }

    /// Loads the plans for `date` unless they're already loaded (Mic Detail
    /// calls this for its date context).
    func ensurePlansLoaded(for date: CalendarDate) async {
        if !loadedPlanDays.contains(date) {
            await loadPlans(from: date, to: date)
        }
    }

    /// Pending scraper listings, grouped-friendly order (source, day, name).
    func loadScraped() async {
        guard config.isConfigured else { return }
        isLoadingScraped = true
        defer { isLoadingScraped = false }
        do {
            let rows: [ScrapedMic] = try await api.select(
                "scraped_mics",
                query: [
                    URLQueryItem(name: "status", value: "eq.pending"),
                    URLQueryItem(name: "order", value: "source.asc,name.asc"),
                ]
            )
            scrapedMics = rows.sorted { lhs, rhs in
                if lhs.source != rhs.source { return lhs.source < rhs.source }
                let leftDay = lhs.weekday?.rawValue ?? 8
                let rightDay = rhs.weekday?.rawValue ?? 8
                if leftDay != rightDay { return leftDay < rightDay }
                return lhs.name.lowercased() < rhs.name.lowercased()
            }
        } catch {
            report(error)
        }
    }

    /// Scraper run history (`scrape_log`), newest first.
    func loadScrapeLog() async {
        guard config.isConfigured else { return }
        isLoadingScrapeLog = true
        defer { isLoadingScrapeLog = false }
        do {
            scrapeLog = try await api.select(
                "scrape_log",
                query: [
                    URLQueryItem(name: "order", value: "last_scraped.desc.nullslast,id.desc"),
                    URLQueryItem(name: "limit", value: "200"),
                ]
            )
        } catch {
            report(error)
        }
    }

    // MARK: - Plans (Going / Cancelled / Clear)

    /// RPC `set_plan_status`. "Going" also creates a planned set row on the
    /// server, so sets are reloaded too.
    @discardableResult
    func setPlanStatus(micID: Int, date: CalendarDate, status: PlanStatus) async -> Bool {
        let ok = await perform {
            try await api.rpcVoid(
                "set_plan_status",
                args: PlanStatusArgs(micID: micID, planDate: date.isoString, status: status.rawValue)
            )
        }
        guard ok else { return false }
        await reloadPlans(on: date)
        if status == .going {
            await reloadSets()
        }
        return true
    }

    /// RPC `clear_plan` (a planned set row, if any, is left alone — same as web).
    @discardableResult
    func clearPlan(micID: Int, date: CalendarDate) async -> Bool {
        let ok = await perform {
            try await api.rpcVoid("clear_plan", args: PlanArgs(micID: micID, planDate: date.isoString))
        }
        guard ok else { return false }
        await reloadPlans(on: date)
        return true
    }

    // MARK: - Mics

    /// POST a new mic. Returns the stored mic, or nil on failure (including
    /// validation failure, which sets `errorMessage` to the web's message).
    /// Geocode first with `geocode(address:borough:)` and put the result in
    /// `fields.latitude/longitude` if you want a map pin.
    @discardableResult
    func addMic(_ fields: MicFields) async -> Mic? {
        if let problem = fields.validationError {
            errorMessage = problem
            return nil
        }
        // Outer optional: did the request succeed? Inner: did it return a row?
        let created: Mic?? = await performReturning { () async throws -> Mic? in
            let rows: [Mic] = try await self.api.insert("open_mics", body: MicInsertPayload(fields: fields))
            return rows.first
        }
        guard let result = created else { return nil }
        await reloadMics()
        return result ?? mics.last
    }

    /// PATCH every editable field of a mic (explicit nulls for cleared ones).
    /// Pass `includeCoordinates: true` when the address changed and you
    /// re-geocoded (fields.latitude/longitude may then be nil to clear them).
    @discardableResult
    func updateMic(id: Int, fields: MicFields, includeCoordinates: Bool = false) async -> Bool {
        if let problem = fields.validationError {
            errorMessage = problem
            return false
        }
        let ok = await perform {
            let rows: [Mic] = try await api.update(
                "open_mics", id: id,
                body: MicUpdatePayload(fields: fields, includeCoordinates: includeCoordinates)
            )
            if rows.isEmpty { throw AppStore.nothingSavedError }
        }
        guard ok else { return false }
        await reloadMics()
        return true
    }

    /// PATCH `mic_rating` (0–10, step 0.5; nil clears it).
    @discardableResult
    func updateMicRating(micID: Int, rating: Double?) async -> Bool {
        let ok = await perform {
            let rows: [Mic] = try await api.update("open_mics", id: micID, body: MicRatingPayload(micRating: rating))
            if rows.isEmpty { throw AppStore.nothingSavedError }
        }
        guard ok else { return false }
        await reloadMics()
        return true
    }

    /// RPC `delete_mic`: soft delete (is_active = false) + remove its plans.
    @discardableResult
    func deleteMic(micID: Int) async -> Bool {
        let ok = await perform {
            try await api.rpcVoid("delete_mic", args: MicIDArgs(micID: micID))
        }
        guard ok else { return false }
        plans.removeAll { $0.openMicId == micID }
        await reloadMics()
        return true
    }

    /// PATCH latitude/longitude. Batch callers pass `reload: false` and
    /// reload once at the end.
    @discardableResult
    func updateCoordinates(micID: Int, latitude: Double?, longitude: Double?, reload: Bool = true) async -> Bool {
        let ok = await perform {
            let rows: [Mic] = try await api.update(
                "open_mics", id: micID,
                body: CoordinatesPayload(latitude: latitude, longitude: longitude)
            )
            if rows.isEmpty { throw AppStore.nothingSavedError }
        }
        if ok && reload {
            await reloadMics()
        }
        return ok
    }

    // MARK: - Geocoding

    /// Geocodes one address on the device (rule 18 bounds, 1 request/sec).
    func geocode(address: String?, borough: String?) async -> GeoPoint? {
        await geocoder.geocode(address: address, borough: borough)
    }

    /// "Geocode with device": every active mic without coordinates that has
    /// an address. Progress is published in `geocodeProgress`.
    @discardableResult
    func geocodeMissingCoordinates() async -> GeocodeSummary {
        let candidates = micsMissingCoordinates.filter { TextRules.trimmedOrNil($0.address) != nil }
        var summary = GeocodeSummary(attempted: 0, geocoded: 0)
        guard !candidates.isEmpty else { return summary }

        geocodeProgress = GeocodeProgress(total: candidates.count, done: 0, succeeded: 0, currentMicName: nil)
        busyCount += 1
        defer {
            busyCount -= 1
            geocodeProgress = nil
        }

        for mic in candidates {
            if Task.isCancelled { break }
            geocodeProgress?.currentMicName = mic.name
            if let point = await geocoder.geocode(address: mic.address, borough: mic.borough) {
                let saved = await updateCoordinates(
                    micID: mic.id, latitude: point.latitude, longitude: point.longitude, reload: false
                )
                if saved { summary.geocoded += 1 }
            }
            summary.attempted += 1
            geocodeProgress?.done = summary.attempted
            geocodeProgress?.succeeded = summary.geocoded
        }

        await reloadMics()
        return summary
    }

    // MARK: - Sets

    /// POST a new logged set.
    @discardableResult
    func logSet(_ fields: SetFields) async -> Bool {
        let ok = await perform {
            let _: [SetEntry] = try await api.insert("my_sets", body: SetInsertPayload(fields: fields))
        }
        guard ok else { return false }
        await reloadSets()
        return true
    }

    /// PATCH an existing set (also how a planned row gets logged).
    @discardableResult
    func updateSet(id: Int, fields: SetFields) async -> Bool {
        let ok = await perform {
            let rows: [SetEntry] = try await api.update("my_sets", id: id, body: SetUpdatePayload(fields: fields))
            if rows.isEmpty { throw AppStore.nothingSavedError }
        }
        guard ok else { return false }
        await reloadSets()
        return true
    }

    /// DELETE a set row (Set Detail → Delete).
    @discardableResult
    func deleteSet(id: Int) async -> Bool {
        let ok = await perform {
            try await api.delete("my_sets", id: id)
        }
        guard ok else { return false }
        await reloadSets()
        return true
    }

    /// RPC `delete_planned_set`: removes a planned row and its calendar plan.
    /// Returns true only if the server actually deleted it (it refuses rows
    /// that have been logged).
    @discardableResult
    func deletePlannedSet(id: Int) async -> Bool {
        let date = sets.first { $0.id == id }?.datePerformed
        let deleted: Bool? = await performReturning {
            try await api.rpcBool("delete_planned_set", args: SetIDArgs(setID: id))
        }
        guard let didDelete = deleted else { return false }
        await reloadSets()
        if let date = date {
            await reloadPlans(on: date)
        }
        return didDelete
    }

    // MARK: - Scraper queue

    /// RPC `import_scraped_mic`. Returns the new open_mics id.
    @discardableResult
    func importScraped(id: Int) async -> Int? {
        let newID: Int? = await performReturning {
            try await api.rpcInt("import_scraped_mic", args: ScrapedIDArgs(id: id))
        }
        guard newID != nil else { return nil }
        scrapedMics.removeAll { $0.id == id }
        await reloadMics()
        return newID
    }

    /// Imports every pending listing from one source ("badslava" /
    /// "firemics"). Returns how many were added; stops reporting after the
    /// first error but keeps going.
    @discardableResult
    func importAllScraped(source: String) async -> Int {
        let batch = scrapedMics.filter { $0.source == source }
        var added = 0
        var firstError: String?
        busyCount += 1
        defer { busyCount -= 1 }

        for listing in batch {
            if Task.isCancelled { break }
            do {
                _ = try await api.rpcInt("import_scraped_mic", args: ScrapedIDArgs(id: listing.id))
                added += 1
                scrapedMics.removeAll { $0.id == listing.id }
            } catch {
                if APIError.isCancellation(error) { break }
                if firstError == nil {
                    firstError = "\(listing.name): \(APIError.describe(error))"
                }
            }
        }
        if let firstError = firstError {
            errorMessage = "Added \(added) of \(batch.count). First error — \(firstError)"
        }
        await reloadMics()
        await loadScraped()
        return added
    }

    /// RPC `dismiss_scraped_mic`.
    @discardableResult
    func dismissScraped(id: Int) async -> Bool {
        let ok = await perform {
            try await api.rpcVoid("dismiss_scraped_mic", args: ScrapedIDArgs(id: id))
        }
        if ok {
            scrapedMics.removeAll { $0.id == id }
        }
        return ok
    }

    // MARK: - Errors

    func clearError() {
        errorMessage = nil
    }

    /// Shows an error unless it's just a cancelled task.
    func report(_ error: Error) {
        guard !APIError.isCancellation(error) else { return }
        errorMessage = APIError.describe(error)
    }

    /// Forgets all loaded data (on sign-out).
    func clearData() {
        mics = []
        activeMics = []
        micsByID = [:]
        sets = []
        loggedVisitCounts = [:]
        plans = []
        loadedPlanDays = []
        scrapedMics = []
        scrapeLog = []
        hasLoadedOnce = false
        lastRefreshed = nil
        errorMessage = nil
    }

    // MARK: - Private: fetching

    private func fetchMics() async throws -> [Mic] {
        try await api.select("open_mics", query: [URLQueryItem(name: "order", value: "id.asc")])
    }

    private func fetchSets() async throws -> [SetEntry] {
        try await api.select("my_sets", query: [URLQueryItem(name: "order", value: "date_performed.desc,id.desc")])
    }

    private func fetchPlans(from start: CalendarDate, through end: CalendarDate) async throws -> [MicPlan] {
        try await api.select(
            "mic_plans",
            query: [
                URLQueryItem(name: "plan_date", value: "gte.\(start.isoString)"),
                URLQueryItem(name: "plan_date", value: "lte.\(end.isoString)"),
                URLQueryItem(name: "order", value: "plan_date.asc,id.asc"),
            ]
        )
    }

    private func reloadMics() async {
        do {
            apply(mics: try await fetchMics())
        } catch {
            report(error)
        }
    }

    private func reloadSets() async {
        do {
            apply(sets: try await fetchSets())
        } catch {
            report(error)
        }
    }

    private func reloadPlans(on date: CalendarDate) async {
        await loadPlans(from: date, to: date)
    }

    private func apply(mics newMics: [Mic]) {
        mics = newMics
        activeMics = MicRules.sortedByDayAndTime(newMics.filter { $0.isActive })
        micsByID = Dictionary(newMics.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    }

    private func apply(sets newSets: [SetEntry]) {
        sets = newSets
        loggedVisitCounts = SetRules.loggedVisitCounts(newSets)
    }

    /// Replaces the plans inside `start...end` with `fetched`, keeping plans
    /// for other dates that were loaded earlier.
    private func replacePlans(_ fetched: [MicPlan], from start: CalendarDate, through end: CalendarDate) {
        guard start <= end else { return }
        var kept = plans.filter { $0.planDate < start || $0.planDate > end }
        kept.append(contentsOf: fetched)
        plans = kept
        for dayNumber in start.dayNumber...end.dayNumber {
            loadedPlanDays.insert(CalendarDate(dayNumber: dayNumber))
        }
    }

    /// Last week's Monday through the Sunday three weeks out.
    nonisolated static func defaultPlanWindow(today: CalendarDate) -> (start: CalendarDate, end: CalendarDate) {
        let monday = today.startOfWeek
        return (monday.adding(weeks: -1), monday.adding(days: 27))
    }

    // MARK: - Private: write helpers

    private static let nothingSavedError = APIError.unexpected(
        "Nothing was saved — the row wasn't found or this account can't edit it."
    )

    /// Runs a write, tracking `busyCount` and reporting failures.
    private func perform(_ operation: () async throws -> Void) async -> Bool {
        busyCount += 1
        defer { busyCount -= 1 }
        do {
            try await operation()
            return true
        } catch {
            report(error)
            return false
        }
    }

    /// Like `perform`, but returns the operation's value (nil on failure).
    private func performReturning<T>(_ operation: () async throws -> T) async -> T? {
        busyCount += 1
        defer { busyCount -= 1 }
        do {
            return try await operation()
        } catch {
            report(error)
            return nil
        }
    }
}

// MARK: - RPC argument bodies (names must match the SQL exactly — spec 3.4)

private struct PlanStatusArgs: Encodable {
    let micID: Int
    let planDate: String
    let status: String

    enum CodingKeys: String, CodingKey {
        case micID = "p_mic_id"
        case planDate = "p_plan_date"
        case status = "p_status"
    }
}

private struct PlanArgs: Encodable {
    let micID: Int
    let planDate: String

    enum CodingKeys: String, CodingKey {
        case micID = "p_mic_id"
        case planDate = "p_plan_date"
    }
}

private struct MicIDArgs: Encodable {
    let micID: Int

    enum CodingKeys: String, CodingKey {
        case micID = "p_mic_id"
    }
}

private struct SetIDArgs: Encodable {
    let setID: Int

    enum CodingKeys: String, CodingKey {
        case setID = "p_set_id"
    }
}

private struct ScrapedIDArgs: Encodable {
    let id: Int

    enum CodingKeys: String, CodingKey {
        case id = "p_id"
    }
}
