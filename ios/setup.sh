#!/usr/bin/env bash
# =============================================================================
# One-command setup for running Mic Tracker on your iPhone from a Mac.
#
#     cd path/to/ComedyPlatformBuild
#     bash ios/setup.sh
#
# What it does:
#   1. checks that Xcode is installed
#   2. gets XcodeGen (Homebrew if you have it, otherwise a local download)
#   3. creates ios/Config/Secrets.xcconfig with your Supabase URL + anon key
#      and a bundle ID that's unique to you (needed for a free Apple ID)
#   4. generates MicTracker.xcodeproj and opens it in Xcode
#
# Safe to run again: it keeps an existing Secrets.xcconfig.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")"   # → ios/

SUPABASE_URL_HOST="pqcapifqcspuwipmxlcz.supabase.co"

say()  { printf '\n\033[1;31m▶\033[0m %s\n' "$1"; }
fail() { printf '\n\033[1;31m✖ %s\033[0m\n' "$1" >&2; exit 1; }

# --- 1. Xcode ---------------------------------------------------------------
say "Checking Xcode"
if ! xcodebuild -version >/dev/null 2>&1; then
  fail "Xcode isn't installed (or never opened). Install it from the Mac App Store, open it once to finish setup, then run this script again."
fi
xcodebuild -version | head -n 1

# --- 2. XcodeGen ------------------------------------------------------------
say "Getting XcodeGen (builds the Xcode project from project.yml)"
if command -v xcodegen >/dev/null 2>&1; then
  XCODEGEN=$(command -v xcodegen)
elif command -v brew >/dev/null 2>&1; then
  brew install xcodegen
  XCODEGEN=$(command -v xcodegen)
else
  # No Homebrew: download the official release into ios/.tools (gitignored).
  mkdir -p .tools
  if [ ! -x .tools/xcodegen/bin/xcodegen ]; then
    curl -fsSL -o .tools/xcodegen.zip \
      https://github.com/yonaskolb/XcodeGen/releases/latest/download/xcodegen.zip
    unzip -qo .tools/xcodegen.zip -d .tools
    rm -f .tools/xcodegen.zip
  fi
  XCODEGEN="$PWD/.tools/xcodegen/bin/xcodegen"
  [ -x "$XCODEGEN" ] || fail "Couldn't download XcodeGen. Install Homebrew (https://brew.sh), then run: brew install xcodegen"
fi
echo "Using $XCODEGEN"

# --- 3. Secrets.xcconfig ----------------------------------------------------
if [ -f Config/Secrets.xcconfig ]; then
  say "Keeping your existing Config/Secrets.xcconfig"
else
  say "Creating Config/Secrets.xcconfig"
  echo "Paste your Supabase anon key (Supabase → Project Settings → API Keys →"
  echo "the long 'anon public' key starting with eyJ), then press Return:"
  read -r ANON_KEY
  # Drop accidental quotes/spaces around the pasted key.
  ANON_KEY=$(printf '%s' "$ANON_KEY" | tr -d "\"' \t\r\n")
  [ -n "$ANON_KEY" ] || fail "No key entered. Run the script again when you have it."

  # Free Apple IDs need a bundle ID nobody else has registered, so add a
  # suffix from your Mac username (letters and digits only).
  SUFFIX=$(whoami | tr '[:upper:]' '[:lower:]' | tr -cd 'a-z0-9')
  [ -n "$SUFFIX" ] || SUFFIX="me"

  cat > Config/Secrets.xcconfig <<EOF
// Created by ios/setup.sh — gitignored, stays on this Mac.
// \`https:/\$()/\` is on purpose: in .xcconfig files // starts a comment.
SUPABASE_URL = https:/\$()/$SUPABASE_URL_HOST
SUPABASE_ANON_KEY = $ANON_KEY

// Unique per person so a free Apple ID can sign it.
MIC_TRACKER_BUNDLE_ID = com.comedyplatform.mictracker.$SUFFIX

// Optional: your Apple team ID, so you don't have to pick the team in Xcode
// again after re-running this script. Find it in Xcode → Settings →
// Accounts → your Apple ID (or leave it empty and pick it in Xcode).
DEVELOPMENT_TEAM =
EOF
  echo "Saved. Bundle ID: com.comedyplatform.mictracker.$SUFFIX"
fi

# --- 4. Generate and open ---------------------------------------------------
say "Generating MicTracker.xcodeproj"
"$XCODEGEN" generate --quiet

say "Opening Xcode"
open MicTracker.xcodeproj

cat <<'EOF'

Next, in Xcode:
  1. Xcode → Settings → Accounts → + → sign in with your Apple ID (once).
  2. Left sidebar: click "MicTracker" → target "MicTracker" → "Signing &
     Capabilities" → Team: pick "<Your Name> (Personal Team)".
  3. Plug in your iPhone, unlock it, tap "Trust". Pick it in the device menu
     at the top of Xcode, then press ⌘R.
  4. First time only, on the iPhone:
       • Settings → Privacy & Security → Developer Mode → On (it restarts)
       • Settings → General → VPN & Device Management → your Apple ID → Trust
  5. Open Mic Tracker and sign in with your Supabase app login.

With a free Apple ID the app stops opening after 7 days — just plug in and
press ⌘R again.
EOF
