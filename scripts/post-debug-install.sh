#!/usr/bin/env bash
# After installing the debug module, reset target apps, adb, DHU, and scrcpy.
#
# DHU and scrcpy are started here and should stay running for the whole debug
# session. Do not stop or restart them until the next debug reinstall (when this
# script runs again). Only post-debug-install.sh should kill them.
#
# Usage: ./scripts/post-debug-install.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/dhu-common.sh
source "$ROOT/scripts/dhu-common.sh"

DHU_LOG="${DHU_LOG:-/tmp/aa-keyboard-unlock-dhu.log}"
SCRCPY_LOG="${SCRCPY_LOG:-/tmp/aa-keyboard-unlock-scrcpy.log}"

stop_debug_tools() {
  if pgrep -f desktop-head-unit >/dev/null 2>&1; then
    echo "Stopping existing desktop-head-unit..."
    pkill -f desktop-head-unit || true
    sleep 1
  fi

  if pgrep -x scrcpy >/dev/null 2>&1; then
    echo "Stopping existing scrcpy..."
    pkill -x scrcpy || true
    sleep 1
  fi
}

wait_for_process() {
  local pattern="$1"
  local timeout="${2:-20}"
  local attempt=0
  while (( attempt < timeout )); do
    if pgrep -f "$pattern" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    ((attempt++))
  done
  return 1
}

launch_in_terminal() {
  local cmd="$1"
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: launch_in_terminal requires macOS Terminal (Darwin)." >&2
    return 1
  fi

  local escaped="${cmd//\\/\\\\}"
  escaped="${escaped//\"/\\\"}"
  osascript -e "tell application \"Terminal\" to do script \"$escaped\""
}

launch_dhu() {
  local dhu_cmd
  dhu_cmd="$(dhu_terminal_command)"
  echo "Starting desktop-head-unit in Terminal (USB -u)..."
  launch_in_terminal "${dhu_cmd} 2>&1 | tee -a \"$DHU_LOG\""
}

launch_scrcpy() {
  if ! command -v scrcpy >/dev/null 2>&1; then
    echo "WARNING: scrcpy not found on PATH; skipping." >&2
    return 0
  fi

  echo "Starting scrcpy in Terminal..."
  launch_in_terminal "scrcpy 2>&1 | tee -a \"$SCRCPY_LOG\""
}

verify_debug_tools() {
  local ok=true

  if wait_for_process desktop-head-unit 25; then
    echo "desktop-head-unit is running."
  else
    echo "ERROR: desktop-head-unit did not stay running." >&2
    ok=false
  fi

  if command -v scrcpy >/dev/null 2>&1; then
    if wait_for_process 'scrcpy' 15 && pgrep -x scrcpy >/dev/null 2>&1; then
      echo "scrcpy is running."
    else
      echo "ERROR: scrcpy did not stay running." >&2
      ok=false
    fi
  fi

  $ok
}

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found." >&2
  exit 1
fi

if [[ ! -x "$DHU_DIR/desktop-head-unit" ]]; then
  echo "ERROR: desktop-head-unit not found at $DHU_DIR/desktop-head-unit" >&2
  exit 1
fi

if [[ ! -f "$DHU_DIR/$DHU_CONFIG" ]]; then
  echo "ERROR: DHU config not found at $DHU_DIR/$DHU_CONFIG" >&2
  exit 1
fi

stop_debug_tools

echo "Force-stopping Android Auto and Maps..."
adb shell am force-stop com.google.android.projection.gearhead
adb shell am force-stop com.google.android.apps.maps

refresh_adb_connection

launch_dhu
sleep 3
launch_scrcpy

if verify_debug_tools; then
  echo "Post-debug install steps complete."
else
  echo "Post-debug install finished with errors. Check $DHU_LOG and $SCRCPY_LOG." >&2
  exit 1
fi
