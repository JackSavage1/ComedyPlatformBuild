import XCTest
@testable import MicTracker

/// Decoding must never crash on nulls, missing keys, floats-for-ints or
/// blank strings; insert payloads omit nulls; update payloads send them
/// (spec 3.5).
final class ModelDecodingTests: XCTestCase {

    private func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    private func jsonObject<T: Encodable>(_ value: T) throws -> [String: Any] {
        let data = try JSONEncoder().encode(value)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    // MARK: - Mic

    func testMicDecodesMessyRow() throws {
        let mic = try decode(Mic.self, """
        {
          "id": 12,
          "name": "  Comedy Night ",
          "venue": "The Club",
          "address": "   ",
          "neighborhood": null,
          "day_of_week": "Monday",
          "start_time": "19:00",
          "display_time": "",
          "cost": "$5",
          "set_length_min": 30.0,
          "signup_method": "in_person",
          "urgency": "high",
          "advance_days": null,
          "mic_rating": 7,
          "latitude": "40.7",
          "longitude": -73.98,
          "is_active": null,
          "is_biweekly": 1,
          "created_at": "2026-01-01T00:00:00",
          "some_new_column": {"nested": true}
        }
        """)
        XCTAssertEqual(mic.id, 12)
        XCTAssertEqual(mic.name, "Comedy Night")
        XCTAssertNil(mic.address)
        XCTAssertNil(mic.neighborhood)
        XCTAssertNil(mic.displayTime)
        XCTAssertEqual(mic.timeText, "19:00")
        XCTAssertEqual(mic.setLengthMin, 30)
        XCTAssertEqual(mic.advanceDays, 0)
        XCTAssertEqual(mic.micRating, 7)
        XCTAssertEqual(mic.latitude, 40.7)
        XCTAssertEqual(mic.longitude, -73.98)
        XCTAssertTrue(mic.isActive)       // null → active (only false hides a mic)
        XCTAssertTrue(mic.isBiweekly)     // 1 → true
        XCTAssertEqual(mic.urgencyLevel, .high)
        XCTAssertEqual(mic.weekday, .monday)
    }

    func testMicDecodesMinimalRow() throws {
        let mic = try decode(Mic.self, "{\"id\": 5}")
        XCTAssertEqual(mic.id, 5)
        XCTAssertEqual(mic.name, "Unnamed mic")
        XCTAssertEqual(mic.venue, "")
        XCTAssertEqual(mic.dayOfWeek, "")
        XCTAssertNil(mic.weekday)
        XCTAssertEqual(mic.startTime, "")
        XCTAssertNil(mic.setLengthMin)
        XCTAssertTrue(mic.isActive)
        XCTAssertFalse(mic.isBiweekly)
        XCTAssertEqual(mic.costText, "Free/TBD")
    }

    func testMicInactiveAndFloatValues() throws {
        let mic = try decode(Mic.self, "{\"id\": 6.0, \"is_active\": false, \"set_length_min\": \"7\", \"advance_days\": 2.0}")
        XCTAssertEqual(mic.id, 6)
        XCTAssertFalse(mic.isActive)
        XCTAssertEqual(mic.setLengthMin, 7)
        XCTAssertEqual(mic.advanceDays, 2)
    }

    func testMicWithoutIDFails() {
        XCTAssertThrowsError(try decode(Mic.self, "{\"name\": \"No id\"}"))
    }

    func testLossyArraySkipsBadRows() throws {
        let rows = try decode([LossyDecodable<SetEntry>].self, """
        [
          {"id": 1, "date_performed": "2026-10-20", "set_rating": 8},
          {"id": 2},
          {"id": 3, "date_performed": "not a date"},
          {"date_performed": "2026-10-20"},
          {"id": 4, "date_performed": "2026-10-21", "set_rating": null}
        ]
        """)
        let sets = rows.compactMap { $0.value }
        XCTAssertEqual(sets.map { $0.id }, [1, 4])
    }

    // MARK: - SetEntry

    func testSetEntryDecoding() throws {
        let entry = try decode(SetEntry.self, """
        {
          "id": 9, "open_mic_id": 3, "date_performed": "2026-10-20",
          "set_rating": 8.0, "crowd_rating": null, "crowd_size": "packed",
          "set_list": "1. Subway\\n2. Coffee", "recording_url": " ",
          "recording_type": "video", "notes": null,
          "new_material": true, "got_feedback": null, "feedback_notes": "",
          "would_return": null, "tags": "killed,crowd_work"
        }
        """)
        XCTAssertEqual(entry.id, 9)
        XCTAssertEqual(entry.openMicId, 3)
        XCTAssertEqual(entry.datePerformed, CalendarDate(year: 2026, month: 10, day: 20))
        XCTAssertEqual(entry.setRating, 8)
        XCTAssertNil(entry.crowdRating)
        XCTAssertEqual(entry.crowdSizeKind, .packed)
        XCTAssertEqual(entry.setList, "1. Subway\n2. Coffee")
        XCTAssertNil(entry.recordingUrl)
        XCTAssertEqual(entry.recordingKind, .video)
        XCTAssertTrue(entry.newMaterial)
        XCTAssertFalse(entry.gotFeedback)
        XCTAssertNil(entry.feedbackNotes)
        XCTAssertNil(entry.wouldReturn)
        XCTAssertEqual(entry.tagList, ["killed", "crowd_work"])
        XCTAssertTrue(entry.isLogged)
        XCTAssertEqual(entry.ratingEmoji, "🔥")
    }

    func testPlannedSetRow() throws {
        let entry = try decode(SetEntry.self, "{\"id\": 2, \"open_mic_id\": 4, \"date_performed\": \"2026-10-22\"}")
        XCTAssertTrue(entry.isPlanned)
        XCTAssertNil(entry.datedRating)
        XCTAssertEqual(entry.ratingEmoji, "📋")
    }

    // MARK: - Plans, scraper rows, scrape log

    func testMicPlanDecoding() throws {
        let plan = try decode(MicPlan.self, "{\"id\": 1, \"open_mic_id\": 7, \"plan_date\": \"2026-10-23\", \"status\": \"going\", \"created_at\": \"2026-10-20T10:00:00\"}")
        XCTAssertEqual(plan.openMicId, 7)
        XCTAssertEqual(plan.planDate, CalendarDate(year: 2026, month: 10, day: 23))
        XCTAssertEqual(plan.status, .going)

        let odd = try decode(MicPlan.self, "{\"id\": 2, \"open_mic_id\": 7, \"plan_date\": \"2026-10-24\", \"status\": \"maybe\"}")
        XCTAssertNil(odd.status)
        XCTAssertEqual(PlanStatus(raw: " Cancelled "), .cancelled)
    }

    func testScrapedMicDecoding() throws {
        let row = try decode(ScrapedMic.self, """
        {"id": 3000000000, "source": "badslava", "name": "Mic", "venue": "Bar", "day_of_week": "Friday",
         "address": null, "display_time": "8:00 PM", "start_time": "20:00", "cost": "Free",
         "signup_url": "", "payload": {"name": "Mic", "is_biweekly": 1}, "status": "pending",
         "first_seen_at": "2026-10-20T03:00:00.123456+00:00", "updated_at": "2026-10-20T03:00:00+00:00"}
        """)
        XCTAssertEqual(row.id, 3_000_000_000)
        XCTAssertEqual(row.sourceDisplayName, "Bad Slava")
        XCTAssertEqual(row.weekday, .friday)
        XCTAssertEqual(row.timeText, "8:00 PM")
        XCTAssertNil(row.signupUrl)
        XCTAssertNotNil(row.firstSeenAt)
        XCTAssertEqual(ScrapedMic.displayName(forSource: "firemics"), "FireMics")
        XCTAssertEqual(ScrapedMic.displayName(forSource: "new_site"), "New Site")
    }

    func testScrapeLogDecoding() throws {
        let entry = try decode(ScrapeLogEntry.self, "{\"id\": 1, \"source\": \"firemics\", \"last_scraped\": \"2026-10-20T03:00:00\", \"status\": \"success\", \"notes\": \"3 new\"}")
        XCTAssertEqual(entry.lastScraped, Date(timeIntervalSince1970: 1_792_465_200))
        XCTAssertEqual(entry.status, "success")

        let broken = try decode(ScrapeLogEntry.self, "{\"id\": 2, \"last_scraped\": \"yesterday\"}")
        XCTAssertNil(broken.lastScraped)
        XCTAssertEqual(broken.lastScrapedText, "yesterday")
    }

    func testTimestampParser() {
        // 2026-10-20T03:00:00Z = 1_792_465_200
        let base = Date(timeIntervalSince1970: 1_792_465_200)
        XCTAssertEqual(TimestampParser.parse("2026-10-20T03:00:00"), base)
        XCTAssertEqual(TimestampParser.parse("2026-10-20 03:00:00"), base)
        XCTAssertEqual(TimestampParser.parse("2026-10-20T03:00:00Z"), base)
        XCTAssertEqual(TimestampParser.parse("2026-10-20T05:00:00+02:00"), base)
        XCTAssertEqual(TimestampParser.parse("2026-10-19T23:00:00-04"), base)
        XCTAssertEqual(TimestampParser.parse("2026-10-20T03:00:00.5")?.timeIntervalSince1970 ?? 0, 1_792_465_200.5, accuracy: 0.001)
        XCTAssertEqual(TimestampParser.parse("2026-10-20"), Date(timeIntervalSince1970: 1_792_454_400))
        XCTAssertNil(TimestampParser.parse("garbage"))
        XCTAssertNil(TimestampParser.parse(nil))
    }

    // MARK: - Insert payloads omit nulls

    func testMicInsertPayloadOmitsNulls() throws {
        var fields = MicFields()
        fields.name = "  New Mic "
        fields.venue = "Bar"
        fields.dayOfWeek = "Tuesday"
        fields.startTime = "19:00"
        fields.displayTime = "7:00 PM"
        fields.address = "   "
        fields.setLengthMin = 0
        fields.latitude = 40.7
        fields.longitude = -73.9

        let json = try jsonObject(MicInsertPayload(fields: fields))
        XCTAssertEqual(json["name"] as? String, "New Mic")
        XCTAssertEqual(json["day_of_week"] as? String, "Tuesday")
        XCTAssertEqual(json["display_time"] as? String, "7:00 PM")
        XCTAssertEqual(json["is_active"] as? Bool, true)
        XCTAssertEqual(json["urgency"] as? String, "normal")
        XCTAssertEqual(json["latitude"] as? Double, 40.7)
        XCTAssertNil(json["address"])          // blank → omitted
        XCTAssertNil(json["set_length_min"])   // 0 → omitted
        XCTAssertNil(json["cost"])
        XCTAssertNil(json["notes"])
        XCTAssertFalse(json.values.contains { $0 is NSNull })
    }

    func testSetInsertPayloadOmitsNulls() throws {
        var fields = SetFields(openMicId: 3, datePerformed: CalendarDate(year: 2026, month: 10, day: 20))
        fields.tags = ["killed", " crowd_work "]
        fields.feedbackNotes = "ignored because gotFeedback is false"

        let json = try jsonObject(SetInsertPayload(fields: fields))
        XCTAssertEqual(json["open_mic_id"] as? Int, 3)
        XCTAssertEqual(json["date_performed"] as? String, "2026-10-20")
        XCTAssertEqual(json["set_rating"] as? Int, 5)
        XCTAssertEqual(json["crowd_size"] as? String, "decent")
        XCTAssertEqual(json["recording_type"] as? String, "none")
        XCTAssertEqual(json["would_return"] as? Bool, true)
        XCTAssertEqual(json["tags"] as? String, "killed,crowd_work")
        XCTAssertNil(json["feedback_notes"])
        XCTAssertNil(json["set_list"])
        XCTAssertFalse(json.values.contains { $0 is NSNull })
    }

    // MARK: - Update payloads send explicit nulls

    func testMicUpdatePayloadSendsNulls() throws {
        let mic = Mic(id: 1, name: "Mic", venue: "Bar", address: "1 Main St", dayOfWeek: "Monday",
                      startTime: "19:00", cost: "$5", latitude: 40.7, longitude: -73.9)
        var fields = MicFields(mic: mic)
        fields.cost = ""        // the user cleared it
        fields.address = nil

        let withoutCoordinates = try jsonObject(MicUpdatePayload(fields: fields))
        XCTAssertTrue(withoutCoordinates["cost"] is NSNull)
        XCTAssertTrue(withoutCoordinates["address"] is NSNull)
        XCTAssertTrue(withoutCoordinates["notes"] is NSNull)
        XCTAssertEqual(withoutCoordinates["name"] as? String, "Mic")
        XCTAssertNil(withoutCoordinates["latitude"])     // untouched unless asked
        XCTAssertNil(withoutCoordinates["is_active"])

        fields.latitude = nil
        fields.longitude = nil
        let withCoordinates = try jsonObject(MicUpdatePayload(fields: fields, includeCoordinates: true))
        XCTAssertTrue(withCoordinates["latitude"] is NSNull)
        XCTAssertTrue(withCoordinates["longitude"] is NSNull)
    }

    func testSetUpdatePayloadSendsNulls() throws {
        let planned = SetEntry(id: 4, openMicId: 2, datePerformed: CalendarDate(year: 2026, month: 10, day: 22))
        var fields = SetFields(set: planned)
        XCTAssertEqual(fields.setRating, 5)   // planned rows get the form defaults
        fields.tags = []
        fields.gotFeedback = true
        fields.feedbackNotes = "Tighten the opener"

        let json = try jsonObject(SetUpdatePayload(fields: fields))
        XCTAssertTrue(json["tags"] is NSNull)
        XCTAssertTrue(json["set_list"] is NSNull)
        XCTAssertTrue(json["recording_url"] is NSNull)
        XCTAssertEqual(json["feedback_notes"] as? String, "Tighten the opener")
        XCTAssertEqual(json["date_performed"] as? String, "2026-10-22")
    }

    func testRatingAndCoordinatePayloads() throws {
        XCTAssertTrue(try jsonObject(MicRatingPayload(micRating: nil))["mic_rating"] is NSNull)
        XCTAssertEqual(try jsonObject(MicRatingPayload(micRating: 7.5))["mic_rating"] as? Double, 7.5)
        let coordinates = try jsonObject(CoordinatesPayload(latitude: 40.7, longitude: -73.9))
        XCTAssertEqual(coordinates["latitude"] as? Double, 40.7)
        XCTAssertEqual(coordinates.count, 2)
    }

    func testMicFieldsValidation() {
        var fields = MicFields()
        XCTAssertEqual(fields.validationError, "Name, Venue, Day of Week, and Start Time are required!")
        fields.name = "Mic"
        fields.venue = "  "
        fields.startTime = "19:00"
        XCTAssertNotNil(fields.validationError)
        fields.venue = "Bar"
        XCTAssertNil(fields.validationError)
    }

    // MARK: - RPC scalars, server errors, config

    func testJSONScalars() {
        XCTAssertEqual(JSONScalar.bool(from: Data("true".utf8)), true)
        XCTAssertEqual(JSONScalar.bool(from: Data("false".utf8)), false)
        XCTAssertEqual(JSONScalar.bool(from: Data(" true\n".utf8)), true)
        XCTAssertEqual(JSONScalar.bool(from: Data("[true]".utf8)), true)
        XCTAssertNil(JSONScalar.bool(from: Data()))
        XCTAssertEqual(JSONScalar.int(from: Data("42".utf8)), 42)
        XCTAssertEqual(JSONScalar.int(from: Data("1".utf8)), 1)
        XCTAssertEqual(JSONScalar.int(from: Data("42.0".utf8)), 42)
        XCTAssertNil(JSONScalar.int(from: Data("true".utf8)))
        XCTAssertNil(JSONScalar.int(from: Data("null".utf8)))
    }

    func testServerErrorMessages() {
        let postgrest = APIError.fromResponse(
            status: 403,
            data: Data("{\"code\":\"42501\",\"message\":\"Not authorized: this account is not an app admin.\",\"details\":null,\"hint\":null}".utf8)
        )
        XCTAssertEqual(postgrest.errorDescription, "Not authorized: this account is not an app admin.")
        XCTAssertEqual(postgrest, .http(status: 403, message: "Not authorized: this account is not an app admin.", code: "42501"))

        let gotrue = APIError.serverMessage(from: Data("{\"code\":400,\"error_code\":\"invalid_credentials\",\"msg\":\"Invalid login credentials\"}".utf8))
        XCTAssertEqual(gotrue.message, "Invalid login credentials")
        XCTAssertEqual(gotrue.code, "invalid_credentials")

        let legacy = APIError.serverMessage(from: Data("{\"error\":\"invalid_grant\",\"error_description\":\"Invalid Refresh Token\"}".utf8))
        XCTAssertEqual(legacy.message, "Invalid Refresh Token")

        let empty = APIError.fromResponse(status: 500, data: Data())
        XCTAssertNotNil(empty.errorDescription)
        XCTAssertTrue(APIError.isCancellation(CancellationError()))
        XCTAssertTrue(APIError.isCancellation(URLError(.cancelled)))
        XCTAssertFalse(APIError.isCancellation(URLError(.notConnectedToInternet)))
    }

    func testAppConfig() {
        let good = AppConfig(urlString: " https://abcd.supabase.co/ ", anonKey: "eyJhbGciOi.test")
        XCTAssertTrue(good.isConfigured)
        XCTAssertEqual(good.supabaseURL?.absoluteString, "https://abcd.supabase.co")
        XCTAssertTrue(good.anonKeyIsJWT)
        XCTAssertNil(good.urlProblemHint)

        let publishable = AppConfig(urlString: "https://abcd.supabase.co", anonKey: "sb_publishable_123")
        XCTAssertTrue(publishable.isConfigured)
        XCTAssertFalse(publishable.anonKeyIsJWT)

        // What a URL written as https://… in an xcconfig turns into.
        let cutOff = AppConfig(urlString: "https:", anonKey: "key")
        XCTAssertFalse(cutOff.isConfigured)
        XCTAssertNotNil(cutOff.urlProblemHint)

        let unexpanded = AppConfig(urlString: "$(SUPABASE_URL)", anonKey: "$(SUPABASE_ANON_KEY)")
        XCTAssertFalse(unexpanded.isConfigured)
        XCTAssertEqual(unexpanded.anonKey, "")

        XCTAssertFalse(AppConfig.empty.isConfigured)
        XCTAssertFalse(AppConfig(urlString: "ftp://abcd.supabase.co", anonKey: "key").isConfigured)
    }
}
