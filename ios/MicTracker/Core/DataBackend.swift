import Foundation

// =============================================================================
// DataBackend — where AppStore reads and writes its data.
//
// There are two:
//   * SupabaseBackend (below) — the real database over REST (spec 3).
//   * DemoBackend (DemoBackend.swift) — bundled sample data kept in memory
//     ("Try the demo", spec 8).
//
// AppStore talks to whichever one is active through this protocol, so the
// screens never know (or care) which backend they're looking at.
//
// Rules every backend follows:
//   * Methods throw a user-facing error (usually an `APIError`) on failure.
//   * `update…` methods throw `APIError.nothingSaved` when no row matched.
//   * RPC-style methods behave like the SQL functions in spec 3.4.
// =============================================================================
@MainActor
protocol DataBackend: AnyObject, Sendable {

    // MARK: Reads

    /// Every mic (inactive ones too), by id.
    func fetchMics() async throws -> [Mic]
    /// Every `my_sets` row, newest first.
    func fetchSets() async throws -> [SetEntry]
    /// Plans with `start <= plan_date <= end`.
    func fetchPlans(from start: CalendarDate, through end: CalendarDate) async throws -> [MicPlan]
    /// Scraper listings still waiting for review (status "pending").
    func fetchPendingScraped() async throws -> [ScrapedMic]
    /// Scraper run history, newest first.
    func fetchScrapeLog() async throws -> [ScrapeLogEntry]

    // MARK: Mics

    /// Inserts a mic (null keys omitted, `is_active` true); returns the stored row.
    func insertMic(_ fields: MicFields) async throws -> Mic?
    /// Updates every editable column (explicit nulls for cleared ones).
    func updateMic(id: Int, fields: MicFields, includeCoordinates: Bool) async throws
    func updateMicRating(id: Int, rating: Double?) async throws
    func updateCoordinates(id: Int, latitude: Double?, longitude: Double?) async throws
    /// RPC `delete_mic`: soft delete + remove the mic's plans.
    func deleteMic(id: Int) async throws

    // MARK: Plans

    /// RPC `set_plan_status` (Going also creates a planned set row).
    func setPlanStatus(micID: Int, date: CalendarDate, status: PlanStatus) async throws
    /// RPC `clear_plan`.
    func clearPlan(micID: Int, date: CalendarDate) async throws

    // MARK: Sets

    func insertSet(_ fields: SetFields) async throws
    func updateSet(id: Int, fields: SetFields) async throws
    func deleteSet(id: Int) async throws
    /// RPC `delete_planned_set`: true only if a planned (unrated) row was deleted.
    func deletePlannedSet(id: Int) async throws -> Bool

    // MARK: Scraper queue

    /// RPC `import_scraped_mic`: returns the new mic's id.
    func importScraped(id: Int) async throws -> Int
    /// RPC `dismiss_scraped_mic`.
    func dismissScraped(id: Int) async throws
}

extension APIError {
    /// An update matched no row (wrong id, or RLS hid it).
    static let nothingSaved = APIError.unexpected(
        "Nothing was saved — the row wasn't found or this account can't edit it."
    )
}

// =============================================================================
// SupabaseBackend — the real thing: PostgREST tables + RPCs (spec 3.1–3.4).
// =============================================================================
@MainActor
final class SupabaseBackend: DataBackend {
    private let api: APIClient

    init(api: APIClient) {
        self.api = api
    }

    // MARK: - Reads

    func fetchMics() async throws -> [Mic] {
        try await api.select("open_mics", query: [URLQueryItem(name: "order", value: "id.asc")])
    }

    func fetchSets() async throws -> [SetEntry] {
        try await api.select("my_sets", query: [URLQueryItem(name: "order", value: "date_performed.desc,id.desc")])
    }

    func fetchPlans(from start: CalendarDate, through end: CalendarDate) async throws -> [MicPlan] {
        try await api.select(
            "mic_plans",
            query: [
                URLQueryItem(name: "plan_date", value: "gte.\(start.isoString)"),
                URLQueryItem(name: "plan_date", value: "lte.\(end.isoString)"),
                URLQueryItem(name: "order", value: "plan_date.asc,id.asc"),
            ]
        )
    }

    func fetchPendingScraped() async throws -> [ScrapedMic] {
        try await api.select(
            "scraped_mics",
            query: [
                URLQueryItem(name: "status", value: "eq.pending"),
                URLQueryItem(name: "order", value: "source.asc,name.asc"),
            ]
        )
    }

    func fetchScrapeLog() async throws -> [ScrapeLogEntry] {
        try await api.select(
            "scrape_log",
            query: [
                URLQueryItem(name: "order", value: "last_scraped.desc.nullslast,id.desc"),
                URLQueryItem(name: "limit", value: "200"),
            ]
        )
    }

    // MARK: - Mics

    func insertMic(_ fields: MicFields) async throws -> Mic? {
        let rows: [Mic] = try await api.insert("open_mics", body: MicInsertPayload(fields: fields))
        return rows.first
    }

    func updateMic(id: Int, fields: MicFields, includeCoordinates: Bool) async throws {
        let rows: [Mic] = try await api.update(
            "open_mics", id: id,
            body: MicUpdatePayload(fields: fields, includeCoordinates: includeCoordinates)
        )
        if rows.isEmpty { throw APIError.nothingSaved }
    }

    func updateMicRating(id: Int, rating: Double?) async throws {
        let rows: [Mic] = try await api.update("open_mics", id: id, body: MicRatingPayload(micRating: rating))
        if rows.isEmpty { throw APIError.nothingSaved }
    }

    func updateCoordinates(id: Int, latitude: Double?, longitude: Double?) async throws {
        let rows: [Mic] = try await api.update(
            "open_mics", id: id,
            body: CoordinatesPayload(latitude: latitude, longitude: longitude)
        )
        if rows.isEmpty { throw APIError.nothingSaved }
    }

    func deleteMic(id: Int) async throws {
        try await api.rpcVoid("delete_mic", args: MicIDArgs(micID: id))
    }

    // MARK: - Plans

    func setPlanStatus(micID: Int, date: CalendarDate, status: PlanStatus) async throws {
        try await api.rpcVoid(
            "set_plan_status",
            args: PlanStatusArgs(micID: micID, planDate: date.isoString, status: status.rawValue)
        )
    }

    func clearPlan(micID: Int, date: CalendarDate) async throws {
        try await api.rpcVoid("clear_plan", args: PlanArgs(micID: micID, planDate: date.isoString))
    }

    // MARK: - Sets

    func insertSet(_ fields: SetFields) async throws {
        let _: [SetEntry] = try await api.insert("my_sets", body: SetInsertPayload(fields: fields))
    }

    func updateSet(id: Int, fields: SetFields) async throws {
        let rows: [SetEntry] = try await api.update("my_sets", id: id, body: SetUpdatePayload(fields: fields))
        if rows.isEmpty { throw APIError.nothingSaved }
    }

    func deleteSet(id: Int) async throws {
        try await api.delete("my_sets", id: id)
    }

    func deletePlannedSet(id: Int) async throws -> Bool {
        try await api.rpcBool("delete_planned_set", args: SetIDArgs(setID: id))
    }

    // MARK: - Scraper queue

    func importScraped(id: Int) async throws -> Int {
        try await api.rpcInt("import_scraped_mic", args: ScrapedIDArgs(id: id))
    }

    func dismissScraped(id: Int) async throws {
        try await api.rpcVoid("dismiss_scraped_mic", args: ScrapedIDArgs(id: id))
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
