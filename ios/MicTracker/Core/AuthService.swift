import Foundation
import Observation

// =============================================================================
// AuthService — signing in with Supabase Auth (GoTrue) over plain REST.
//
// Spec 3.2:
//   * Sign in:  POST /auth/v1/token?grant_type=password       {email, password}
//   * Refresh:  POST /auth/v1/token?grant_type=refresh_token  {refresh_token}
//   * Sign out: POST /auth/v1/logout (best effort), then forget the tokens
//   * Refresh proactively when the token expires within 60 s; on a refused
//     refresh → signed out.
//   * After sign-in, RPC `is_app_admin` decides between the main app and the
//     "Account not authorized" screen.
//
// Spec 9 (Change password):
//   * Re-check the current password: POST /auth/v1/token?grant_type=password
//   * Set the new one:               PUT  /auth/v1/user  {password}
//
// The session is saved in the Keychain so you stay signed in between launches.
// =============================================================================

/// Where the sign-in gate is. RootView switches on this.
enum AuthState: Equatable, Sendable {
    /// Secrets.xcconfig is missing → "Not configured" screen.
    case notConfigured
    /// Launching: restoring a saved session / checking access.
    case restoring
    /// Show the sign-in screen.
    case signedOut
    /// Signed in, but this login isn't in `app_admins`.
    case notAuthorized(email: String)
    /// Signed in, but the admin check couldn't reach the server.
    case accessCheckFailed(email: String, message: String)
    /// Signed in and allowed in.
    case ready(email: String)
}

/// The tokens we keep between launches.
struct AuthSession: Codable, Equatable, Sendable {
    var accessToken: String
    var refreshToken: String
    var expiresAt: Date
    var userID: String?
    var email: String?
}

/// Result of trying to refresh the access token.
enum RefreshOutcome: Equatable, Sendable {
    /// New tokens saved.
    case refreshed
    /// The server refused the refresh token → we're now signed out.
    case rejected
    /// Couldn't reach the server; the old session is kept.
    case failed(String)
}

@Observable
@MainActor
final class AuthService {

    // MARK: - Observable state

    private(set) var state: AuthState = .restoring
    private(set) var session: AuthSession?
    /// True while the Sign in button's request is running.
    private(set) var isSigningIn = false
    /// Shown under the sign-in form.
    var signInError: String?
    /// True while `checkAccess()` runs (the Retry button's spinner).
    private(set) var isCheckingAccess = false

    /// The signed-in email, if any.
    var email: String? { session?.email }

    // MARK: - Dependencies

    let config: AppConfig
    private let keychain: KeychainStore
    private let urlSession: URLSession
    @ObservationIgnored private var refreshTask: Task<RefreshOutcome, Never>?

    private static let sessionAccount = "supabase-session"

    init(config: AppConfig, keychain: KeychainStore = KeychainStore(), urlSession: URLSession = .shared) {
        self.config = config
        self.keychain = keychain
        self.urlSession = urlSession
        self.state = config.isConfigured ? .restoring : .notConfigured
    }

    // MARK: - Launch

    /// Called once at launch: restore the saved session (refreshing it if it
    /// is about to expire) and check access.
    func bootstrap() async {
        guard config.isConfigured else {
            state = .notConfigured
            return
        }
        guard let stored = loadStoredSession() else {
            state = .signedOut
            return
        }
        session = stored
        state = .restoring

        if stored.expiresAt.timeIntervalSinceNow < 60 {
            let outcome = await refresh()
            if outcome == .rejected { return } // already signed out
        }
        await checkAccess()
    }

    // MARK: - Sign in / out

    func signIn(email: String, password: String) async {
        let trimmedEmail = email.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedEmail.isEmpty, !password.isEmpty else {
            signInError = "Enter your email and password."
            return
        }
        guard config.isConfigured else {
            state = .notConfigured
            return
        }

        isSigningIn = true
        signInError = nil
        defer { isSigningIn = false }

        do {
            let response = try await tokenRequest(
                grantType: "password",
                body: ["email": trimmedEmail, "password": password]
            )
            save(makeSession(from: response, fallbackEmail: trimmedEmail))
            await checkAccess()
        } catch {
            signInError = APIError.describe(error)
        }
    }

    /// Best-effort server logout, then forget the tokens.
    func signOut() async {
        if let token = session?.accessToken,
           let url = endpoint("auth/v1/logout") {
            var request = URLRequest(url: url)
            request.httpMethod = "POST"
            request.timeoutInterval = 10
            request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            _ = try? await urlSession.data(for: request)
        }
        signOutLocally()
    }

    // MARK: - Change password (spec 9)

