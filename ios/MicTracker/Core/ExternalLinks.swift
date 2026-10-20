import Foundation

// =============================================================================
// ExternalLinks — URLs that leave the app: Maps, Instagram, sign-up pages,
// recordings. Views open them with `Link` or `@Environment(\.openURL)`.
// =============================================================================
enum ExternalLinks {

    /// The text Maps should search for: "123 Main St, Brooklyn, NY".
    static func mapsQuery(address: String, borough: String?) -> String {
        if let borough = TextRules.trimmedOrNil(borough) {
            return "\(address), \(borough), NY"
        }
        return "\(address), New York, NY"
    }

    /// Opens Apple Maps searching for the address.
    static func appleMapsURL(address: String, borough: String? = nil) -> URL? {
        var components = URLComponents(string: "https://maps.apple.com/")
        components?.queryItems = [URLQueryItem(name: "q", value: mapsQuery(address: address, borough: borough))]
        return components?.url
    }

    /// Opens Google Maps (app or website) searching for the address.
    static func googleMapsURL(address: String, borough: String? = nil) -> URL? {
        var components = URLComponents(string: "https://www.google.com/maps/search/")
        components?.queryItems = [
            URLQueryItem(name: "api", value: "1"),
            URLQueryItem(name: "query", value: mapsQuery(address: address, borough: borough)),
        ]
        return components?.url
    }

    /// Apple Maps for a mic's address (nil when it has none).
    static func appleMapsURL(for mic: Mic) -> URL? {
        guard let address = TextRules.trimmedOrNil(mic.address) else { return nil }
        return appleMapsURL(address: address, borough: mic.borough)
    }

    /// Rule 9: https://instagram.com/{handle} for a valid handle.
    static func instagramURL(for mic: Mic) -> URL? {
        guard let handle = mic.instagramHandle else { return nil }
        return InstagramRules.profileURL(handle: handle)
    }

    /// Rule 8 sign-up link.
    static func signupURL(for mic: Mic) -> URL? {
        mic.signupLink
    }

    /// Venue website (same normalization as sign-up links).
    static func venueURL(for mic: Mic) -> URL? {
        mic.venueLink
    }

    /// A set's recording link (same normalization as sign-up links).
    static func recordingURL(for entry: SetEntry) -> URL? {
        SignupRules.signupURL(from: entry.recordingUrl)
    }
}
