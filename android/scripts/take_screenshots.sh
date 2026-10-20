#!/usr/bin/env bash
# =============================================================================
# Takes a screenshot of every screen in docs/MOBILE_SPEC.md section 8.3 on a
# running emulator (or USB phone) and saves them as
#     docs/screenshots/android/NN-<screen>.png
#
# Used by .github/workflows/android-screenshots.yml, but works locally too:
#     cd android && ./gradlew :app:assembleDebug && cd ..
#     bash android/scripts/take_screenshots.sh        # with an emulator running
#
# How it works: for each screen it force-stops the app, then launches it with
# the automation extras (spec 8.3), e.g.
#     adb shell am start -W -n com.comedyplatform.mictracker/.MainActivity \
#         --ez demo true --es screen calendar-filters
# waits for the screen to settle and saves `adb exec-out screencap -p`.
# Everything runs in demo mode (no Supabase needed) except `sign-in`.
#
# Exits non-zero if any screenshot is missing or empty, or the app crashed.
# Optional environment variables: APK, OUT_DIR, LOG_DIR.
# =============================================================================
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
APK="${APK:-$REPO_ROOT/android/app/build/outputs/apk/debug/app-debug.apk}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/docs/screenshots/android}"
LOG_DIR="${LOG_DIR:-$REPO_ROOT/android/build/screenshot-logs}"

PACKAGE="com.comedyplatform.mictracker"
ACTIVITY="$PACKAGE/.MainActivity"

# The spec 8.3 table, in order (the NN prefix of each file follows this order).
SCREENS=(
  sign-in
  tonight
  calendar
  calendar-filters
  mic-detail
  map
  sets-log
  sets-history
  set-detail
  stats
  stats-charts
  manage
  import
  mic-form
)

# Seconds to wait after launch: the map downloads tiles, everything else is local.
settle_seconds() {
  case "$1" in
    map) echo 12 ;;
    *) echo 5 ;;
  esac
}

log() { echo "==> $*"; }

# SystemUI demo mode command (clean status bar); never fatal.
sysui() { adb shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null || true; }

# ---------------------------------------------------------------------------
# 1. Device + app setup
# ---------------------------------------------------------------------------
if [[ ! -f "$APK" ]]; then
  echo "::error::APK not found at $APK — run ./gradlew :app:assembleDebug in android/ first."
  exit 1
fi

log "Waiting for the device to finish booting"
adb wait-for-device
for _ in $(seq 1 120); do
  if [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then break; fi
  sleep 2
done

# Screen on, unlocked, and kept on while we work.
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true
adb shell svc power stayon true || true

log "Installing $APK"
adb install -r "$APK"

log "Dark mode + location permission (so the map never shows a permission dialog)"
adb shell cmd uimode night yes
adb shell pm grant "$PACKAGE" android.permission.ACCESS_FINE_LOCATION
adb shell pm grant "$PACKAGE" android.permission.ACCESS_COARSE_LOCATION

log "Clean status bar (SystemUI demo mode)"
adb shell settings put global sysui_demo_allowed 1
sysui enter
sysui clock -e hhmm 0941
sysui battery -e level 100 -e plugged false
sysui network -e wifi show -e level 4 -e fully true
sysui network -e mobile hide
sysui notifications -e visible false

# ---------------------------------------------------------------------------
# 2. One screenshot per screen
# ---------------------------------------------------------------------------
mkdir -p "$OUT_DIR" "$LOG_DIR"
rm -f "$OUT_DIR"/*.png
adb logcat -c || true

failures=()
index=0
for screen in "${SCREENS[@]}"; do
  index=$((index + 1))
  file="$OUT_DIR/$(printf '%02d' "$index")-$screen.png"

  if [[ "$screen" == "sign-in" ]]; then
    extras=(--es screen "$screen")                 # the real gate, no demo
  else
    extras=(--ez demo true --es screen "$screen")
  fi

  log "[$index/${#SCREENS[@]}] $screen"
  adb shell am force-stop "$PACKAGE"
  start_output="$(adb shell am start -W -n "$ACTIVITY" "${extras[@]}" 2>&1 | tr -d '\r')"
  echo "$start_output"
  if grep -q "^Error" <<<"$start_output"; then
    failures+=("$screen: the activity didn't start")
    continue
  fi

  sleep "$(settle_seconds "$screen")"
  # Close any system dialog (e.g. "System UI isn't responding") that popped up meanwhile.
  adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true

  if [[ -z "$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')" ]]; then
    failures+=("$screen: the app is not running (crashed?) — see logcat")
  fi

  adb exec-out screencap -p > "$file"
done

# ---------------------------------------------------------------------------
# 3. Check the results (fail loudly)
# ---------------------------------------------------------------------------
adb logcat -d > "$LOG_DIR/logcat.txt" 2>/dev/null || true
adb shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null || true

index=0
for screen in "${SCREENS[@]}"; do
  index=$((index + 1))
  file="$OUT_DIR/$(printf '%02d' "$index")-$screen.png"
  if [[ ! -s "$file" ]]; then
    failures+=("$screen: $file is missing or empty")
  elif [[ "$(head -c 8 "$file" | od -An -tx1 | tr -d ' \n')" != "89504e470d0a1a0a" ]]; then
    failures+=("$screen: $file is not a PNG")
  fi
done

if (( ${#failures[@]} > 0 )); then
  echo "::error::Screenshot run failed:"
  printf '  - %s\n' "${failures[@]}"
  echo "Crash traces (if any):"
  grep -A 20 "FATAL EXCEPTION" "$LOG_DIR/logcat.txt" | head -80 || true
  exit 1
fi

log "Saved ${#SCREENS[@]} screenshots to $OUT_DIR"
ls -la "$OUT_DIR"
