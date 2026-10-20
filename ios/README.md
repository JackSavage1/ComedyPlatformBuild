# Mic Tracker — native iOS app

SwiftUI app (iOS 17+, Swift 5 language mode) that talks to the same Supabase
database as the Streamlit web app. The behavior contract for both mobile apps
is [`docs/MOBILE_SPEC.md`](../docs/MOBILE_SPEC.md); one-time Supabase setup
(migration, creating your login, making it an admin) is in
[`docs/MOBILE_SETUP.md`](../docs/MOBILE_SETUP.md).

## Run it

Use **Xcode 16** (what CI builds with). Every view struct is marked
`@MainActor` explicitly, because the views read the main-actor `AppStore`
from helper methods: the iOS 18 SDK makes all of `View` main-actor anyway,
and the explicit annotation lets Xcode 15 (iOS 17 SDK) build it too. Keep
the annotation on new views. The app runs on iOS 17+.

```bash
brew install xcodegen                      # once
cp ios/Config/Secrets.example.xcconfig ios/Config/Secrets.xcconfig
# edit Secrets.xcconfig: SUPABASE_URL = https:/$()/<ref>.supabase.co  (keep the $()!)
#                        SUPABASE_ANON_KEY = <anon or publishable key>
cd ios && xcodegen generate                # creates MicTracker.xcodeproj (not committed)
open MicTracker.xcodeproj                  # pick an iPhone simulator, ⌘R
```

