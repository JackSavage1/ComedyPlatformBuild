import Foundation

// =============================================================================
// DemoBackend — "Try the demo" (spec 8): the bundled sample data, kept in
// memory and forgotten when the app quits.
//
// It behaves like a tiny Supabase:
//   * Each table is an array of rows (`DemoRow`) with the real column names.
//   * Reads decode those rows with the same `Decodable` models the real
//     backend uses, then sort/filter them like the real queries do.
//   * Inserts/updates encode the SAME payloads the real backend sends
//     (MicInsertPayload, SetUpdatePayload, …) and store the result, so
//     "omit nulls on insert / explicit nulls on update" works identically.
//   * The RPC methods copy the SQL functions in spec 3.4 (Going creates a
//     planned set, delete_planned_set also removes the plan, import moves a
//     scraped listing into the mic list, …).
//   * New ids continue from the largest existing id.
// =============================================================================
@MainActor
final class DemoBackend: DataBackend {
    private var tables: DemoTables

    init(tables: DemoTables) {
        self.tables = tables
    }

    /// The bundled DemoData.json, with its dates resolved against `today`.
    static func bundled(today: CalendarDate = CalendarDate.today(), bundle: Bundle = .main) throws -> DemoBackend {
        let json = try DemoSeed.bundledJSON(in: bundle)
        return DemoBackend(tables: try DemoSeed.resolve(json: json, today: today))
    }

    // MARK: - Reads

    func fetchMics() async throws -> [Mic] {
        try Self.decode(tables.mics, as: Mic.self).sorted { $0.id < $1.id }
    }

    func fetchSets() async throws -> [SetEntry] {
        try Self.decode(tables.sets, as: SetEntry.self).sorted { lhs, rhs in
            lhs.datePerformed != rhs.datePerformed ? lhs.datePerformed > rhs.datePerformed : lhs.id > rhs.id
        }
    }

    func fetchPlans(from start: CalendarDate, through end: CalendarDate) async throws -> [MicPlan] {
        try Self.decode(tables.plans, as: MicPlan.self)
            .filter { $0.planDate >= start && $0.planDate <= end }
            .sorted { lhs, rhs in
                lhs.planDate != rhs.planDate ? lhs.planDate < rhs.planDate : lhs.id < rhs.id
            }
    }

    func fetchPendingScraped() async throws -> [ScrapedMic] {
        try Self.decode(tables.scrapedMics, as: ScrapedMic.self)
            .filter { $0.status == "pending" }
            .sorted { lhs, rhs in
                lhs.source != rhs.source ? lhs.source < rhs.source : lhs.name < rhs.name
            }
    }

    func fetchScrapeLog() async throws -> [ScrapeLogEntry] {
        try Self.decode(tables.scrapeLog, as: ScrapeLogEntry.self).sorted { lhs, rhs in
            // Newest first; rows without a time go last.
            switch (lhs.lastScraped, rhs.lastScraped) {
            case let (left?, right?) where left != right: return left > right
            case (.some, nil): return true
            case (nil, .some): return false
            default: return lhs.id > rhs.id
            }
        }
    }

    // MARK: - Mics

    func insertMic(_ fields: MicFields) async throws -> Mic? {
        var row = try Self.row(from: MicInsertPayload(fields: fields))
        row["id"] = Self.nextID(in: tables.mics)
        tables.mics.append(row)
        return try Self.decode([row], as: Mic.self).first
    }

    func updateMic(id: Int, fields: MicFields, includeCoordinates: Bool) async throws {
        try Self.patch(&tables.mics, id: id, with: MicUpdatePayload(fields: fields, includeCoordinates: includeCoordinates))
    }

    func updateMicRating(id: Int, rating: Double?) async throws {
        try Self.patch(&tables.mics, id: id, with: MicRatingPayload(micRating: rating))
    }

