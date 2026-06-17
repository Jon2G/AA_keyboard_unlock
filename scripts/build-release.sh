#!/usr/bin/env bash
# Build signed release + logging APKs for distribution.
#
# Usage:
#   export ANDROID_SIGNING_PASSWORD='…'
#   ./scripts/build-release.sh 1.1.0
#   ./scripts/build-release.sh 1.1.0 --install-release   # adb install release APK only
#
# Outputs (repo root):
#   aa-keyboard-unlock-{version}.apk
#   aa-keyboard-unlock-{version}-log.apk
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GRADLE_DIR="$ROOT/src"
KEYSTORE="$GRADLE_DIR/aa-keyboard-unlock.keystore"
INSTALL_RELEASE=false

VERSION_NAME="${1:-}"
shift || true

for arg in "$@"; do
  case "$arg" in
    --install-release|-i) INSTALL_RELEASE=true ;;
    -h|--help)
      sed -n '2,14p' "$0"
      exit 0
      ;;
    *)
      echo "Unknown option: $arg" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$VERSION_NAME" ]]; then
  echo "Usage: $0 <version> [--install-release]" >&2
  echo "Example: $0 1.1.0" >&2
  exit 1
fi

IFS='.' read -r MAJOR MINOR PATCH <<< "${VERSION_NAME%%-*}"
MAJOR=${MAJOR:-0}
MINOR=${MINOR:-0}
PATCH=${PATCH:-0}
VERSION_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

if [[ ! -f "$KEYSTORE" ]]; then
  echo "ERROR: Keystore not found at $KEYSTORE" >&2
  echo "Place aa-keyboard-unlock.keystore in src/ or set up CI secrets." >&2
  exit 1
fi

if [[ -z "${ANDROID_SIGNING_PASSWORD:-}" ]]; then
  echo "ERROR: Set ANDROID_SIGNING_PASSWORD for signed release builds." >&2
  exit 1
fi

if [[ -n "${JAVA_HOME:-}" ]] && command -v /usr/libexec/java_home >/dev/null 2>&1; then
  for version in 21 17; do
    if new_java_home="$(/usr/libexec/java_home -v "$version" 2>/dev/null)"; then
      export JAVA_HOME="$new_java_home"
      break
    fi
  done
fi

pick_apk() {
  local dir="$1"
  if [[ -f "$GRADLE_DIR/app/build/outputs/apk/${dir}/app-${dir}.apk" ]]; then
    echo "$GRADLE_DIR/app/build/outputs/apk/${dir}/app-${dir}.apk"
  else
    echo "$GRADLE_DIR/app/build/outputs/apk/${dir}/app-${dir}-unsigned.apk"
  fi
}

echo "== Building release v${VERSION_NAME} (versionCode ${VERSION_CODE}) =="
(
  cd "$GRADLE_DIR"
  ./gradlew assembleRelease assembleLogging \
    -PversionName="$VERSION_NAME" \
    -PversionCode="$VERSION_CODE"
)

RELEASE_APK="$(pick_apk release)"
LOG_APK="$(pick_apk logging)"
OUT_RELEASE="$ROOT/aa-keyboard-unlock-${VERSION_NAME}.apk"
OUT_LOG="$ROOT/aa-keyboard-unlock-${VERSION_NAME}-log.apk"

cp "$RELEASE_APK" "$OUT_RELEASE"
cp "$LOG_APK" "$OUT_LOG"

echo ""
echo "Release APK (silent): $OUT_RELEASE"
echo "Logging APK:          $OUT_LOG"
ls -lh "$OUT_RELEASE" "$OUT_LOG"

if $INSTALL_RELEASE; then
  if ! command -v adb >/dev/null 2>&1; then
    echo "ERROR: adb not found; cannot install." >&2
    exit 1
  fi
  echo ""
  echo "Installing release APK..."
  "$ROOT/scripts/install-apk.sh" "$OUT_RELEASE"
fi
