#!/usr/bin/env bash
# Launch Android Auto Desktop Head Unit with the moving-sensors config.
#
# Prerequisites:
#   - Phone connected via USB with USB debugging enabled
#   - Android Auto developer mode enabled on the phone
#
# Usage:
#   ./scripts/run-dhu-moving.sh
#
# Equivalent to (from the DHU install directory):
#   ./desktop-head-unit -c config/config_moving_sensors.ini -u
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# shellcheck source=scripts/dhu-common.sh
source "$ROOT/scripts/dhu-common.sh"

if [[ ! -x "$DHU_DIR/desktop-head-unit" ]]; then
  echo "ERROR: desktop-head-unit not found at $DHU_DIR/desktop-head-unit" >&2
  echo "Install via Android Studio SDK Manager → Android Auto Desktop Head Unit emulator." >&2
  exit 1
fi

if [[ ! -f "$DHU_DIR/$DHU_CONFIG" ]]; then
  echo "ERROR: Config not found at $DHU_DIR/$DHU_CONFIG" >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found on PATH." >&2
  exit 1
fi

echo "== AA Keyboard Unlock — DHU (moving config) =="
echo "DHU dir:   $DHU_DIR"
echo "Config:    $DHU_CONFIG"
echo "Transport: USB (-u)"
echo ""

prepare_dhu_session

run_desktop_head_unit