    func updateCoordinates(id: Int, latitude: Double?, longitude: Double?) async throws {
        try Self.patch(&tables.mics, id: id, with: CoordinatesPayload(latitude: latitude, longitude: longitude))
    }

    /// `delete_mic`: soft delete, and drop every plan for the mic.
    func deleteMic(id: Int) async throws {
        if let index = Self.index(of: id, in: tables.mics) {
            tables.mics[index]["is_active"] = false
        }
        tables.plans.removeAll { DemoValue.int($0["open_mic_id"]) == id }
    }

    // MARK: - Plans

    /// `set_plan_status`: upsert the plan; Going also adds a planned
    /// (skeletal) set for that mic and date unless one already exists.
    func setPlanStatus(micID: Int, date: CalendarDate, status: PlanStatus) async throws {
        let day = date.isoString
        if let index = tables.plans.firstIndex(where: { Self.matches($0, micID: micID, dateColumn: "plan_date", date: day) }) {
            tables.plans[index]["status"] = status.rawValue
        } else {
            tables.plans.append([
                "id": Self.nextID(in: tables.plans),
                "open_mic_id": micID,
                "plan_date": day,
                "status": status.rawValue,
            ])
        }

        let hasSet = tables.sets.contains { Self.matches($0, micID: micID, dateColumn: "date_performed", date: day) }
        if status == .going && !hasSet {
            // Only the two columns the SQL inserts; the rest are the
            // table's defaults (new_material/got_feedback false, would_return true).
            tables.sets.append([
                "id": Self.nextID(in: tables.sets),
                "open_mic_id": micID,
                "date_performed": day,
                "new_material": false,
                "got_feedback": false,
                "would_return": true,
            ])
        }
    }

    /// `clear_plan`: only the plan goes; a planned set row stays (like the web).
    func clearPlan(micID: Int, date: CalendarDate) async throws {
        let day = date.isoString
        tables.plans.removeAll { Self.matches($0, micID: micID, dateColumn: "plan_date", date: day) }
    }

    // MARK: - Sets

    func insertSet(_ fields: SetFields) async throws {
        var row = try Self.row(from: SetInsertPayload(fields: fields))
        row["id"] = Self.nextID(in: tables.sets)
        tables.sets.append(row)
    }

    func updateSet(id: Int, fields: SetFields) async throws {
        try Self.patch(&tables.sets, id: id, with: SetUpdatePayload(fields: fields))
    }

    func deleteSet(id: Int) async throws {
        tables.sets.removeAll { DemoValue.int($0["id"]) == id }
    }

    /// `delete_planned_set`: only an unrated row is deleted, together with
    /// its calendar plan. Returns whether anything was deleted.
    func deletePlannedSet(id: Int) async throws -> Bool {
        guard let index = Self.index(of: id, in: tables.sets),
              DemoValue.isNull(tables.sets[index]["set_rating"])
        else { return false }

        let removed = tables.sets.remove(at: index)
        if let micID = DemoValue.int(removed["open_mic_id"]), let day = DemoValue.string(removed["date_performed"]) {
            tables.plans.removeAll { Self.matches($0, micID: micID, dateColumn: "plan_date", date: day) }
        }
        return true
    }

    // MARK: - Scraper queue