    /// Changes the signed-in user's password in two steps:
    ///
    ///   1. Sign in again with the current password. This proves the user
    ///      knows it (and satisfies Supabase's optional "recent login
    ///      required" rule). The fresh session is saved like any sign-in.
    ///   2. PUT /auth/v1/user with the new password, using that fresh token.
    ///
    /// On failure it throws an `APIError` whose description is the message
    /// to show in the form (`APIError.describe(error)`). It never signs the
    /// user out — not even for a wrong current password.
    ///
    /// The passwords are only ever put in request bodies: never logged,
    /// never stored.
    func changePassword(current: String, new: String) async throws {
        guard let email = TextRules.trimmedOrNil(session?.email) else {
            throw APIError.http(status: 401, message: "Please sign in again.", code: nil)
        }

        // Step 1: re-check the current password by signing in again.
        let tokenData: Data
        let tokenStatus: Int
        do {
            (tokenData, tokenStatus) = try await sendTokenRequest(
                grantType: "password",
                body: ["email": email, "password": current]
            )
        } catch {
            throw Self.changePasswordFailure(error)
        }
        if tokenStatus == 400 {
            // GoTrue's "Invalid login credentials". The old session is
            // untouched, so the user stays signed in.
            throw APIError.http(
                status: tokenStatus,
                message: PasswordRules.incorrectCurrentMessage,
                code: APIError.serverMessage(from: tokenData).code
            )
        }
        guard (200..<300).contains(tokenStatus) else {
            throw Self.changePasswordFailure(status: tokenStatus, data: tokenData)
        }
        guard let fresh = try? decodeTokenResponse(tokenData) else {
            throw APIError.decoding(PasswordRules.genericFailureMessage)
        }
        // Store the fresh session exactly as a normal sign-in does.
        save(makeSession(from: fresh, fallbackEmail: email))

        // Step 2: set the new password, authorized by the fresh token.
        guard let url = endpoint("auth/v1/user") else { throw APIError.notConfigured }
        var request = URLRequest(url: url)
        request.httpMethod = "PUT"
        request.timeoutInterval = 30
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(fresh.accessToken)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(["password": new])

        let data: Data
        let status: Int
        do {
            (data, status) = try await send(request)
        } catch {
            throw Self.changePasswordFailure(error)
        }
        guard (200..<300).contains(status) else {
            // e.g. 422 with error_code weak_password / same_password.
            throw Self.changePasswordFailure(status: status, data: data)
        }
    }

    /// A refusal from the server: show its own message (422 weak_password /
    /// same_password say what to fix), or the generic one if it sent none.
    private static func changePasswordFailure(status: Int, data: Data) -> APIError {
        let parsed = APIError.serverMessage(from: data)
        return .http(status: status, message: parsed.message ?? PasswordRules.genericFailureMessage, code: parsed.code)
    }

    /// No answer at all (offline, timeout…): the generic message. A
    /// cancelled task stays a cancellation so the form can ignore it.
    private static func changePasswordFailure(_ error: Error) -> Error {
        if APIError.isCancellation(error) { return CancellationError() }
        return APIError.network(PasswordRules.genericFailureMessage)
    }

    // MARK: - Access check (is_app_admin)

    /// Asks the server whether this login is an app admin and moves to
    /// `.ready`, `.notAuthorized` or `.accessCheckFailed`.
    func checkAccess() async {
        guard let current = session else {
            state = config.isConfigured ? .signedOut : .notConfigured
            return
        }
        let email = current.email ?? "your account"
        isCheckingAccess = true
        defer { isCheckingAccess = false }

        do {
            let isAdmin = try await fetchIsAdmin(allowRefresh: true)
            state = isAdmin ? .ready(email: email) : .notAuthorized(email: email)
        } catch {
            if session == nil {
                // The refresh token was refused while checking.
                signOutLocally()
            } else {
                state = .accessCheckFailed(email: email, message: APIError.describe(error))
            }
        }
    }

