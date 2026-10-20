import XCTest
@testable import MicTracker

// Tests for the Map tab's pure logic (spec 6.5).
final class MicMapTests: XCTestCase {

    // MARK: - Zoom presets

    func testPresetSpansFollowTheZoomFormula() {
        // 360 / 2^10.5 ≈ 0.2486° of longitude for "All NYC".
        XCTAssertEqual(MicMapZoomPreset.allNYC.longitudeSpan, 360.0 / pow(2.0, 10.5), accuracy: 1e-12)
        XCTAssertEqual(MicMapZoomPreset.allNYC.longitudeSpan, 0.2486, accuracy: 0.0001)
        XCTAssertEqual(MicMapZoomPreset.brooklyn.longitudeSpan, 360.0 / 4096.0, accuracy: 1e-12)
        // Latitude span is shorter (same ground distance at NYC's latitude).
        XCTAssertLessThan(MicMapZoomPreset.manhattan.latitudeSpan, MicMapZoomPreset.manhattan.longitudeSpan)
        XCTAssertGreaterThan(MicMapZoomPreset.manhattan.latitudeSpan, 0)
    }

    func testPresetCentersMatchTheWebApp() {
        XCTAssertEqual(MicMapZoomPreset.allNYC.latitude, 40.7128)
        XCTAssertEqual(MicMapZoomPreset.allNYC.longitude, -73.9500)
        XCTAssertEqual(MicMapZoomPreset.queensBronx.title, "Queens/Bronx")
        XCTAssertEqual(MicMapZoomPreset.allCases.map { $0.title }, ["All NYC", "Manhattan", "Brooklyn", "Queens/Bronx"])
    }

    func testPresetCenteredCheck() {
        let preset = MicMapZoomPreset.manhattan
        XCTAssertTrue(preset.isCentered(latitude: 40.7580, longitude: -73.9855))
        XCTAssertTrue(preset.isCentered(latitude: 40.7581, longitude: -73.9856))
        XCTAssertFalse(preset.isCentered(latitude: 40.70, longitude: -73.9855))
    }

    // MARK: - Mappable mics

    func testOnlyActiveMicsWithValidCoordinatesAreMappable() {
        let mics = [
            Mic(id: 1, latitude: 40.72, longitude: -73.98),
            Mic(id: 2, latitude: nil, longitude: -73.98),
            Mic(id: 3, latitude: 40.72, longitude: -73.98, isActive: false),
            Mic(id: 4, latitude: 0, longitude: 0),
            Mic(id: 5, latitude: 123, longitude: -73.98),
        ]
        XCTAssertEqual(MicMapRules.mappableMics(mics).map { $0.id }, [1])
    }

    // MARK: - Filter

    func testDayAndBoroughFilters() {
        let mics = [
            Mic(id: 1, borough: "Manhattan", dayOfWeek: "Monday"),
            Mic(id: 2, borough: "brooklyn", dayOfWeek: "Monday"),
            Mic(id: 3, borough: "Brooklyn", dayOfWeek: "Friday"),
            Mic(id: 4, borough: nil, dayOfWeek: "junk"),
        ]
        var filter = MicMapFilter()
        XCTAssertFalse(filter.isActive)
        XCTAssertEqual(mics.filter { filter.matches($0) }.count, 4)

        filter.toggle(day: .monday)
        XCTAssertTrue(filter.isActive)
        XCTAssertEqual(mics.filter { filter.matches($0) }.map { $0.id }, [1, 2])

        filter.toggle(borough: "Brooklyn")
        XCTAssertEqual(mics.filter { filter.matches($0) }.map { $0.id }, [2])

        filter.toggle(day: .monday)
        XCTAssertEqual(mics.filter { filter.matches($0) }.map { $0.id }, [2, 3])

        filter.reset()
        XCTAssertEqual(filter, MicMapFilter())
    }

    // MARK: - Helpers

    func testMicsSharingALocation() {
        let a = Mic(id: 1, name: "A", dayOfWeek: "Tuesday", startTime: "20:00", latitude: 40.7, longitude: -73.9)
        let b = Mic(id: 2, name: "B", dayOfWeek: "Monday", startTime: "19:00", latitude: 40.70001, longitude: -73.90001)
        let c = Mic(id: 3, name: "C", dayOfWeek: "Monday", startTime: "19:00", latitude: 40.71, longitude: -73.9)
        XCTAssertEqual(MicMapRules.micsSharingLocation(with: a, in: [a, b, c]).map { $0.id }, [2])
    }

    func testOtherBoroughDetection() {
        XCTAssertFalse(MicMapRules.hasOtherBorough([Mic(id: 1, borough: "Queens")]))
        XCTAssertTrue(MicMapRules.hasOtherBorough([Mic(id: 1, borough: "Queens"), Mic(id: 2, borough: "Jersey City")]))
        XCTAssertTrue(MicMapRules.hasOtherBorough([Mic(id: 1, borough: nil)]))
    }

    func testNextDateUsesRule16() {
        let tuesday = CalendarDate(year: 2026, month: 10, day: 20)
        XCTAssertEqual(MicMapRules.nextDate(for: Mic(id: 1, dayOfWeek: "Tuesday"), today: tuesday), tuesday)
        XCTAssertEqual(
            MicMapRules.nextDate(for: Mic(id: 1, dayOfWeek: "Monday"), today: tuesday),
            CalendarDate(year: 2026, month: 10, day: 26)
        )
        XCTAssertNil(MicMapRules.nextDate(for: Mic(id: 1, dayOfWeek: "someday"), today: tuesday))
    }
}
