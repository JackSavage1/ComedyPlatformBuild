import XCTest
@testable import MicTracker

// =============================================================================
// Tests for the Manage screen's pure helpers (Features/Manage/ManageLogic.swift,
// spec 6.8).
// =============================================================================
final class ManageTests: XCTestCase {

    func testScrapedGroupsKeepArrivalOrderAndUseDisplayNames() {
        let listings = [
            ScrapedMic(id: 1, source: "badslava", name: "A", venue: "V1", dayOfWeek: "Monday"),
            ScrapedMic(id: 2, source: "badslava", name: "B", venue: "V2", dayOfWeek: "Tuesday"),
            ScrapedMic(id: 3, source: "firemics", name: "C", venue: "V3", dayOfWeek: "Friday"),
        ]
        let groups = ManageLogic.scrapedGroups(listings)
        XCTAssertEqual(groups.map { $0.source }, ["badslava", "firemics"])
        XCTAssertEqual(groups.map { $0.title }, ["Bad Slava", "FireMics"])
        XCTAssertEqual(groups[0].listings.map { $0.id }, [1, 2])
        XCTAssertTrue(ManageLogic.scrapedGroups([]).isEmpty)
    }

    func testScrapedTitleAndDetails() {
        let full = ScrapedMic(id: 1, source: "badslava", name: "Laugh Lab", venue: "The Bar", dayOfWeek: "monday", displayTime: "7:00 PM", startTime: "19:00", cost: "$5")
        XCTAssertEqual(ManageLogic.scrapedTitle(full), "Monday — Laugh Lab @ The Bar")
        XCTAssertEqual(ManageLogic.scrapedDetails(full), "7:00 PM · $5")

        let sparse = ScrapedMic(id: 2, source: "firemics", name: "Mystery", venue: " ", dayOfWeek: nil, startTime: nil, cost: nil)
        XCTAssertEqual(ManageLogic.scrapedTitle(sparse), "Unknown day — Mystery")
        XCTAssertEqual(ManageLogic.scrapedDetails(sparse), "Free/TBD")
    }

    func testMicListLabel() {
        XCTAssertEqual(ManageLogic.micListLabel(Mic(id: 1, name: "Creek Mic", venue: "The Creek", dayOfWeek: "Monday")), "Mon — Creek Mic @ The Creek")
        XCTAssertEqual(ManageLogic.micListLabel(Mic(id: 2, name: "Solo", venue: "", dayOfWeek: "Sunday")), "Sun — Solo")
    }

    func testSearchMics() {
        let mics = [
            Mic(id: 1, name: "Creek Mic", venue: "The Creek", neighborhood: "LIC", borough: "Queens", dayOfWeek: "Monday"),
            Mic(id: 2, name: "Gutter Mic", venue: "The Gutter", neighborhood: "Williamsburg", borough: "Brooklyn", dayOfWeek: "Wednesday"),
        ]
        XCTAssertEqual(ManageLogic.searchMics(mics, query: "").map { $0.id }, [1, 2])
        XCTAssertEqual(ManageLogic.searchMics(mics, query: "  brook ").map { $0.id }, [2])
        XCTAssertEqual(ManageLogic.searchMics(mics, query: "creek").map { $0.id }, [1])
        XCTAssertEqual(ManageLogic.searchMics(mics, query: "monday").map { $0.id }, [1])
        XCTAssertTrue(ManageLogic.searchMics(mics, query: "zzz").isEmpty)
    }

    func testGeocodingCounts() {
        let mics = [
            Mic(id: 1, address: "1 Main St", latitude: 40.7, longitude: -73.9),
            Mic(id: 2, address: "2 Main St"),
            Mic(id: 3, address: "   "),
            Mic(id: 4),
        ]
        XCTAssertEqual(ManageLogic.coordinatesText(activeMics: mics), "1 / 4")
        let missing = mics.filter { !$0.hasCoordinates }
        XCTAssertEqual(ManageLogic.geocodableCount(missing), 1)
    }

    func testScrapeStatus() {
        XCTAssertEqual(ManageLogic.scrapeStatus("success"), .success)
        XCTAssertEqual(ManageLogic.scrapeStatus("Error: timeout"), .failure)
        XCTAssertEqual(ManageLogic.scrapeStatus("failed"), .failure)
        XCTAssertEqual(ManageLogic.scrapeStatus(nil), .other)
        XCTAssertEqual(ManageLogic.scrapeStatus("partial"), .other)
    }
}
