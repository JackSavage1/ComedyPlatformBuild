# NYC Open Mic Tracker — Native Mobile Spec

This is the single source of truth for the native iOS app (`ios/`) and the
native Android app (`android/`). Both apps must behave identically; where this
document and the Streamlit code disagree, **this document wins** (a few
Streamlit bugs are fixed here on purpose and called out with **FIX**).

The Streamlit web app (`app.py`, `pages/`) keeps working unchanged against the
same Supabase database.

---

## 1. Architecture decisions (final — do not relitigate)

| Concern | iOS | Android |
|---|---|---|
| Language / UI | Swift 5 language mode, SwiftUI, iOS 17+ | Kotlin, Jetpack Compose, Material 3, minSdk 26 |
| State | `@Observable` stores (Observation framework) | `ViewModel` + `StateFlow`, manual DI via an `AppContainer` on the `Application` |
| Networking | Thin REST client on `URLSession` + `Codable` (no Supabase SDK) | Thin REST client on OkHttp + kotlinx.serialization + coroutines (no Supabase SDK) |
| Token storage | Keychain | App-private `SharedPreferences` |
| Maps | MapKit (SwiftUI `Map`) | MapLibre Native Android + Carto Voyager style (no API key) |
| Charts | Swift Charts | Hand-drawn Compose `Canvas` charts (no chart library) |
| Geocoding | `CLGeocoder` | `android.location.Geocoder` |
| Project file | XcodeGen `ios/project.yml` (generate `.xcodeproj`, don't commit it) | Gradle Kotlin DSL + version catalog + Gradle wrapper |
| Pure logic | `ios/MicTracker/Domain/` (no SwiftUI/UIKit imports) + XCTest | `android/core` pure Kotlin/JVM module + JUnit tests |
| CI | `.github/workflows/ios.yml` (macOS runner) | `.github/workflows/android.yml` (ubuntu runner) |

Why no Supabase SDK: both SDKs have changed APIs across majors; a ~300 line
REST client is deterministic, identical on both platforms, and gives exact
control over null handling and date decoding.

Identifiers: display name **"Mic Tracker"**, bundle ID / applicationId
`com.comedyplatform.mictracker`.

Theme: dark-first, follows system light/dark. Accent red `#FF4B4B`
(Streamlit's primary). Urgency colors: high `#E74C3C`, medium `#F1C40F`,
normal `#2ECC71`.

---

## 2. Configuration

Two values, never committed:

- `SUPABASE_URL` — e.g. `https://<ref>.supabase.co`
- `SUPABASE_ANON_KEY` — the project's anon / publishable key

iOS: `ios/Config/Secrets.xcconfig` (gitignored), template
`ios/Config/Secrets.example.xcconfig`. Values flow into Info.plist keys
`SupabaseURL` / `SupabaseAnonKey`. **xcconfig treats `//` as a comment**, so the
template must show the URL as `https:/$()/<ref>.supabase.co`.

Android: `android/secrets.properties` (gitignored), template
`android/secrets.example.properties`, read in `app/build.gradle.kts` into
`BuildConfig.SUPABASE_URL` / `BuildConfig.SUPABASE_ANON_KEY`.

Missing values must **not** break the build (CI has none) — they become empty
strings, and the app shows a "Not configured" screen explaining the file to
create.

---

## 3. Backend contract (Supabase)

### 3.1 REST basics

All requests go to `{SUPABASE_URL}` with headers:

```
apikey: {SUPABASE_ANON_KEY}
Authorization: Bearer {access_token}        // the user's JWT once signed in
Content-Type: application/json
Accept: application/json
```

- Select: `GET /rest/v1/{table}?select=*&{filters}&order=...`
- Insert: `POST /rest/v1/{table}` with `Prefer: return=representation`
- Update: `PATCH /rest/v1/{table}?id=eq.{id}` with `Prefer: return=representation`
- Delete: `DELETE /rest/v1/{table}?id=eq.{id}`
- RPC: `POST /rest/v1/rpc/{function}` with a JSON object of named args
- Errors: non-2xx returns `{"message": "...", "code": "...", "details": ..., "hint": ...}`. Surface `message` to the user.
- On HTTP 401 with an expired JWT: refresh once (below) and retry the request once.

### 3.2 Auth (GoTrue REST)

- Sign in: `POST /auth/v1/token?grant_type=password` body `{"email","password"}` → `{access_token, refresh_token, expires_in, expires_at, user:{id,email}}`
- Refresh: `POST /auth/v1/token?grant_type=refresh_token` body `{"refresh_token"}` → same shape
- Sign out: `POST /auth/v1/logout` (best effort), then clear stored tokens
- Refresh proactively when `expires_at` is < 60 s away. Persist tokens securely (Keychain / private prefs). On refresh failure → signed-out state.
- No in-app sign-up. The owner creates their account in the Supabase dashboard (see `docs/MOBILE_SETUP.md`).

After sign-in, call RPC `is_app_admin` (no args → JSON boolean). If `false`,
show an **"Account not authorized"** screen with the signed-in email, the SQL
the owner must run (from `docs/MOBILE_SETUP.md`), a Retry button, and Sign out.

### 3.3 Tables (existing, created by `utils/database.py`)

`open_mics` — `id` int (serial)

| column | type | notes |
|---|---|---|
| name, venue | text NOT NULL | |
| address, neighborhood, borough | text | borough ∈ Manhattan, Brooklyn, Queens, Bronx, Staten Island (others possible from scrapers) |
| day_of_week | text NOT NULL | full English name, `Monday`…`Sunday` |
| start_time | text NOT NULL | `HH:MM` 24h (may be malformed from scrapers) |
| display_time | text | e.g. `7:00 PM` |
| end_time | text | unused in UI |
| cost | text | free text: `Free`, `$5`, `1 drink min`… |
| set_length_min | int | null/0 = unknown |
| signup_method | text | `in_person`, `online`, `email`, `instagram_dm` (others possible) |
| signup_url, signup_notes, venue_url, instagram, urgency_note, notes | text | |
| urgency | text | `high`, `medium`, `normal` (default) |
| advance_days | int | default 0 |
| mic_rating | real | owner's 0–10 rating, step 0.5 |
| latitude, longitude | real | |
| is_active | bool | default true; soft delete |
| is_biweekly | bool | may be null |
| created_at, updated_at | timestamp | ignore in UI |

`my_sets` — `id` int

| column | type | notes |
|---|---|---|
| open_mic_id | int → open_mics.id | |
| date_performed | date NOT NULL | |
| set_rating | int 1–10 | **null ⇒ "planned" (skeletal) row** created by Going |
| crowd_rating | int 1–10 | |
| crowd_size | text | `empty`, `sparse`, `decent`, `packed` |
| set_list, recording_url, notes, feedback_notes | text | |
| recording_type | text | `none`, `video`, `audio` |
| new_material, got_feedback, would_return | bool | |
| tags | text | comma-separated, e.g. `killed,crowd_work` |

`mic_plans` — `id` int, `open_mic_id` int, `plan_date` date, `status` text ∈ `going`, `cancelled`, UNIQUE(open_mic_id, plan_date)

`scrape_log` — `id`, `source`, `last_scraped` timestamp, `status`, `notes`

### 3.4 New objects (created by `supabase/migrations/20261020000000_mobile_api.sql`)

`app_admins (user_id uuid PK → auth.users)` — who may write.

`scraped_mics` — review queue filled nightly by `scripts/run_scrapers.py`:

| column | type | notes |
|---|---|---|
| id | bigint identity | |
| source | text | `badslava`, `firemics` |
| name, venue, day_of_week, address, display_time, start_time, cost, signup_url | text | denormalized for list display |
| payload | jsonb | full mic dict as the scraper produced it |
| status | text | `pending` (default), `added`, `dismissed` |
| first_seen_at, updated_at | timestamptz | |

UNIQUE(source, name, venue, day_of_week).

RLS:
- `open_mics`: SELECT for `anon` + `authenticated`; INSERT/UPDATE/DELETE only when `is_app_admin()`.
- `my_sets`, `mic_plans`, `scrape_log`, `scraped_mics`: everything only when `is_app_admin()`.
- The Streamlit app connects as the `postgres` table owner, which bypasses RLS, so it is unaffected.

RPC functions (all `SECURITY INVOKER`, executable by `authenticated` only,
except `is_app_admin` which is `SECURITY DEFINER`). Arg names are exact:

| function | args | returns | behavior |
|---|---|---|---|
| `is_app_admin` | — | boolean | caller's uid is in `app_admins` |
| `set_plan_status` | `p_mic_id int, p_plan_date date, p_status text` | void | upsert `mic_plans` (status, refresh created_at). If `p_status='going'` and no `my_sets` row exists for (mic, date), insert skeletal `{open_mic_id, date_performed}` |
| `clear_plan` | `p_mic_id int, p_plan_date date` | void | delete that `mic_plans` row (skeletal set is left alone — same as web) |
| `delete_planned_set` | `p_set_id int` | boolean | only if that `my_sets` row has `set_rating IS NULL`: delete it and the matching `mic_plans` row; returns whether it deleted |
| `delete_mic` | `p_mic_id int` | void | `is_active=false`, `updated_at=now()`, delete all its `mic_plans` |
| `import_scraped_mic` | `p_id bigint` | int (new open_mics id) | insert into `open_mics` from `payload` (only real columns, bools coerced, `is_active=true`), mark row `added` |
| `dismiss_scraped_mic` | `p_id bigint` | void | mark row `dismissed` |

Dates are sent as `"YYYY-MM-DD"` strings.

### 3.5 Client data rules (both apps)

- Load **all** `open_mics` (`select=*`, including inactive — history needs names of deactivated mics) into an id→mic map. "Active mics" = `is_active != false`.
- Load all `my_sets` (`order=date_performed.desc,id.desc`) and join to mics client-side.
- Load `mic_plans` for a date window (`plan_date=gte.X&plan_date=lte.Y`).
- Refresh the relevant data after every mutation and on pull-to-refresh.
- **Dates:** `date` columns are calendar dates. Parse `"YYYY-MM-DD"` into a calendar-date type (`LocalDate` on Android; a `CalendarDate`/`DateComponents` value or a `Date` at local midnight built with the **local** calendar on iOS). Never round-trip through UTC — that shifts the day.
- "Today" = device local date. Weeks start **Monday**.
- **Blank = null:** any text field that is null or whitespace-only is treated as absent (the Streamlit `safe_str` rule). Decoding must never crash on null, missing keys, unknown enum strings, or numbers sent as floats (`set_length_min` may arrive as `30.0`).
- Unknown enum strings (urgency, signup_method, crowd_size, recording_type) map to a fallback/"other" case and display the raw text prettified (`in_person` → `In Person`).
- **Insert payloads omit null keys** (so DB defaults apply). **Update payloads send explicit `null`** for fields the user cleared.
- Sort mics within a day by `start_time` parsed to minutes; unparseable times sort last; ties by name.

---

## 4. Business rules (implement in pure, unit-tested code)

These live in `ios/MicTracker/Domain/` and `android/core/`. Write unit tests for each.

1. **Time display:** `display_time` if present, else `start_time`.
2. **Cost display:** `cost` if present else `"Free/TBD"`. **Cost badge:** cost (null ⇒ "free") lowercased contains `free` → 🆓 "Free"; contains `drink` → 🍺 "Drink min"; else 💵 "Paid".
3. **Urgency dot:** high 🔴, medium 🟡, anything else 🟢. Default notes: high → "Signs up fast — book ahead!", medium → "Book a few days early".
4. **Cost filter:** All · Free only (cost null or contains "free") · Under $6 (**FIX:** cost null, contains "free", or the first `$N` / `$N.NN` amount is < 6 — Streamlit's regex wrongly matched `$10`) · Drink min only (contains "drink").
5. **Signup filter:** All · Online (`online`) · In-person (`in_person`) · Email (`email`) · Instagram DM (`instagram_dm`).
6. **Visited:** a mic is visited if it has ≥1 **logged** set (`set_rating != null`). **FIX:** Streamlit counted planned rows. "Been here N×" uses the same count.
7. **Signup deadline** (only when `advance_days > 0`), for a mic occurring on date D: `signupBy = D − advance_days`; `daysUntil = signupBy − today`.
   - `signupBy < today` → "⚠️ Signup deadline passed!"
   - `daysUntil == 0` → "🔴 Sign up TODAY!"
   - `daysUntil == 1` → "🟡 Sign up by TOMORROW!"
   - else → "📅 Sign up by EEE MM/dd (N days)" e.g. `📅 Sign up by Thu 10/23 (3 days)`
8. **Signup URL:** trim; if it starts with `http://` or `https://` and has a host → use it. Else if it has no spaces and contains a `.` → prefix `https://`. Else no Sign Up button (show the raw text as a note instead).
9. **Instagram:** trim; if it contains `instagram.com/`, take the first path segment after it; strip leading `@` and trailing `/`, drop any `?query`. Valid handle = `[A-Za-z0-9._]{1,30}`; show `@handle` linking to `https://instagram.com/{handle}`. Invalid → show raw text, no link.
10. **Set length:** `set_length_min` > 0 → "N min sets", else "Set length: N/A".
11. **Week streak** (**FIX:** unify Dashboard and My Sets; Streamlit's My Sets version just counted weeks, and the Dashboard broke on 53-week years): ISO weeks (Monday start) containing ≥1 logged set. Start at the current week; if it has none, start at last week (a streak isn't broken on Monday morning). Count consecutive weeks backwards using real date math. No sets → 0.
12. **Average rating delta:** avg `set_rating` of logged sets this calendar month minus last calendar month, one decimal, shown as `+0.4 vs last month`; hidden unless both months have sets.
13. **OLS trendline:** least-squares line of `set_rating` over date (x = days since first set). Needs ≥2 sets with ≥2 distinct dates.
14. **Tags:** fixed options `killed, bombed, new_bit, crowd_work, got_laughs, nervous, confident, short_set, long_set, got_spotted`. Positive (green `#2ECC71`): killed, got_laughs, confident, crowd_work, got_spotted. Negative (red `#E74C3C`): bombed, nervous. Others blue `#3498DB`. Stored comma-joined with no spaces; parse by splitting on `,` and trimming, dropping empties.
15. **Rating emoji:** planned 📋; ≥8 🔥; ≥5 👍; else 😬.
16. **Next occurrence** of a weekday from today: today if same weekday, else the next date with that weekday.
17. **Borough colors** (map + legend): Manhattan `#FF6347`, Brooklyn `#1E90FF`, Queens `#32CD32`, Bronx `#FFA500`, Staten Island `#9370DB`, other `#808080`.
18. **NYC bounds** for geocoding results: `40.4 < lat < 41.0` and `-74.3 < lon < -73.5`; reject anything outside.
19. **Display time from a picked time:** `h:mm a` in English, e.g. `19:00` → `7:00 PM`; `start_time` stored as zero-padded `HH:mm`.

---

## 5. Navigation

Bottom tab bar, 5 tabs, in this order:

1. **Tonight** (house / mic icon) — home
2. **Calendar** (calendar icon)
3. **Map** (map icon)
4. **My Sets** (mic icon)
5. **Stats** (chart icon)

A gear button in the top bar of **Tonight** and **Calendar** opens **Manage**
(Add mic, Import, Manage mics, Geocoding, Scrape history, Account).

Gate before tabs: Not configured → Sign in → Not authorized → tabs.

Every list supports pull-to-refresh. Every network action shows progress and,
on failure, a dismissible error with the server `message`.

---

## 6. Screens

### 6.1 Sign in
App name + 🎤, email field, password field, Sign in button, error text.

### 6.2 Tonight (port of `app.py`)
- Header: full date, e.g. "Monday, October 20, 2026".
- **Tonight's Mics — {Day}**: "N mics tonight". Each row: urgency dot, time, **name**, "@ venue", cost. Tap → Mic Detail with date = today. Empty: "No open mics listed for {Day}. Rest day! 😴".
- **Coming Up Tomorrow — {Day}**: compact rows `dot time — name @ venue (neighborhood) · cost`. Tap → Mic Detail with date = tomorrow.
- **Quick stats** row: Total Active Mics · Neighborhoods (distinct non-blank) · Most Mics In (borough with most active mics, or N/A).

### 6.3 Calendar (port of Weekly Calendar)
- Week header: ◀ / ▶ arrows, title "Last Week" (offset −1) / "This Week" (0) / "Next Week" (1) / "N Weeks Out", subtitle "Oct 20 – Oct 26, 2026", a "Today" button when offset ≠ 0. Offset range **−1 … +3**.
- Day strip: 7 chips Mon–Sun showing `Mon` + `10/20` + count of filtered mics that day. Today's chip (in the current week) highlighted in accent red; past days dimmed. Selected day defaults to today in the current week, else Monday.
- Filter button (badge with active-filter count) opens a sheet: Borough (multi), Neighborhood (multi, options limited to selected boroughs; all if none), Cost (single), Signup Type (single), Only unvisited (toggle), Only going this week (toggle), Reset.
- Caption: "Showing X of Y mics" (X = filtered mics across the whole week).
- List for selected day: card = status prefix (✅ going / ❌ cancelled for that date) + urgency dot + time, **name**, cost badge + neighborhood + 🔄 if biweekly, and the signup-deadline line if any. Tap → Mic Detail with that date. Swipe actions: Going / Cancelled.
- Empty day: "No mics".

### 6.4 Mic Detail (shared screen)
Input: mic id + optional date context.
- Title: name. Venue, address (tap → open in Apple Maps / Google Maps intent), neighborhood + borough, day + time (+ 🔄 Biweekly), cost, set length, signup method + notes.
- Status banner for the date: "You're going to this mic!" (green) / "This mic is marked as cancelled" (red).
- Signup deadline (rule 7) for the date context.
- Urgency callout (high = warning style, medium = info style) with urgency_note or default note.
- Notes 💡. Instagram link. **Sign Up →** button (rule 8). Venue website link if present.
- "✅ Been here N×" if visited. **My rating** slider 0–10 step 0.5 (shows "Not rated" at 0/null) with an "Update rating" button → PATCH `mic_rating`.
- With a date context: action row **Going** (primary) · **Cancelled** · **Clear** (only the ones that change state, like the web) → RPCs `set_plan_status` / `clear_plan`.
- Footer: **Edit mic** (→ Mic Form) and **Delete mic** (destructive, confirm "Remove {name} from your database? Your set history is kept.") → RPC `delete_mic`, then pop.

### 6.5 Map
- Native map, initial camera "All NYC". Pins/circles colored by borough (rule 17), only active mics with coordinates.
- Filter chips: Day (multi), Borough (multi). Zoom presets segmented control: All NYC (40.7128, −73.9500, z10.5) · Manhattan (40.7580, −73.9855, z12.5) · Brooklyn (40.6782, −73.9442, z12) · Queens/Bronx (40.8000, −73.8800, z11). (iOS: convert zoom to a span ≈ `360 / 2^zoom` degrees longitude.)
- "My location" button (When-In-Use permission; app works if denied).
- Stats strip: Mics on map · Neighborhoods · Boroughs. Legend of borough colors.
- Tap a mic → callout/bottom card: name, venue, "Mondays at 7:00 PM", cost, neighborhood or borough, "Details" → Mic Detail with date = next occurrence (rule 16).
- Android map style switcher like the web: Voyager (default) `https://basemaps.cartocdn.com/gl/voyager-gl-style/style.json`, Light `.../positron-gl-style/style.json`, Dark `.../dark-matter-gl-style/style.json`. iOS: standard / muted / hybrid MapKit styles.
- If no mic has coordinates: message pointing to Manage → Geocoding.

### 6.6 My Sets (port of My Sets page)
Segmented control: **Log a Set** | **History**.

**Log a Set**
- "Planned Sets — Ready to Log" list (planned rows, oldest first): "📝 Log: {mic} @ {venue} — {date}" tap → prefill form (UPDATE that row on save); trailing ✕ (swipe-to-delete on Android/iOS also fine) → RPC `delete_planned_set`.
- Form: Mic picker (searchable, grouped by day Mon→Sun, label `Mon — 7:00 PM — Name @ Venue`, active mics only) · Date performed (max today; a prefilled future date is clamped to today) · Set rating slider 1–10 default 5 ("1 = total bomb, 10 = absolute killer set") · Crowd rating 1–10 default 5 · Crowd size segmented (empty/sparse/decent/packed, default decent) · Set list (multiline) · Trying new material? toggle · Recording link + type segmented (none/video/audio, default none) · Notes (multiline) · Got feedback? toggle → Feedback notes appears · Would return? toggle default on · Tags multi-select chips (rule 14).
- Save: validates a mic is selected; insert (`POST my_sets`) or update the prefilled planned row (`PATCH`). Blank text → null; tags → comma-joined or null. On success: confetti/haptic + "✅ Set logged! Keep grinding! 🎤", form resets. A "Log a different set instead" button clears the prefill.

**History**
- Summary: Total Sets "N" (+ " (+M planned)" if any) · Avg Set Rating "X.X/10" · Most Visited "Mic (N×)" (logged sets only) · Week Streak "N weeks" (rule 11).
- Filters: mic (All + mic names present), min set rating 1–10 (planned rows always shown), tags (any-of).
- "Showing N sets". Rows: emoji (rule 15) **MM/dd/yyyy** — mic @ venue — "Set: 8/10 · Crowd: 6/10" or "PLANNED — tap to log details", tag pills.
- Tap logged set → Set Detail: all fields, set list in monospace, feedback, "▶️ Watch/Listen ({type})" opens recording URL. **Edit** (opens the form prefilled, PATCH) and **Delete** (confirm, `DELETE my_sets?id=eq.X`) — new on mobile.
- Tap planned row → switches to Log tab prefilled.
- Empty: "No sets logged yet! Hit a mic tonight and come back to log it. Your first data point is always the hardest. 🎤"

### 6.7 Stats (port of Dashboard; logged sets only)
Empty state same copy as the web dashboard. Otherwise, scrolling cards:
1. Metrics: 🎤 Total Sets · ⭐ Avg Set Rating (+ delta rule 12) · 🔥 Week Streak · 🏠 Venues Visited "distinct venues of logged sets / active mics count".
2. **Set Rating Over Time** — scatter by date, y 0–10, colored by venue, OLS trendline (rule 13).
3. **Crowd vs Set Rating** — scatter x crowd 0–10, y set 0–10, point size by crowd size (empty 10, sparse 20, decent 35, packed 50, unknown 20 — scale to sensible pts/dp).
4. **Sets by Day of Week** — bars Mon→Sun (mic's `day_of_week`), zeros included.
5. **Sets by Neighborhood** — horizontal bars, top 10.
6. **Top 5 Mics** — horizontal bars by visit count.
7. **Tag Frequency** — bars colored per rule 14 (only if any tags).
8. **Tags Over Time** — stacked area (or stacked bars) by month `yyyy-MM` per tag.
9. **My Mic Ratings** table: Mic · Venue · My Rating · Visits · Avg Set · Avg Crowd · Last Visit (MM/dd/yyyy), sorted by visits desc (horizontally scrollable).
10. Insight card: "💡 Mics you rated highly but haven't visited in 30+ days: …" (mic_rating ≥ 7 and last visit > 30 days ago).

### 6.8 Manage (port of Settings + map geocoding tools)
Sections/list rows:
- **Add a Mic** → Mic Form (empty).
- **Import from Scrapers** — pending `scraped_mics` grouped by source (Bad Slava / FireMics). Row: `{day} — {name} @ {venue}`, address, time, cost, signup URL. Actions: **Add** (`import_scraped_mic`), **Dismiss** (`dismiss_scraped_mic`), and **Add all** per source. Caption: "Scrapers run nightly on GitHub Actions. Duplicates are filtered automatically." Empty: "You're up to date."
- **Scrape History** — `scrape_log` ordered by `last_scraped desc`: source, time, status, notes.
- **Manage Mics** — searchable list of active mics (`Mon — Name @ Venue`) → Mic Detail without a date context.
- **Geocoding** — "Mics with coordinates X / Y"; list of active mics missing coordinates (name, venue, address); **Geocode with device** button: for each missing mic with an address, geocode `"{address}, {borough}, New York, NY"` then `"{address}, New York, NY"`, accept only rule-18 results, PATCH `latitude`/`longitude`, wait ≥1 s between requests, show progress and a final "Geocoded N of M".
- **Account** — signed-in email, Sign out.

### 6.9 Mic Form (add / edit)
Fields: Name* · Venue* · Address · Neighborhood · Borough (picker, 5 boroughs) · Day of week* (picker) · Start time* (native time picker → `HH:mm`) · Display time (auto-filled from the picker per rule 19, editable) · Cost · Set length (stepper 0–30, 0 = unknown → null) · Signup method (picker) · Signup URL · Signup notes · Venue website · Instagram · Urgency (normal/medium/high) · Urgency note · Advance days (stepper ≥0) · Biweekly toggle · Notes.
- Add: POST with null keys omitted, `is_active: true`. If an address is given, geocode it on device first (same rule as 6.8) and include lat/lon when found. Success: "Added {name} @ {venue} ({day})!"
- Edit: PATCH all fields (explicit nulls for cleared ones). If the address changed, re-geocode.
- Validation message: "Name, Venue, Day of Week, and Start Time are required!"

---

## 7. Out of scope for v1 (listed so nobody builds them)
Push notifications, offline cache/persistence beyond tokens, in-app sign-up,
multi-user data separation, widgets, running scrapers from the phone, iPad- or
tablet-specific layouts.