    /// `import_scraped_mic`: copies the listing (its columns, then its full
    /// payload on top) into the mic list and marks it "added".
    func importScraped(id: Int) async throws -> Int {
        guard let index = Self.index(of: id, in: tables.scrapedMics) else {
            throw APIError.unexpected("Scraped mic \(id) was not found.")
        }
        let listing = tables.scrapedMics[index]
        let name = DemoValue.string(listing["name"]) ?? "This listing"
        if DemoValue.string(listing["status"]) == "added" {
            throw APIError.unexpected("\"\(name)\" was already added.")
        }

        // Start from the listing's own columns, then let the payload win.
        var source: DemoRow = [:]
        for column in Self.listingColumns where !DemoValue.isNull(listing[column]) {
            source[column] = listing[column]
        }
        for (column, value) in listing["payload"] as? DemoRow ?? [:] where !DemoValue.isNull(value) {
            source[column] = value
        }

        // Keep only real open_mics columns; blank text means "not provided".
        var row: DemoRow = [:]
        for column in Self.importableMicColumns {
            guard let value = source[column] else { continue }
            if let text = value as? String, TextRules.isBlank(text) {
                if Self.requiredMicColumns.contains(column) { row[column] = "" }
                continue
            }
            row[column] = value
        }
        for column in Self.requiredMicColumns where row[column] == nil {
            throw APIError.unexpected("Can't import \"\(name)\": the listing has no \(column).")
        }

        let newID = Self.nextID(in: tables.mics)
        row["id"] = newID
        row["is_active"] = true
        tables.mics.append(row)
        tables.scrapedMics[index]["status"] = "added"
        return newID
    }

    func dismissScraped(id: Int) async throws {
        if let index = Self.index(of: id, in: tables.scrapedMics) {
            tables.scrapedMics[index]["status"] = "dismissed"
        }
    }

    // MARK: - Columns

    /// `scraped_mics` columns copied before the payload (same as the SQL).
    private static let listingColumns = [
        "name", "venue", "day_of_week", "address", "display_time", "start_time", "cost", "signup_url",
    ]

    /// `open_mics` columns an import may fill (not id / is_active / timestamps).
    private static let importableMicColumns = [
        "name", "venue", "address", "neighborhood", "borough", "day_of_week", "start_time",
        "display_time", "end_time", "cost", "set_length_min", "signup_method", "signup_url",
        "signup_notes", "venue_url", "instagram", "urgency", "urgency_note", "advance_days",
        "mic_rating", "notes", "latitude", "longitude", "is_biweekly",
    ]

    /// NOT NULL columns without a default.
    private static let requiredMicColumns = ["name", "venue", "day_of_week", "start_time"]

    // MARK: - Row helpers

    /// Encodes a payload the way the real backend would send it and reads it
    /// back as a row (explicit nulls arrive as NSNull).
    private static func row<Body: Encodable>(from body: Body) throws -> DemoRow {
        let data = try JSONEncoder().encode(body)
        guard let row = try JSONSerialization.jsonObject(with: data) as? DemoRow else {
            throw APIError.unexpected("Couldn't prepare the demo change.")
        }
        return row
    }

    /// PATCH: writes every column in `body` onto the row with this id.
    private static func patch<Body: Encodable>(_ table: inout [DemoRow], id: Int, with body: Body) throws {
        guard let index = index(of: id, in: table) else { throw APIError.nothingSaved }
        let changes = try row(from: body)
        for (column, value) in changes {
            table[index][column] = value
        }
    }

    /// Decodes rows with the app's forgiving models (bad rows are skipped,
    /// exactly like APIClient does).
    private static func decode<Row: Decodable>(_ rows: [DemoRow], as type: Row.Type) throws -> [Row] {
        guard JSONSerialization.isValidJSONObject(rows) else {
            throw APIError.decoding("The demo data contains a value JSON can't hold.")
        }
        let data = try JSONSerialization.data(withJSONObject: rows)
        return try JSONDecoder().decode([LossyDecodable<Row>].self, from: data).compactMap { $0.value }
    }

    private static func index(of id: Int, in table: [DemoRow]) -> Int? {
        table.firstIndex { DemoValue.int($0["id"]) == id }
    }

    /// Largest id + 1 (1 for an empty table).
    private static func nextID(in table: [DemoRow]) -> Int {
        (table.compactMap { DemoValue.int($0["id"]) }.max() ?? 0) + 1
    }

    private static func matches(_ row: DemoRow, micID: Int, dateColumn: String, date: String) -> Bool {
        DemoValue.int(row["open_mic_id"]) == micID && DemoValue.string(row[dateColumn]) == date
    }
}
