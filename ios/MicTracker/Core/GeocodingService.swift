import CoreLocation
import Foundation

// =============================================================================
// GeocodingService — turns a street address into map coordinates on the
// device with Apple's CLGeocoder (spec 6.8 / 6.9).
//
// * Tries "{address}, {borough}, New York, NY", then "{address}, New York, NY".
// * Accepts only results inside NYC (rule 18) — "5th Ave" exists in many cities.
// * Waits at least 1 second between requests; Apple throttles geocoding.
// =============================================================================
@MainActor
final class GeocodingService {
    private let geocoder = CLGeocoder()
    private var lastRequestAt: Date?
    private let minimumInterval: TimeInterval = 1.0

    /// Biases results toward the five boroughs.
    private let nycRegion = CLCircularRegion(
        center: CLLocationCoordinate2D(latitude: 40.7128, longitude: -73.9500),
        radius: 60_000,
        identifier: "nyc"
    )

    init() {}

    /// The first in-NYC coordinate for the address, or nil.
    func geocode(address: String?, borough: String?) async -> GeoPoint? {
        for query in GeoRules.geocodeQueries(address: address, borough: borough) {
            if Task.isCancelled { return nil }
            if let point = await geocodeOnce(query) {
                return point
            }
        }
        return nil
    }

    private func geocodeOnce(_ query: String) async -> GeoPoint? {
        await waitForRateLimit()
        lastRequestAt = Date()

        let geocoder = self.geocoder
        let region = self.nycRegion
        return await withCheckedContinuation { (continuation: CheckedContinuation<GeoPoint?, Never>) in
            geocoder.geocodeAddressString(query, in: region, preferredLocale: nil) { placemarks, _ in
                // Pick the first result that is actually in NYC.
                var match: GeoPoint?
                for placemark in placemarks ?? [] {
                    guard let coordinate = placemark.location?.coordinate else { continue }
                    let point = GeoPoint(latitude: coordinate.latitude, longitude: coordinate.longitude)
                    if GeoRules.isInNYC(point) {
                        match = point
                        break
                    }
                }
                continuation.resume(returning: match)
            }
        }
    }

    private func waitForRateLimit() async {
        guard let last = lastRequestAt else { return }
        let elapsed = Date().timeIntervalSince(last)
        if elapsed < minimumInterval {
            let nanoseconds = UInt64((minimumInterval - elapsed) * 1_000_000_000)
            try? await Task.sleep(nanoseconds: nanoseconds)
        }
    }
}
