#!/usr/bin/env bash
# Build a debug APK for local development and LSPosed testing.
#
# Usage:
#   ./scripts/build-debug.sh           # build only
#   ./scripts/build-debug.sh --install # build and adb install -r
#
# Requirements:
#   - JDK 17 or 21 (Java 26 is not supported by AGP)
#   - Android SDK (sdk.dir in src/local.properties or ANDROID_HOME)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_DIR="$ROOT/src"
APK="$GRADLE_DIR/app/build/outputs/apk/debug/app-debug.apk"
INSTALL=false

for arg in "$@"; do
  case "$arg" in
    --install|-i) INSTALL=true ;;
    -h|--help)
      sed -n '2,12p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      echo "Usage: $0 [--install]" >&2
      exit 1
      ;;
  esac
done

if [[ ! -x "$GRADLE_DIR/gradlew" ]]; then
  echo "ERROR: gradlew not found at $GRADLE_DIR/gradlew" >&2
  exit 1
fi

# Prefer Java 21, then 17; avoid Java 26+ which breaks AGP.
current_java_ver=""
if [[ -n "${JAVA_HOME:-}" ]]; then
  current_java_ver=$("$JAVA_HOME/bin/java" -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
fi

if [[ -z "${JAVA_HOME:-}" ]] || [[ "$current_java_ver" -ge 22 ]]; then
  if command -v /usr/libexec/java_home >/dev/null 2>&1; then
    for version in 21 17; do
      if new_java_home="$(/usr/libexec/java_home -v "$version" 2>/dev/null)"; then
        export JAVA_HOME="$new_java_home"
        break
      fi
    done
  fi
fi

if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "ERROR: Set JAVA_HOME to JDK 17 or 21." >&2
  exit 1
fi

JAVA_VERSION="$("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "Using Java: $JAVA_VERSION"

LOCAL_PROPS="$GRADLE_DIR/local.properties"
if [[ ! -f "$LOCAL_PROPS" ]]; then
  SDK_DIR="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -z "$SDK_DIR" && -d "$HOME/android/android-sdk" ]]; then
    SDK_DIR="$HOME/android/android-sdk"
  fi
  if [[ -z "$SDK_DIR" && -d "$HOME/Library/Android/sdk" ]]; then
    SDK_DIR="$HOME/Library/Android/sdk"
  fi
  if [[ -n "$SDK_DIR" ]]; then
    echo "sdk.dir=$SDK_DIR" > "$LOCAL_PROPS"
    echo "Created $LOCAL_PROPS (sdk.dir=$SDK_DIR)"
  else
    echo "WARNING: $LOCAL_PROPS missing and ANDROID_HOME not set." >&2
    echo "Create src/local.properties with: sdk.dir=/path/to/android-sdk" >&2
  fi
fi

echo "== Building debug APK =="
(
  cd "$GRADLE_DIR"
  ./gradlew assembleDebug -PversionName="99.99.99" -PversionCode="99999"
)

if [[ ! -f "$APK" ]]; then
  echo "ERROR: Expected APK not found at $APK" >&2
  exit 1
fi

echo ""
echo "Debug APK: $APK"
ls -lh "$APK"

if $INSTALL; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not found; cannot install." >&2
    exit 1
  fi
  echo ""
  echo "Installing on connected device..."
  "$ROOT/scripts/install-apk.sh" "$APK"
  echo "Installed com.jon2g.aa_keyboard_unlock (debug)"
fi
