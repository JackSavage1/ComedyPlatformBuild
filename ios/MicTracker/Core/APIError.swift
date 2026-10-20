import Foundation

// =============================================================================
// APIError — every way a request to Supabase can fail, with a message that
// is safe to show the user (spec 3.1: "Surface `message` to the user").
// =============================================================================
enum APIError: LocalizedError, Equatable {
    /// SUPABASE_URL / SUPABASE_ANON_KEY are missing.
    case notConfigured
    /// The server answered with a non-2xx status. `message` is the server's
    /// own text (PostgREST `message`, GoTrue `msg` / `error_description`).
    case http(status: Int, message: String, code: String?)
    /// No response at all (offline, timeout, DNS…).
    case network(String)
    /// The response arrived but wasn't the JSON we expected.
    case decoding(String)
    /// Anything else.
    case unexpected(String)

    var errorDescription: String? {
        switch self {
        case .notConfigured:
            return "The app isn't configured with a Supabase URL and key."
        case let .http(status, message, _):
            if status == 401 {
                return message.isEmpty ? "Your session expired. Please sign in again." : message
            }
            return message.isEmpty ? "Server error (HTTP \(status))." : message
        case let .network(message):
            return message
        case let .decoding(message):
            return message
        case let .unexpected(message):
            return message
        }
    }

    /// Builds an `.http` error from a failed response body.
    static func fromResponse(status: Int, data: Data) -> APIError {
        let parsed = serverMessage(from: data)
        let fallback = HTTPURLResponse.localizedString(forStatusCode: status).capitalized
        return .http(status: status, message: parsed.message ?? "\(fallback) (HTTP \(status))", code: parsed.code)
    }

    /// Pulls a human message and code out of PostgREST / GoTrue error JSON:
    ///   PostgREST: {"message": "...", "code": "42501", "details": ..., "hint": ...}
    ///   GoTrue:    {"msg": "...", "error_code": "..."} or
    ///              {"error": "invalid_grant", "error_description": "..."}
    static func serverMessage(from data: Data) -> (message: String?, code: String?) {
        guard !data.isEmpty else { return (nil, nil) }

        if let object = try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]),
           let dictionary = object as? [String: Any] {
            var message: String?
            for key in ["message", "msg", "error_description", "error"] {
                if let text = dictionary[key] as? String, let cleaned = TextRules.trimmedOrNil(text) {
                    message = cleaned
                    break
                }
            }
            var code: String?
            if let text = dictionary["code"] as? String {
                code = text
            } else if let text = dictionary["error_code"] as? String {
                code = text
            } else if let number = dictionary["code"] as? Int {
                code = String(number)
            }
            return (message, code)
        }

        // Not JSON: show a short plain-text body if there is one.
        let text = String(decoding: data, as: UTF8.self)
        guard let cleaned = TextRules.trimmedOrNil(text), cleaned.count <= 300 else { return (nil, nil) }
        return (cleaned, nil)
    }

    /// A user-facing description of any error.
    static func describe(_ error: Error) -> String {
        if let apiError = error as? APIError, let text = apiError.errorDescription {
            return text
        }
        if let urlError = error as? URLError {
            return urlError.localizedDescription
        }
        return error.localizedDescription
    }

    /// True for errors caused by a cancelled task (a view went away, or a
    /// pull-to-refresh was interrupted). These are never shown to the user.
    static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        if let urlError = error as? URLError, urlError.code == .cancelled { return true }
        return false
    }
}

// =============================================================================
// JSONScalar — reads the plain JSON values RPC functions return
// (`true`, `42`, `null`) without relying on top-level-fragment support in
// JSONDecoder.
// =============================================================================
enum JSONScalar {

    static func bool(from data: Data) -> Bool? {
        guard let object = jsonObject(data) else {
            switch plainText(data) {
            case "true", "t": return true
            case "false", "f": return false
            default: return nil
            }
        }
        if let value = object as? Bool { return value }
        if let value = object as? NSNumber { return value.boolValue }
        if let value = object as? String {
            switch value.lowercased() {
            case "true", "t": return true
            case "false", "f": return false
            default: return nil
            }
        }
        // Some setups wrap scalar results: [true] or [{"fn": true}].
        if let array = object as? [Any], let first = array.first {
            if let value = first as? Bool { return value }
            if let dictionary = first as? [String: Any], dictionary.count == 1, let value = dictionary.values.first as? Bool {
                return value
            }
        }
        return nil
    }

    static func int(from data: Data) -> Int? {
        guard let object = jsonObject(data) else {
            return Int(plainText(data))
        }
        if let value = object as? NSNumber {
            // JSON true/false also arrive as NSNumber; they aren't ids.
            if CFGetTypeID(value) == CFBooleanGetTypeID() { return nil }
            return LenientNumber.int(from: value.doubleValue)
        }
        if let value = object as? String { return LenientNumber.int(from: value) }
        if let array = object as? [Any], let first = array.first {
            if let value = first as? NSNumber { return LenientNumber.int(from: value.doubleValue) }
            if let dictionary = first as? [String: Any], dictionary.count == 1,
               let value = dictionary.values.first as? NSNumber {
                return LenientNumber.int(from: value.doubleValue)
            }
        }
        return nil
    }

    private static func jsonObject(_ data: Data) -> Any? {
        guard !data.isEmpty else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    private static func plainText(_ data: Data) -> String {
        String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
