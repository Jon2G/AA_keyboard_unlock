#!/usr/bin/env bash
# DHU validation script for AA Keyboard Unlock module.
# Prerequisites: rooted device, LSPosed, module enabled + scoped to gearhead and maps.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DHU="${DHU:-/Users/jon2g/android/android-sdk/extras/google/auto/desktop-head-unit}"
CONFIG="${CONFIG:-$ROOT/dhu_config/config_stopped_sensors.ini}"
APK="$ROOT/src/app/build/outputs/apk/debug/app-debug.apk"

echo "== AA Keyboard Unlock — DHU test matrix =="

if [[ ! -x "$DHU" ]]; then
  echo "ERROR: DHU not found at $DHU"
  exit 1
fi

echo "[1/6] Installing module APK..."
adb install -r "$APK"

echo "[2/6] Enabling module via prefs (toggle off by default)..."
adb shell am start -n com.jon2g.aa_keyboard_unlock/.SettingsActivity >/dev/null 2>&1 || true
echo "  -> Open AA Keyboard Unlock app and enable the toggle, then enable module in LSPosed for gearhead + maps."

echo "[3/6] adb forward tcp:5277 tcp:5277"
adb forward tcp:5277 tcp:5277

echo "[4/6] Clearing logcat buffer..."
adb logcat -c

echo "[5/6] Launch DHU in another terminal:"
echo "  $DHU -c $CONFIG"
echo ""
echo "Manual test steps:"
echo "  A) Module OFF — DHU: parking_brake false; gear 100; speed 10  => keyboard should LOCK"
echo "  B) Module ON  — same DHU moving state                        => keyboard should UNLOCK"
echo "  C) Module ON  — speed 0; gear 101; parking_brake true        => keyboard works"
echo "  D) Toggle module OFF while moving                            => lock returns"
echo ""
echo "[6/6] Capture logs (run while testing):"
echo "  adb logcat -s AAKeyboardUnlock:* LSPosed:* | tee $ROOT/re/dhu_validation.log"