    private func fetchIsAdmin(allowRefresh: Bool) async throws -> Bool {
        guard let url = endpoint("rest/v1/rpc/is_app_admin") else { throw APIError.notConfigured }
        guard let token = await validAccessToken() else {
            throw APIError.http(status: 401, message: "Please sign in again.", code: nil)
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = Data("{}".utf8)

        let (data, status) = try await send(request)

        if status == 401 && allowRefresh {
            let outcome = await refresh()
            switch outcome {
            case .refreshed:
                return try await fetchIsAdmin(allowRefresh: false)
            case .rejected:
                throw APIError.http(status: 401, message: "Your session expired. Please sign in again.", code: nil)
            case .failed(let message):
                throw APIError.network(message)
            }
        }
        guard (200..<300).contains(status) else {
            throw APIError.fromResponse(status: status, data: data)
        }
        guard let value = JSONScalar.bool(from: data) else {
            throw APIError.decoding("Unexpected answer from is_app_admin.")
        }
        return value
    }

    // MARK: - Tokens (used by APIClient)

    /// The current access token, refreshed first if it expires within 60 s.
    /// nil when signed out.
    func validAccessToken() async -> String? {
        guard let current = session else { return nil }
        if current.expiresAt.timeIntervalSinceNow < 60 {
            _ = await refresh()
        }
        return session?.accessToken
    }

    /// Refreshes the session. Concurrent callers share one network request,
    /// so two screens hitting a 401 at once don't burn the refresh token twice.
    @discardableResult
    func refresh() async -> RefreshOutcome {
        if let running = refreshTask {
            return await running.value
        }
        let task = Task { await self.performRefresh() }
        refreshTask = task
        let outcome = await task.value
        refreshTask = nil
        return outcome
    }

    private func performRefresh() async -> RefreshOutcome {
        guard let current = session else { return .rejected }
        do {
            let response = try await tokenRequest(
                grantType: "refresh_token",
                body: ["refresh_token": current.refreshToken]
            )
            save(makeSession(from: response, fallbackEmail: current.email))
            return .refreshed
        } catch APIError.http(let status, let message, _) where (400..<500).contains(status) && status != 429 {
            // 400/401/403: the refresh token is invalid or revoked.
            signOutLocally()
            signInError = message.isEmpty ? nil : "Signed out: \(message)"
            return .rejected
        } catch {
            return .failed(APIError.describe(error))
        }
    }

    // MARK: - Networking helpers

    private struct TokenResponse: Decodable {
        let accessToken: String
        let refreshToken: String
        let expiresIn: Double?
        let expiresAt: Double?
        let user: TokenUser?

        struct TokenUser: Decodable {
            let id: String?
            let email: String?
        }

        enum CodingKeys: String, CodingKey {
            case accessToken = "access_token"
            case refreshToken = "refresh_token"
            case expiresIn = "expires_in"
            case expiresAt = "expires_at"
            case user
        }
    }

    private func tokenRequest(grantType: String, body: [String: String]) async throws -> TokenResponse {
        let (data, status) = try await sendTokenRequest(grantType: grantType, body: body)
        guard (200..<300).contains(status) else {
            throw APIError.fromResponse(status: status, data: data)
        }
        return try decodeTokenResponse(data)
    }

    /// POST /auth/v1/token and return (body, HTTP status) without judging
    /// the status — `tokenRequest` and `changePassword` each decide what a
    /// failure means.
    private func sendTokenRequest(grantType: String, body: [String: String]) async throws -> (Data, Int) {
        guard let url = endpoint("auth/v1/token", query: [URLQueryItem(name: "grant_type", value: grantType)]) else {
            throw APIError.notConfigured
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        // GoTrue only needs the apikey header here (and the newer
        // sb_publishable_ keys must NOT be sent as a Bearer token).
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.httpBody = try JSONEncoder().encode(body)
        return try await send(request)
    }

    private func decodeTokenResponse(_ data: Data) throws -> TokenResponse {
        do {
            return try JSONDecoder().decode(TokenResponse.self, from: data)
        } catch {
            throw APIError.decoding("Unexpected sign-in response from Supabase.")
        }
    }

    /// Runs a request and returns (body, HTTP status). Transport failures
    /// become `APIError.network`; cancellation is passed through.
    private func send(_ request: URLRequest) async throws -> (Data, Int) {
        do {
            let (data, response) = try await urlSession.data(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 0
            return (data, status)
        } catch {
            if APIError.isCancellation(error) { throw CancellationError() }
            throw APIError.network(APIError.describe(error))
        }
    }

    private func endpoint(_ path: String, query: [URLQueryItem] = []) -> URL? {
        guard let base = config.supabaseURL else { return nil }
        guard var components = URLComponents(url: base.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            return nil
        }
        if !query.isEmpty { components.queryItems = query }
        return components.url
    }

    private func makeSession(from response: TokenResponse, fallbackEmail: String?) -> AuthSession {
        let expiresAt: Date
        if let absolute = response.expiresAt {
            expiresAt = Date(timeIntervalSince1970: absolute)
        } else {
            expiresAt = Date().addingTimeInterval(response.expiresIn ?? 3600)
        }
        return AuthSession(
            accessToken: response.accessToken,
            refreshToken: response.refreshToken,
            expiresAt: expiresAt,
            userID: response.user?.id,
            email: TextRules.trimmedOrNil(response.user?.email) ?? fallbackEmail
        )
    }

    // MARK: - Persistence

    private func save(_ newSession: AuthSession) {
        session = newSession
        if let data = try? JSONEncoder().encode(newSession) {
            keychain.set(data, for: Self.sessionAccount)
        }
    }

    private func loadStoredSession() -> AuthSession? {
        guard let data = keychain.data(for: Self.sessionAccount) else { return nil }
        return try? JSONDecoder().decode(AuthSession.self, from: data)
    }

    private func signOutLocally() {
        session = nil
        keychain.remove(Self.sessionAccount)
        state = config.isConfigured ? .signedOut : .notConfigured
    }
}
