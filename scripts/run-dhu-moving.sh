#!/usr/bin/env bash
# Launch Android Auto Desktop Head Unit with the moving-sensors config.
#
# Prerequisites:
#   - Phone connected via USB with USB debugging enabled
#   - Android Auto developer mode + head unit server started on the phone
#
# Usage:
#   ./scripts/run-dhu-moving.sh
#
# Equivalent to (from the DHU install directory):
#   adb forward tcp:5277 tcp:5277
#   ./desktop-head-unit -c config/config_moving_sensors.ini -u
set -euo pipefail

DHU_DIR="${DHU_DIR:-$HOME/android/android-sdk/extras/google/auto}"
CONFIG="${CONFIG:-config/config_moving_sensors.ini}"

if [[ ! -x "$DHU_DIR/desktop-head-unit" ]]; then
  echo "ERROR: desktop-head-unit not found at $DHU_DIR/desktop-head-unit" >&2
  echo "Install via Android Studio SDK Manager → Android Auto Desktop Head Unit emulator." >&2
  exit 1
fi

if [[ ! -f "$DHU_DIR/$CONFIG" ]]; then
  echo "ERROR: Config not found at $DHU_DIR/$CONFIG" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH." >&2
  exit 1
fi

echo "== AA Keyboard Unlock — DHU (moving config) =="
echo "DHU dir:  $DHU_DIR"
echo "Config:   $CONFIG"
echo ""

echo "Forwarding adb port 5277..."
adb forward tcp:5277 tcp:5277

cd "$DHU_DIR"
echo "Starting desktop-head-unit..."
exec ./desktop-head-unit -c "$CONFIG" -u
