import XCTest
@testable import MicTracker

// =============================================================================
// Change password (spec 9): the pure validation rules, and AuthService's two
// REST calls against a fake server.
//
// The fake server is a URLProtocol: a URLSession built with
// `StubURLProtocol.session()` never touches the network — every request is
// recorded and answered by the closure the test sets.
// =============================================================================
final class ChangePasswordTests: XCTestCase {

    // MARK: - Validation (rules 1–3, in order)

    func testSaveNeedsEveryFieldFilledIn() {
        XCTAssertFalse(PasswordRules.canSubmit(current: "", new: "", confirm: ""))
        XCTAssertFalse(PasswordRules.canSubmit(current: "old-pass", new: "new-pass1", confirm: ""))
        XCTAssertFalse(PasswordRules.canSubmit(current: "", new: "new-pass1", confirm: "new-pass1"))
        XCTAssertTrue(PasswordRules.canSubmit(current: "old-pass", new: "new-pass1", confirm: "new-pass1"))
        // A space is a real password character, so it counts as filled in.
        XCTAssertTrue(PasswordRules.canSubmit(current: " ", new: " ", confirm: " "))
    }

    func testTooShortComesFirst() {
        XCTAssertEqual(
            PasswordRules.validationError(current: "old-pass", new: "short", confirm: "short"),
            "Use at least 8 characters."
        )
        // Also too short AND mismatched AND equal to current → rule 1 wins.
        XCTAssertEqual(
            PasswordRules.validationError(current: "short", new: "short", confirm: "other"),
            "Use at least 8 characters."
        )
        // 7 characters is too short; exactly 8 is fine.
        XCTAssertEqual(PasswordRules.validationError(current: "old-pass", new: "1234567", confirm: "1234567"), PasswordRules.tooShortMessage)
        XCTAssertNil(PasswordRules.validationError(current: "old-pass", new: "12345678", confirm: "12345678"))
    }

    func testMismatchComesBeforeSameAsCurrent() {
        XCTAssertEqual(
            PasswordRules.validationError(current: "old-pass", new: "new-pass1", confirm: "new-pass2"),
            "The new passwords don't match."
        )
        // New equals current but confirm differs → still the mismatch message.
        XCTAssertEqual(
            PasswordRules.validationError(current: "old-pass", new: "old-pass", confirm: "old-pasS"),
            PasswordRules.mismatchMessage
        )
    }

    func testNewEqualToCurrent() {
        XCTAssertEqual(
            PasswordRules.validationError(current: "old-pass", new: "old-pass", confirm: "old-pass"),
            "Pick a password different from your current one."
        )
    }

    func testValidPasswordsPass() {
        XCTAssertNil(PasswordRules.validationError(current: "old-pass", new: "brand new pass", confirm: "brand new pass"))
        // Passwords are compared exactly: a trailing space makes it different.
        XCTAssertNil(PasswordRules.validationError(current: "old-pass", new: "old-pass ", confirm: "old-pass "))
    }

    // MARK: - AuthService against a fake server

    private let config = AppConfig(urlString: "https://example.supabase.co", anonKey: "sb_publishable_test")
    private static let email = "owner@example.com"
    /// A Keychain "service" of our own, so the tests never touch the app's
    /// real saved session.
    private let keychain = KeychainStore(service: "com.comedyplatform.mictracker.tests.\(UUID().uuidString)")

    override func tearDown() {
        StubURLProtocol.reset()
        keychain.remove("supabase-session") // the account AuthService saves under
        super.tearDown()
    }

    /// A token response like GoTrue's. (Static, so the stub's handler —
    /// which runs on a URLSession thread — doesn't capture the test case.)
    private static func tokenJSON(access: String, refresh: String) -> String {
        """
        {"access_token": "\(access)", "token_type": "bearer", "expires_in": 3600,
         "refresh_token": "\(refresh)", "user": {"id": "user-1", "email": "\(email)"}}
        """
    }

