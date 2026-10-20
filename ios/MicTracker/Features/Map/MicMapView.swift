import CoreLocation
import MapKit
import SwiftUI
import UIKit

// =============================================================================
// MicMapView — the Map tab (spec 6.5, port of pages/5_🗺️_Map.py).
//
//   [All days] [Mon] [Tue] …            day chips (multi-select)
//   [All boroughs] [Manhattan] …        borough chips (multi-select)
//   [All NYC] [Manhattan] [Brooklyn] [Queens/Bronx]   zoom presets
//   ┌──────────── map ────────────┐
//   │ Mics on map · Neighborhoods · Boroughs          │
//   │   ● pins colored by borough (rule 17)           │
//   │                                   (◎ My location)
//   │ legend  — or the tapped mic's card → Details    │
//   └─────────────────────────────────┘
//
// Only active mics with coordinates are shown. The toolbar menu switches
// between Standard / Muted / Hybrid MapKit styles.
// =============================================================================
@MainActor
struct MicMapView: View {
    @Environment(AppStore.self) private var store
    @Environment(\.openURL) private var openURL
    @StateObject private var locationPermission = MicMapLocationPermission()

    /// Where the map camera is. Starts on "All NYC".
    @State private var position: MapCameraPosition = .region(MicMapZoomPreset.allNYC.region)
    /// The highlighted preset (nil once the user pans away from it).
    @State private var zoomPreset: MicMapZoomPreset? = .allNYC
    @State private var filter = MicMapFilter()
    @State private var styleOption: MicMapStyleOption = .standard
    @State private var selectedMicID: Int?
    /// Set when "My location" asked for permission, so we can center on the
    /// user as soon as they allow it.
    @State private var isWaitingForLocationPermission = false
    @State private var isShowingLocationAlert = false

