#!/usr/bin/env bash
# Install an APK via adb. Uninstalls first when signatures are incompatible
# (e.g. switching between signed release and debug builds).
#
# Usage: ./scripts/install-apk.sh <apk-path> [package-name]
set -euo pipefail

APK="${1:?APK path required}"
PKG="${2:-com.jon2g.aa_keyboard_unlock}"

if [[ ! -f "$APK" ]]; then
  echo "ERROR: APK not found: $APK" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found." >&2
  exit 1
fi

output=""
if output=$(adb install -r "$APK" 2>&1); then
  echo "$output"
  exit 0
fi

if echo "$output" | grep -qiE 'INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match'; then
  echo "$output"
  echo "Signature mismatch — uninstalling $PKG, then installing debug build..."
  adb uninstall "$PKG"
  adb install "$APK"
else
  echo "$output" >&2
  exit 1
fi
