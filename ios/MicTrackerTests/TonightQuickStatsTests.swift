import XCTest
@testable import MicTracker

// Tests for Tonight's quick stats (spec 6.2), also used by the Map's stats strip.
final class TonightQuickStatsTests: XCTestCase {

    func testNeighborhoodCountIgnoresBlanksAndDuplicates() {
        let mics = [
            Mic(id: 1, neighborhood: "East Village"),
            Mic(id: 2, neighborhood: " East Village "),
            Mic(id: 3, neighborhood: "Bushwick"),
            Mic(id: 4, neighborhood: "   "),
            Mic(id: 5, neighborhood: nil),
        ]
        XCTAssertEqual(TonightQuickStats.neighborhoodCount(mics), 2)
    }

    func testBoroughCount() {
        let mics = [
            Mic(id: 1, borough: "Manhattan"),
            Mic(id: 2, borough: "Brooklyn"),
            Mic(id: 3, borough: "Brooklyn"),
            Mic(id: 4, borough: nil),
        ]
        XCTAssertEqual(TonightQuickStats.boroughCount(mics), 2)
    }

    func testTopBoroughPicksTheMostCommon() {
        let mics = [
            Mic(id: 1, borough: "Manhattan"),
            Mic(id: 2, borough: "Brooklyn"),
            Mic(id: 3, borough: "Brooklyn"),
            Mic(id: 4, borough: ""),
            Mic(id: 5, borough: nil),
        ]
        XCTAssertEqual(TonightQuickStats.topBorough(mics), "Brooklyn")
        XCTAssertEqual(TonightQuickStats.topBoroughText(mics), "Brooklyn")
    }

    func testTopBoroughTieGoesToAlphabeticallyFirst() {
        let mics = [
            Mic(id: 1, borough: "Queens"),
            Mic(id: 2, borough: "Brooklyn"),
        ]
        XCTAssertEqual(TonightQuickStats.topBorough(mics), "Brooklyn")
    }

    func testTopBoroughIsNAWithoutBoroughs() {
        XCTAssertNil(TonightQuickStats.topBorough([]))
        XCTAssertEqual(TonightQuickStats.topBoroughText([Mic(id: 1, borough: nil)]), "N/A")
    }
}
