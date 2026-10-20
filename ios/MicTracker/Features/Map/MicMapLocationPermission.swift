import Combine
import CoreLocation
import Foundation

// =============================================================================
// MicMapLocationPermission — tracks (and asks for) "When In Use" location
// permission for the Map's "My location" button (spec 6.5).
//
// Apple only lets an app see the user's location after asking once; the
// answer arrives later through a delegate callback, which we publish as
// `status` so the Map view can react (e.g. center on the user right after
// they tap "Allow"). The app keeps working normally if they say no.
//
// The Info.plist text for the prompt (NSLocationWhenInUseUsageDescription)
// is set in ios/project.yml.
// =============================================================================
final class MicMapLocationPermission: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var status: CLAuthorizationStatus

    private let manager: CLLocationManager

    override init() {
        let manager = CLLocationManager()
        self.manager = manager
        self.status = manager.authorizationStatus
        super.init()
        manager.delegate = self
    }

    /// Location is available to the map.
    var isAuthorized: Bool {
        status == .authorizedWhenInUse || status == .authorizedAlways
    }

    /// The user (or a device policy) said no; only Settings can change it.
    var isDenied: Bool {
        status == .denied || status == .restricted
    }

    /// Shows the system "Allow location?" prompt (only does anything the
    /// first time — after that iOS remembers the answer).
    func requestWhenInUse() {
        manager.requestWhenInUseAuthorization()
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let newStatus = manager.authorizationStatus
        if Thread.isMainThread {
            status = newStatus
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.status = newStatus
            }
        }
    }
}
