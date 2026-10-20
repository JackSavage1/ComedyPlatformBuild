import Foundation

// =============================================================================
// APIClient — a thin REST client for Supabase's PostgREST API (spec 3.1).
//
// PostgREST turns each table into a URL:
//   GET    /rest/v1/open_mics?select=*&order=id.asc        → rows as JSON
//   POST   /rest/v1/my_sets          (Prefer: return=representation)
//   PATCH  /rest/v1/my_sets?id=eq.5  (Prefer: return=representation)
//   DELETE /rest/v1/my_sets?id=eq.5
//   POST   /rest/v1/rpc/set_plan_status  {"p_mic_id": 1, ...}
//
// Every request carries the `apikey` header and, once signed in, the user's
// JWT as `Authorization: Bearer …`. If the server says 401 (expired JWT), we
// refresh the session once and retry the request once.
//
// Everything runs on the main actor: network waits don't block the UI
// (they're `await`s), and it keeps the code free of threading puzzles.
// =============================================================================
@MainActor
final class APIClient {
    private let config: AppConfig
    private let auth: AuthService
    private let urlSession: URLSession
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(config: AppConfig, auth: AuthService, urlSession: URLSession = .shared) {
        self.config = config
        self.auth = auth
        self.urlSession = urlSession
    }

    // MARK: - Tables

    /// GET rows. `query` holds PostgREST filters/ordering, e.g.
    /// `[URLQueryItem(name: "order", value: "id.asc")]`. `select=*` is added
    /// unless the query already has a `select`. Rows that fail to decode are
    /// skipped instead of failing the whole list.
    func select<Row: Decodable>(_ table: String, query: [URLQueryItem] = []) async throws -> [Row] {
        var items = query
        if !items.contains(where: { $0.name == "select" }) {
            items.insert(URLQueryItem(name: "select", value: "*"), at: 0)
        }
        let data = try await send("GET", path: "rest/v1/\(table)", query: items)
        return try decodeRows(Row.self, from: data)
    }

    /// POST a new row and return what the database stored.
    func insert<Row: Decodable, Body: Encodable>(_ table: String, body: Body) async throws -> [Row] {
        let data = try await send(
            "POST",
            path: "rest/v1/\(table)",
            body: try encode(body),
            prefer: "return=representation"
        )
        return try decodeRows(Row.self, from: data)
    }

    /// PATCH the row with this id and return the updated row(s). An empty
    /// result means nothing matched (wrong id, or not allowed by RLS).
    func update<Row: Decodable, Body: Encodable>(_ table: String, id: Int, body: Body) async throws -> [Row] {
        let data = try await send(
            "PATCH",
            path: "rest/v1/\(table)",
            query: [URLQueryItem(name: "id", value: "eq.\(id)")],
            body: try encode(body),
            prefer: "return=representation"
        )
        return try decodeRows(Row.self, from: data)
    }

    /// DELETE the row with this id.
    func delete(_ table: String, id: Int) async throws {
        _ = try await send(
            "DELETE",
            path: "rest/v1/\(table)",
            query: [URLQueryItem(name: "id", value: "eq.\(id)")]
        )
    }

    // MARK: - RPC (Postgres functions)

    /// POST /rest/v1/rpc/{function} with named arguments; returns the raw body.
    func rpc<Args: Encodable>(_ function: String, args: Args) async throws -> Data {
        try await send("POST", path: "rest/v1/rpc/\(function)", body: try encode(args))
    }

    /// For functions that return `void`.
    func rpcVoid<Args: Encodable>(_ function: String, args: Args) async throws {
        _ = try await rpc(function, args: args)
    }

    /// For functions that return `boolean`.
    func rpcBool<Args: Encodable>(_ function: String, args: Args) async throws -> Bool {
        let data = try await rpc(function, args: args)
        guard let value = JSONScalar.bool(from: data) else {
            throw APIError.decoding("Unexpected answer from \(function).")
        }
        return value
    }

    /// For functions that return an integer (e.g. a new row id).
    func rpcInt<Args: Encodable>(_ function: String, args: Args) async throws -> Int {
        let data = try await rpc(function, args: args)
        guard let value = JSONScalar.int(from: data) else {
            throw APIError.decoding("Unexpected answer from \(function).")
        }
        return value
    }

    // MARK: - Core request

    private func send(
        _ method: String,
        path: String,
        query: [URLQueryItem] = [],
        body: Data? = nil,
        prefer: String? = nil,
        isRetry: Bool = false
    ) async throws -> Data {
        guard config.isConfigured, let baseURL = config.supabaseURL else { throw APIError.notConfigured }
        guard var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw APIError.unexpected("Couldn't build a URL for \(path).")
        }
        if !query.isEmpty { components.queryItems = query }
        guard let url = components.url else {
            throw APIError.unexpected("Couldn't build a URL for \(path).")
        }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.timeoutInterval = 30
        request.setValue(config.anonKey, forHTTPHeaderField: "apikey")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = body
        }
        if let prefer = prefer {
            request.setValue(prefer, forHTTPHeaderField: "Prefer")
        }
        if let token = await auth.validAccessToken() {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        } else if config.anonKeyIsJWT {
            request.setValue("Bearer \(config.anonKey)", forHTTPHeaderField: "Authorization")
        }

        let result: (Data, URLResponse)
        do {
            result = try await urlSession.data(for: request)
        } catch {
            if APIError.isCancellation(error) { throw CancellationError() }
            throw APIError.network(APIError.describe(error))
        }
        let data = result.0
        let status = (result.1 as? HTTPURLResponse)?.statusCode ?? 0

        // Expired JWT: refresh once, retry once.
        if status == 401, !isRetry, auth.session != nil {
            if await auth.refresh() == .refreshed {
                return try await send(method, path: path, query: query, body: body, prefer: prefer, isRetry: true)
            }
        }

        guard (200..<300).contains(status) else {
            throw APIError.fromResponse(status: status, data: data)
        }
        return data
    }

    // MARK: - JSON

    private func encode<Body: Encodable>(_ body: Body) throws -> Data {
        do {
            return try encoder.encode(body)
        } catch {
            throw APIError.unexpected("Couldn't prepare the request (\(error.localizedDescription)).")
        }
    }

    private func decodeRows<Row: Decodable>(_ type: Row.Type, from data: Data) throws -> [Row] {
        // 204 No Content / empty body → no rows.
        if data.isEmpty { return [] }
        do {
            let wrapped = try decoder.decode([LossyDecodable<Row>].self, from: data)
            let rows = wrapped.compactMap { $0.value }
            #if DEBUG
            if rows.count != wrapped.count {
                print("APIClient: skipped \(wrapped.count - rows.count) undecodable \(Row.self) row(s)")
            }
            #endif
            return rows
        } catch {
            throw APIError.decoding("Couldn't read the server's response (\(error.localizedDescription)).")
        }
    }
}
