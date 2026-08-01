#!/usr/bin/env bash
# Shared helpers for Desktop Head Unit (DHU) setup over adb.
#
# Usage: source "$(dirname "$0")/dhu-common.sh"
#
# Prefer device-triggered adb refresh (adbd restart / USB mode) over host kill-server.
# Force-stopping gearhead stops the head unit server; restart it before launching DHU.

DHU_DIR="${DHU_DIR:-$HOME/android/android-sdk/extras/google/auto}"
DHU_CONFIG="${DHU_CONFIG:-config/config_moving_sensors.ini}"
DHU_ADB_PORT="${DHU_ADB_PORT:-5277}"
HEAD_UNIT_SERVICE="com.google.android.projection.gearhead/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService"

adb_device_ready() {
  adb get-state 2>/dev/null | grep -q '^device$'
}

wait_for_adb_device() {
  echo "Waiting for adb device..."
  local attempt=0
  while (( attempt < 45 )); do
    if adb_device_ready; then
      return 0
    fi
    sleep 1
    ((attempt++))
  done
  echo "ERROR: adb device not ready." >&2
  return 1
}

restart_adbd_on_device() {
  echo "Restarting adbd on device..."
  adb shell setprop ctl.restart adbd >/dev/null 2>&1 || true
  if adb shell "su 0 setprop ctl.restart adbd" >/dev/null 2>&1; then
    echo "  adbd restart requested (root)."
  elif adb shell setprop ctl.restart adbd >/dev/null 2>&1; then
    echo "  adbd restart requested (shell)."
  else
    echo "  adbd restart via setprop not permitted; using host USB reset."
  fi
}

refresh_adb_connection() {
  echo "Refreshing adb connection (device-first, no replug)..."

  if adb_device_ready; then
    restart_adbd_on_device
    sleep 2
    if adb_device_ready; then
      echo "  adbd restart complete."
    fi
  fi

  if adb_device_ready; then
    echo "Restarting USB adb mode on device..."
    adb usb >/dev/null 2>&1 || true
    sleep 2
  fi

  if ! adb_device_ready; then
    echo "Waiting for device after adbd/USB reset..."
    wait_for_adb_device || true
  fi

  if adb_device_ready; then
    adb reconnect device >/dev/null 2>&1 || true
    sleep 2
  fi

  if adb_device_ready; then
    echo "adb connection refreshed."
    return 0
  fi

  echo "Device-first refresh failed; falling back to host adb restart..."
  adb kill-server 2>/dev/null || true
  adb start-server
  wait_for_adb_device
  adb reconnect device >/dev/null 2>&1 || true
  sleep 2
  wait_for_adb_device
}

reset_adb_connection() {
  refresh_adb_connection
}

setup_dhu_adb_forward() {
  echo "Forwarding adb port ${DHU_ADB_PORT}..."
  adb forward --remove "tcp:${DHU_ADB_PORT}" 2>/dev/null || true
  adb forward "tcp:${DHU_ADB_PORT}" "tcp:${DHU_ADB_PORT}"
}

stop_head_unit_server() {
  echo "Stopping Android Auto head unit server..."
  adb shell am stopservice -n "$HEAD_UNIT_SERVICE" >/dev/null 2>&1 || true
}

restart_head_unit_server() {
  stop_head_unit_server
  sleep 1
  echo "Starting Android Auto head unit server..."
  if adb shell am start-foreground-service -n "$HEAD_UNIT_SERVICE" >/dev/null 2>&1; then
    sleep 2
    return 0
  fi

  if adb shell am startservice -n "$HEAD_UNIT_SERVICE" >/dev/null 2>&1; then
    sleep 2
    return 0
  fi

  echo "WARNING: Could not start head unit server via adb." >&2
  echo "  On the phone: Android Auto → ⋮ → Start head unit server" >&2
  return 1
}

head_unit_server_running() {
  adb shell dumpsys activity services DeveloperHeadUnitNetworkService 2>/dev/null \
    | grep -qi 'app=ProcessRecord' 2>/dev/null
}

dhu_terminal_command() {
  echo "cd \"$DHU_DIR\" && ./desktop-head-unit -c \"$DHU_CONFIG\" -u"
}

run_desktop_head_unit() {
  cd "$DHU_DIR"
  exec ./desktop-head-unit -c "$DHU_CONFIG" -u
}

prepare_dhu_session() {
  refresh_adb_connection
}