    /// An AuthService that has signed in normally (token + is_app_admin),
    /// with an empty request log afterwards.
    @MainActor
    private func signedInService() async throws -> AuthService {
        StubURLProtocol.handler = { request in
            switch request.url?.path {
            case "/auth/v1/token":
                return (200, Self.tokenJSON(access: "first-access", refresh: "first-refresh"))
            case "/rest/v1/rpc/is_app_admin":
                return (200, "true")
            default:
                return (404, "{}")
            }
        }
        let auth = AuthService(config: config, keychain: keychain, urlSession: StubURLProtocol.session())
        await auth.signIn(email: Self.email, password: "old-password")
        XCTAssertEqual(auth.state, .ready(email: Self.email))
        XCTAssertEqual(auth.session?.accessToken, "first-access")
        StubURLProtocol.reset()
        return auth
    }

    /// Runs `changePassword` and returns the message it failed with
    /// (nil when it succeeded).
    @MainActor
    private func changePasswordError(_ auth: AuthService, current: String = "old-password", new: String = "brand-new-pass") async -> String? {
        do {
            try await auth.changePassword(current: current, new: new)
            return nil
        } catch {
            return APIError.describe(error)
        }
    }

    @MainActor
    func testWrongCurrentPasswordSaysSoAndStaysSignedIn() async throws {
        let auth = try await signedInService()
        StubURLProtocol.handler = { _ in
            (400, #"{"error": "invalid_grant", "error_description": "Invalid login credentials"}"#)
        }

        let message = await changePasswordError(auth, current: "wrong-password")

        XCTAssertEqual(message, "Your current password is incorrect.")
        // Still signed in with the old session, and no PUT was attempted.
        XCTAssertEqual(auth.state, .ready(email: Self.email))
        XCTAssertEqual(auth.session?.accessToken, "first-access")
        XCTAssertEqual(StubURLProtocol.requests.map { $0.url?.path }, ["/auth/v1/token"])
    }

    @MainActor
    func testSuccessSignsInAgainThenPutsTheNewPassword() async throws {
        let auth = try await signedInService()
        StubURLProtocol.handler = { request in
            switch request.url?.path {
            case "/auth/v1/token":
                return (200, Self.tokenJSON(access: "fresh-access", refresh: "fresh-refresh"))
            case "/auth/v1/user":
                return (200, #"{"id": "user-1", "email": "owner@example.com"}"#)
            default:
                return (404, "{}")
            }
        }

        let message = await changePasswordError(auth)
        XCTAssertNil(message)

        let requests = StubURLProtocol.requests
        XCTAssertEqual(requests.count, 2)
        guard requests.count == 2 else { return }

        // 1. Re-check the current password: a normal password sign-in.
        let signIn = requests[0]
        XCTAssertEqual(signIn.httpMethod, "POST")
        XCTAssertEqual(signIn.url?.path, "/auth/v1/token")
        XCTAssertEqual(signIn.url?.query, "grant_type=password")
        XCTAssertEqual(signIn.value(forHTTPHeaderField: "apikey"), "sb_publishable_test")
        XCTAssertEqual(try jsonBody(signIn), ["email": Self.email, "password": "old-password"])

        // 2. PUT the new password with the FRESH token.
        let update = requests[1]
        XCTAssertEqual(update.httpMethod, "PUT")
        XCTAssertEqual(update.url?.path, "/auth/v1/user")
        XCTAssertEqual(update.value(forHTTPHeaderField: "Authorization"), "Bearer fresh-access")
        XCTAssertEqual(update.value(forHTTPHeaderField: "apikey"), "sb_publishable_test")
        XCTAssertEqual(update.value(forHTTPHeaderField: "Content-Type"), "application/json")
        XCTAssertEqual(try jsonBody(update), ["password": "brand-new-pass"])

        // The fresh session was stored, and the user is still signed in.
        XCTAssertEqual(auth.session?.accessToken, "fresh-access")
        XCTAssertEqual(auth.session?.refreshToken, "fresh-refresh")
        XCTAssertEqual(auth.state, .ready(email: Self.email))
    }

    @MainActor
    func testWeakOrSamePasswordShowsTheServerMessage() async throws {
        let refusals = [
            #"{"code": 422, "error_code": "weak_password", "msg": "Password should contain at least one character of each: abcdefghijklmnopqrstuvwxyz, 0123456789.", "weak_password": {"reasons": ["characters"]}}"#,
            #"{"code": 422, "error_code": "same_password", "msg": "New password should be different from the old password."}"#,
        ]
        for refusal in refusals {
            let auth = try await signedInService()
            StubURLProtocol.handler = { request in
                request.url?.path == "/auth/v1/token"
                    ? (200, Self.tokenJSON(access: "fresh-access", refresh: "fresh-refresh"))
                    : (422, refusal)
            }

            let message = await changePasswordError(auth)

            let expected = APIError.serverMessage(from: Data(refusal.utf8)).message
            XCTAssertNotNil(expected)
            XCTAssertEqual(message, expected)
            XCTAssertEqual(auth.state, .ready(email: Self.email))
            StubURLProtocol.reset()
        }
    }

    @MainActor
    func testOtherFailureWithoutAMessageUsesTheGenericText() async throws {
        let auth = try await signedInService()
        StubURLProtocol.handler = { request in
            request.url?.path == "/auth/v1/token"
                ? (200, Self.tokenJSON(access: "fresh-access", refresh: "fresh-refresh"))
                : (500, "")
        }

        let message = await changePasswordError(auth)

        XCTAssertEqual(message, "Couldn't change your password. Check your connection and try again.")
        XCTAssertEqual(auth.state, .ready(email: Self.email))
    }

    @MainActor
    func testNoConnectionUsesTheGenericText() async throws {
        let auth = try await signedInService()
        StubURLProtocol.failure = URLError(.notConnectedToInternet)

        let message = await changePasswordError(auth)

        XCTAssertEqual(message, PasswordRules.genericFailureMessage)
        XCTAssertEqual(auth.state, .ready(email: Self.email))
        XCTAssertEqual(auth.session?.accessToken, "first-access")
    }

    // MARK: - Helpers

    /// The request's JSON body as [String: String].
    private func jsonBody(_ request: URLRequest) throws -> [String: String] {
        let data = try XCTUnwrap(StubURLProtocol.body(of: request), "request has no body")
        return try JSONDecoder().decode([String: String].self, from: data)
    }
}

// =============================================================================
// StubURLProtocol — a fake server for URLSession.
//
// URLSession asks each registered URLProtocol "can you handle this?"; ours
// says yes to everything, records the request, and answers with whatever
// `handler` returns (or fails with `failure`). URLSession calls it from a
// background thread, so the shared state sits behind a lock.
// =============================================================================
final class StubURLProtocol: URLProtocol {

    /// (HTTP status, response body) for a request. Called on a URLSession
    /// thread, hence @Sendable.
    typealias Handler = @Sendable (URLRequest) -> (Int, String)

    private static let lock = NSLock()
    private static var storedHandler: Handler?
    private static var storedFailure: Error?
    private static var storedRequests: [URLRequest] = []

    static var handler: Handler? {
        get { lock.lock(); defer { lock.unlock() }; return storedHandler }
        set { lock.lock(); storedHandler = newValue; lock.unlock() }
    }

    /// When set, every request fails with this error (e.g. no connection).
    static var failure: Error? {
        get { lock.lock(); defer { lock.unlock() }; return storedFailure }
        set { lock.lock(); storedFailure = newValue; lock.unlock() }
    }

    /// Every request received since the last `reset()`, oldest first.
    static var requests: [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return storedRequests
    }

    static func reset() {
        lock.lock()
        storedHandler = nil
        storedFailure = nil
        storedRequests = []
        lock.unlock()
    }

    /// A URLSession whose requests all go to this stub.
    static func session() -> URLSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return URLSession(configuration: configuration)
    }

    /// URLSession often moves `httpBody` into `httpBodyStream` before a
    /// URLProtocol sees the request, so read whichever is there.
    static func body(of request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }

    override class func canInit(with request: URLRequest) -> Bool { true }

    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        // Keep the body readable for the assertions.
        var recorded = request
        if recorded.httpBody == nil, let body = Self.body(of: request) {
            recorded.httpBody = body
        }
        Self.lock.lock()
        Self.storedRequests.append(recorded)
        let handler = Self.storedHandler
        let failure = Self.storedFailure
        Self.lock.unlock()

        if let failure = failure {
            client?.urlProtocol(self, didFailWithError: failure)
            return
        }
        let (status, body) = handler?(recorded) ?? (500, "")
        let url = request.url ?? URL(string: "https://example.invalid")!
        let response = HTTPURLResponse(
            url: url,
            statusCode: status,
            httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}