* Without `Secrets.xcconfig` the app still builds and shows a **Not configured**
  screen (that's what CI does).
* `//` starts a comment in `.xcconfig` files, hence `https:/$()/…`.
* Re-run `xcodegen generate` after adding or removing files.
* Tests: ⌘U in Xcode, or
  `xcodebuild test -project MicTracker.xcodeproj -scheme MicTracker -destination 'platform=iOS Simulator,name=iPhone 16'`.
* CI: [`.github/workflows/ios.yml`](../.github/workflows/ios.yml) selects the
  newest Xcode 16.x on the runner, builds for the simulator and runs the unit
  tests on every push touching `ios/`.

## Architecture

```
MicTrackerApp ── creates one AppStore, injects it with .environment(store)
  └─ RootView      gate: Not configured → Sign in → Not authorized → MainTabView
       └─ MainTabView   Tonight · Calendar · Map · My Sets · Stats
                        (loads data, shows the global error banner)
```

| Layer | Folder | What lives there |
|---|---|---|
| App | `MicTracker/App/` | entry point, gate, tab bar |
| Core | `MicTracker/Core/` | `AppConfig` (Info.plist secrets), `APIClient` (PostgREST + RPC over URLSession), `AuthService` (GoTrue REST, Keychain), `KeychainStore`, `Models` (defensive `Decodable`), `Payloads` (insert = omit nulls, update = explicit nulls), `CalendarDate` (time-zone-free days), `AppStore` (all data + every mutation), `GeocodingService` (CLGeocoder, NYC bounds, 1 req/s), `Theme`, `ExternalLinks` |
| Domain | `MicTracker/Domain/` | pure business rules from spec §4 — Foundation only, no SwiftUI/UIKit; fully unit tested |
| Shared | `MicTracker/Shared/` | reusable views: `MicRowView`, `CostBadge`, `UrgencyDot`, `ErrorBanner` (+ `.storeErrorBanner()`), `LoadingOverlay` (+ `.loadingOverlay(_:)`), `EmptyStateView`, `FlowLayout`, `TagChips`/`TagSelector`, `ManageToolbarButton`, `MicDetailView` (+ `MicDetailRoute`) |
| Features | `MicTracker/Features/<Screen>/` | one folder per screen |

### Conventions for screens

* Read the store with `@Environment(AppStore.self) private var store`.
* Mutations are `async` methods on `AppStore` that return `Bool` (or the new
  value) and put a user-facing message in `store.errorMessage` on failure.
  MainTabView and the Manage sheet already show that message as a banner.
* Pull-to-refresh: `.refreshable { await store.refreshAll() }`.
* Dates: always `CalendarDate` (`CalendarDate.today()`, `.adding(days:)`,
  `.weekday`, `.startOfWeek`), never `Date` at UTC midnight.
* Each tab's root view owns its `NavigationStack`. Mic Form and Manage
  sub-screens are pushed, so they don't create one. `ManageView` is presented
  by `ManageToolbarButton` inside its own NavigationStack with a Done button.
* Push Mic Detail with `NavigationLink { MicDetailView(micID:date:) } label: { … }`
  or `NavigationLink(value: MicDetailRoute(...))` + `.micDetailDestination()`.

### Cheat sheet (foundation API used by the feature screens)

```swift
// Data (read-only outside the store)
store.mics, store.activeMics, store.micsByID, store.mic(id:)
store.activeMicsOnDay(.monday)            // sorted by start time
store.sets, store.loggedSets, store.plannedSets, store.mic(for: set), store.micName(for: set)
store.planStatus(micID:on:) -> PlanStatus?, store.goingMicIDs(from:through:)
store.loggedVisitCount(micID:), store.activeNeighborhoods, store.micsMissingCoordinates
store.scrapedMics, store.scrapeLog, store.geocodeProgress
store.isLoading, store.hasLoadedOnce, store.isBusy, store.errorMessage, store.auth.email

// Loading
await store.refreshAll()                  // mics + sets + plans (last week … +3 weeks)
await store.loadPlans(from:to:), await store.ensurePlansLoaded(for:)
await store.loadScraped(), await store.loadScrapeLog()

// Writes (return Bool / value; failures land in store.errorMessage)
await store.setPlanStatus(micID:date:status:), await store.clearPlan(micID:date:)
await store.addMic(MicFields) -> Mic?, await store.updateMic(id:fields:includeCoordinates:)
await store.updateMicRating(micID:rating:), await store.deleteMic(micID:)
await store.updateCoordinates(micID:latitude:longitude:)
await store.geocode(address:borough:) -> GeoPoint?, await store.geocodeMissingCoordinates() -> GeocodeSummary
await store.logSet(SetFields), await store.updateSet(id:fields:), await store.deleteSet(id:)
await store.deletePlannedSet(id:)
await store.importScraped(id:) -> Int?, await store.importAllScraped(source:), await store.dismissScraped(id:)
await store.signOut()

// Domain helpers (pure)
mic.timeText, mic.costText, mic.costKind, mic.urgencyLevel, mic.urgencyMessage, mic.weekday,
mic.setLengthText, mic.signupLink, mic.signupMethodKind, mic.instagramHandle, mic.venueLink,
mic.locationText, mic.scheduleText, mic.hasCoordinates, mic.signupDeadline(on:today:)
set.isPlanned, set.isLogged, set.tagList, set.ratingEmoji, set.crowdSizeKind, set.recordingKind, set.datedRating
CostFilter, SignupFilter, SignupDeadline, SignupRules, InstagramRules, TagRules, SetRules
(weekStreak, monthOverMonthDelta, deltaText, olsTrend, loggedVisitCounts), ScheduleRules
(nextOccurrence, weekTitle, weekRangeText, monday(weekOffset:today:), weekDates), BoroughRules,
GeoRules, TimeRules, MicRules (sortedByStartTime, minutesSinceMidnight), CrowdSize, RecordingType,
SignupMethod, Urgency, Weekday, CalendarDate (today(), adding(days:), weekday, startOfWeek, mmdd,
mmddyyyy, longText, shortText, monthKey, date()), Theme (accent, boroughColor, tagColor, urgencyColor)
```

### Ownership map (multi-agent build)

| Path | Owner |
|---|---|
| `project.yml`, `Config/`, `.gitignore`, `README.md`, `.github/workflows/ios.yml` | Foundation |
| `MicTracker/App/`, `Core/`, `Domain/`, `Shared/`, `Resources/`, `Features/Auth/` | Foundation |
| `MicTracker/Features/Tonight/`, `Calendar/`, `Map/`, `MicForm/` | Feature team A |
| `MicTracker/Features/Sets/`, `Stats/`, `Manage/` | Feature team B |
| `MicTrackerTests/DomainTests.swift`, `CalendarDateTests.swift`, `ModelDecodingTests.swift` | Foundation (feature teams add their own test files) |

Feature teams keep these entry-point signatures: `TonightView()`,
`CalendarView()`, `MicMapView()`, `MicFormView(editing: Mic? = nil)`,
`SetsView()`, `StatsView()`, `ManageView()`. Helpers a feature needs that
don't exist yet go in a new file inside that feature's folder.