    var body: some View {
        let mappable = MicMapRules.mappableMics(store.activeMics)
        let visible = mappable.filter { filter.matches($0) }

        NavigationStack {
            VStack(spacing: 0) {
                controlsPanel
                mapArea(mappable: mappable, visible: visible)
            }
            .navigationTitle("Map")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    styleMenu
                }
            }
            .onChange(of: filter) { _, newFilter in
                // Drop the selection if its pin was just filtered away.
                if let id = selectedMicID,
                   let mic = store.micsByID[id],
                   !newFilter.matches(mic) {
                    selectedMicID = nil
                }
            }
            .onChange(of: locationPermission.status) { _, _ in
                handlePermissionChange()
            }
            .alert("Location is off", isPresented: $isShowingLocationAlert) {
                Button("Open Settings") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        openURL(url)
                    }
                }
                Button("OK", role: .cancel) {}
            } message: {
                Text("To see where you are on the map, allow location access for Mic Tracker in Settings. Everything else works without it.")
            }
        }
    }

    // MARK: - Controls above the map

    private var controlsPanel: some View {
        VStack(alignment: .leading, spacing: 8) {
            chipRow {
                MicMapFilterChip(title: "All days", isOn: filter.days.isEmpty) {
                    filter.days = []
                }
                ForEach(Weekday.allCases) { day in
                    MicMapFilterChip(title: day.shortName, isOn: filter.days.contains(day)) {
                        filter.toggle(day: day)
                    }
                }
            }
            chipRow {
                MicMapFilterChip(title: "All boroughs", isOn: filter.boroughs.isEmpty) {
                    filter.boroughs = []
                }
                ForEach(BoroughRules.all, id: \.self) { borough in
                    MicMapFilterChip(
                        title: borough,
                        dotColor: Theme.boroughColor(borough),
                        isOn: filter.boroughs.contains(borough)
                    ) {
                        filter.toggle(borough: borough)
                    }
                }
            }
            MicMapPresetBar(selected: zoomPreset) { preset in
                apply(preset)
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 8)
        .background(Theme.groupedBackground)
    }

    /// A horizontally scrolling row of chips.
    private func chipRow<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                content()
            }
            .padding(.horizontal, 12)
        }
    }

    // MARK: - Map

    @ViewBuilder
    private func mapArea(mappable: [Mic], visible: [Mic]) -> some View {
        if store.hasLoadedOnce && mappable.isEmpty {
            // Nothing has coordinates yet: point to Manage → Geocoding.
            VStack {
                Spacer(minLength: 0)
                EmptyStateView(
                    title: "No mics on the map yet",
                    message: "None of your mics have map coordinates. Tap the gear on the Tonight or Calendar tab, then Manage → Geocoding → Geocode with device.",
                    systemImage: "map"
                )
                Spacer(minLength: 0)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(Theme.groupedBackground)
        } else {
            mapView(visible: visible)
                .overlay(alignment: .top) {
                    MicMapStatsStrip(
                        micCount: visible.count,
                        neighborhoodCount: TonightQuickStats.neighborhoodCount(visible),
                        boroughCount: TonightQuickStats.boroughCount(visible)
                    )
                    .padding(.horizontal, 12)
                    .padding(.top, 8)
                }
                .overlay {
                    if store.hasLoadedOnce && visible.isEmpty {
                        noMatchesCard
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    bottomPanel(visible: visible)
                }
        }
    }

    private func mapView(visible: [Mic]) -> some View {
        let pins = visible.compactMap { mic -> MicMapPin? in
            guard let latitude = mic.latitude, let longitude = mic.longitude else { return nil }
            return MicMapPin(mic: mic, coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
        }

        // Rotation and pitch are off: a flat, north-up map is easiest to read
        // and leaves no compass to collide with the stats strip.
        return Map(position: $position, interactionModes: [.pan, .zoom]) {
            ForEach(pins) { pin in
                Annotation("", coordinate: pin.coordinate, anchor: .center) {
                    Button {
                        select(pin.id)
                    } label: {
                        MicMapPinView(
                            color: Theme.boroughColor(pin.mic.borough),
                            isSelected: pin.id == selectedMicID
                        )
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(pin.mic.name)
                }
            }
            UserAnnotation()
        }
        .mapStyle(styleOption.mapStyle)
        .onMapCameraChange(frequency: .onEnd) { context in
            // Un-highlight the preset once the user has panned away from it.
            if let preset = zoomPreset,
               !preset.isCentered(latitude: context.region.center.latitude,
                                  longitude: context.region.center.longitude) {
                zoomPreset = nil
            }
        }
    }

    /// The "My location" button above the legend / selected-mic card.
    private func bottomPanel(visible: [Mic]) -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            locationButton
            if let mic = selectedMic(in: visible) {
                let today = CalendarDate.today()
                MicMapSelectedCard(
                    mic: mic,
                    nextDate: MicMapRules.nextDate(for: mic, today: today),
                    neighbors: MicMapRules.micsSharingLocation(with: mic, in: visible),
                    today: today,
                    onSelect: { id in select(id) },
                    onClose: { select(nil) }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                MicMapLegend(showsOther: MicMapRules.hasOtherBorough(visible))
            }
        }
        .padding(.horizontal, 12)
        .padding(.bottom, 8)
    }

    private var locationButton: some View {
        Button {
            showUserLocation()
        } label: {
            Image(systemName: locationPermission.isAuthorized ? "location.fill" : "location")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(Theme.accent)
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: Circle())
                .shadow(color: Color.black.opacity(0.15), radius: 4, x: 0, y: 1)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("My location")
    }

    private var noMatchesCard: some View {
        VStack(spacing: 10) {
            Text("No mics match your filters.")
                .font(.headline)
            Button("Clear filters") {
                filter.reset()
            }
            .buttonStyle(.bordered)
        }
        .padding(16)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    /// Map style picker + a manual refresh (the map has no pull-to-refresh).
    private var styleMenu: some View {
        Menu {
            Picker("Map style", selection: $styleOption) {
                ForEach(MicMapStyleOption.allCases) { option in
                    Label(option.title, systemImage: option.systemImage)
                        .tag(option)
                }
            }
            Divider()
            Button {
                Task { await store.refreshAll() }
            } label: {
                Label("Refresh mics", systemImage: "arrow.clockwise")
            }
        } label: {
            Image(systemName: "square.3.layers.3d")
        }
        .accessibilityLabel("Map style")
    }

    // MARK: - Helpers

    private func selectedMic(in visible: [Mic]) -> Mic? {
        guard let id = selectedMicID else { return nil }
        return visible.first { $0.id == id }
    }

    private func select(_ id: Int?) {
        withAnimation(.easeInOut(duration: 0.2)) {
            selectedMicID = id
        }
    }

    private func apply(_ preset: MicMapZoomPreset) {
        zoomPreset = preset
        withAnimation(.easeInOut(duration: 0.4)) {
            position = .region(preset.region)
        }
    }

    // MARK: - My location

    private func showUserLocation() {
        switch locationPermission.status {
        case .authorizedWhenInUse, .authorizedAlways:
            centerOnUser()
        case .notDetermined:
            isWaitingForLocationPermission = true
            locationPermission.requestWhenInUse()
        case .denied, .restricted:
            isShowingLocationAlert = true
        @unknown default:
            locationPermission.requestWhenInUse()
        }
    }

    private func handlePermissionChange() {
        guard isWaitingForLocationPermission else { return }
        if locationPermission.isAuthorized {
            isWaitingForLocationPermission = false
            centerOnUser()
        } else if locationPermission.isDenied {
            // They said no — nothing else to do; the map keeps working.
            isWaitingForLocationPermission = false
        }
    }

    private func centerOnUser() {
        zoomPreset = nil
        withAnimation(.easeInOut(duration: 0.4)) {
            position = .userLocation(fallback: .region(MicMapZoomPreset.allNYC.region))
        }
    }
}

// MARK: - Map-only types

/// One mic with its coordinate, ready for `Annotation`.
private struct MicMapPin: Identifiable {
    let mic: Mic
    let coordinate: CLLocationCoordinate2D

    var id: Int { mic.id }
}

/// The MapKit styles offered in the toolbar menu (spec 6.5, iOS).
enum MicMapStyleOption: String, CaseIterable, Identifiable, Hashable {
    case standard
    case muted
    case hybrid

    var id: String { rawValue }

    var title: String {
        switch self {
        case .standard: return "Standard"
        case .muted: return "Muted"
        case .hybrid: return "Hybrid"
        }
    }

    var systemImage: String {
        switch self {
        case .standard: return "map"
        case .muted: return "circle.lefthalf.filled"
        case .hybrid: return "globe.americas"
        }
    }

    var mapStyle: MapStyle {
        switch self {
        case .standard: return .standard
        case .muted: return .standard(emphasis: .muted)
        case .hybrid: return .hybrid
        }
    }
}

extension MicMapZoomPreset {
    /// The MapKit region for this preset.
    var region: MKCoordinateRegion {
        MKCoordinateRegion(
            center: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            span: MKCoordinateSpan(latitudeDelta: latitudeSpan, longitudeDelta: longitudeSpan)
        )
    }
}
