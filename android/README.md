# Mic Tracker — Android

The native Android version of the NYC Open Mic Tracker: Kotlin, Jetpack
Compose, Material 3. It talks to the same Supabase database as the Streamlit
web app. Behavior is defined by [`docs/MOBILE_SPEC.md`](../docs/MOBILE_SPEC.md)
(the iOS app follows the same spec).

## Run it

Full walkthrough (Supabase setup, secrets, Android Studio, emulator or phone):
**[`docs/MOBILE_SETUP.md`](../docs/MOBILE_SETUP.md), step 7.** In short:

```bash
cp android/secrets.example.properties android/secrets.properties   # then fill in URL + anon key
```

Open the `android/` folder (not the repo root) in Android Studio and press Run.
Without `secrets.properties` the app still builds and shows a "Not configured"
screen.

Command line (needs JDK 17+ and the Android SDK; Android Studio provides both):

```bash
cd android
./gradlew :core:test                 # business-rule unit tests (plain JVM, fast)
./gradlew :app:testDebugUnitTest     # data-layer unit tests (fake HTTP server)
./gradlew :app:assembleDebug         # APK in app/build/outputs/apk/debug/
```

CI: [`.github/workflows/android.yml`](../.github/workflows/android.yml) runs all
three on every push touching `android/` and uploads the debug APK.

## Versions

| | |
|---|---|
| Gradle (wrapper) | 8.14.3 |
| Android Gradle plugin | 8.7.3 |
| Kotlin (+ Compose compiler plugin) | 2.1.21 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Compose BOM | 2024.12.01 (Material 3 1.3.x) |
| Networking | OkHttp 4.12 + kotlinx.serialization 1.8 (no Supabase SDK) |
| Maps | MapLibre Native 11.8 + Carto styles (no API key) |

All versions live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).
Bytecode targets Java 17; any JDK 17+ can run the build.

## Architecture

```
android/
├── core/                         pure Kotlin/JVM — no Android imports
│   └── src/main/kotlin/com/comedyplatform/mictracker/core/
│       ├── model/                Mic, SetEntry, MicPlan, ScrapedMic, ScrapeLogEntry,
│       │                         enums (Urgency, CostKind, SignupMethod, ...),
│       │                         MicDraft / SetDraft → JSON payloads, Rpc args, Tables
│       ├── json/                 lenient decoding (nulls, 30.0, "1", blanks → null)
│       └── rules/                every business rule of spec §4 (+ JUnit tests)
└── app/src/main/java/com/comedyplatform/mictracker/
    ├── MicTrackerApplication.kt  creates AppContainer, initializes MapLibre
    ├── AppContainer.kt           manual DI + appViewModel { } / appContainer() helpers
    ├── MainActivity.kt           single Activity, edge-to-edge, sets the Compose root
    ├── data/                     ApiClient (PostgREST + RPC, 401 → refresh → retry),
    │                             AuthRepository (GoTrue REST), MicRepository (THE
    │                             shared store), GeocodingService (android.location.Geocoder)
    └── ui/
        ├── theme/                colors (accent #FF4B4B, urgency, borough, tag colors)
        ├── components/           MicRow, CostBadge, UrgencyDot, ErrorBanner, EmptyState,
        │                         TagChips/TagSelector, LoadingBox, RefreshableBox,
        │                         Callout, PlanStatusBanner, ConfirmDialog, Links
        ├── navigation/           gate (MicTrackerRoot), bottom bar + NavHost (MainScaffold), Routes
        ├── auth/                 Sign in / Not configured / Not authorized + SessionViewModel
        ├── detail/               Mic Detail (spec 6.4)
        ├── tonight/ calendar/ map/ micform/      feature team A
        └── sets/ stats/ manage/                  feature team B
```

### Data flow

- **One repository.** `MicRepository` holds `StateFlow`s of all mics (including
  inactive ones), all sets, loaded plans, scraped mics and the scrape log, plus
  derived flows (`activeMics`, `micsById`, `planLookup`, `loggedVisitCounts`,
  `setsWithMics`). Every screen observes it, so a change made anywhere shows up
  everywhere.
- **Loads.** The gate calls `refreshAll()` after sign-in (mics + sets + plans
  from last week's Monday to the Sunday three weeks ahead). Pull-to-refresh
  calls it again. `refreshAll()` never throws; it sets `lastError`.
- **Mutations** (`setPlanStatus`, `clearPlan`, `addMic`, `updateMic`,
  `updateMicRating`, `updateCoordinates`, `deleteMic`, `logSet`, `updateSet`,
  `deleteSet`, `deletePlannedSet`, `importScraped`, `dismissScraped`) throw
  `ApiException` (its `userMessage` is the server's message, safe to show) and
  refresh the affected data afterwards.
- **Dates** are `java.time.LocalDate` end to end and go over the wire as
  `yyyy-MM-dd`. "Today" is `LocalDate.now()` (device-local).

### Writing a screen (feature teams)

```kotlin
@Composable
fun TonightScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onOpenManage: () -> Unit) {
    val vm = appViewModel { c -> TonightViewModel(c.micRepository, today = { c.today() }) }
    val state by vm.state.collectAsStateWithLifecycle()
    // Scaffold + TopAppBar is fine: MainScaffold consumes the window insets it
    // already applied, so nothing is padded twice.
}
```

- Put ViewModels and helpers in **your own package**; don't edit foundation
  files. Pure logic you add should be plain Kotlin in your package.
- Reuse `ui/components` (e.g. `RefreshableBox` for pull-to-refresh, `ErrorBanner`
  for dismissible errors, `rememberLinkOpener()` for links and map addresses).
- Sign out: `appContainer().signOut()` (suspend) — the gate returns to sign-in.
- Geocoding: `container.geocodingService.geocodeMic(address, borough)` returns a
  `GeoPoint` inside NYC or null, and enforces 1 request/second itself.

## Who owns what (multi-agent build)

| Path | Owner |
|---|---|
| Gradle files, wrapper, `core/`, `app/build.gradle.kts`, manifest, `res/` | Foundation |
| `MainActivity`, `MicTrackerApplication`, `AppContainer`, `data/` | Foundation |
| `ui/theme`, `ui/components`, `ui/navigation`, `ui/auth`, `ui/detail` | Foundation |
| `ui/tonight`, `ui/calendar`, `ui/map`, `ui/micform` | Feature team A |
| `ui/sets`, `ui/stats`, `ui/manage` | Feature team B |

Feature entry points (signatures are fixed; the NavHost calls them):

```kotlin
TonightScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onOpenManage: () -> Unit)
CalendarScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onOpenManage: () -> Unit)
MapScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit)
MicFormScreen(editMicId: Int?, onDone: () -> Unit)
SetsScreen()
StatsScreen()
ManageScreen(onOpenMic: (micId: Int, date: LocalDate?) -> Unit, onAddMic: () -> Unit, onBack: () -> Unit)
```
