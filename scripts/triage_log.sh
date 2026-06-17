#!/usr/bin/env bash
# Triage AA Keyboard Unlock LSPosed module logs.
# Usage: ./scripts/triage_log.sh path/to/modules_*.log
set -euo pipefail

LOG="${1:-}"
if [[ -z "$LOG" || ! -f "$LOG" ]]; then
  echo "Usage: $0 path/to/modules_*.log"
  exit 1
fi

echo "== AA Keyboard Unlock log triage =="
echo "File: $LOG"
echo "Lines: $(wc -l < "$LOG")"
echo ""

version=$(grep -o 'AAKeyboardUnlock v[0-9]*' "$LOG" | tail -1 || true)
echo "Module build: ${version:-UNKNOWN}"
debug_on=$(grep -c 'debug=true' "$LOG" 2>/dev/null | head -1 || true)
debug_on=${debug_on:-0}
if [[ "$debug_on" -eq 0 ]]; then
  echo "NOTE: Install the logging APK (build=logging or debug) for MAPS-DRIVE-* traces."
fi
echo ""

section() { echo ""; echo "--- $1 ---"; }

pass=0
fail=0
warn=0

section "Install / hook health"
grep -E '\[INSTALL\]|\[GH-INSTALL\]|\[MAPS-INSTALL\]' "$LOG" | tail -5 || echo "(none)"

section "Gearhead keyboard unlock (external Car apps)"
grep -E 'xcu\.h|xdl\.d|xdb\.onStart|forced c=false|gxy\.d\(\)|lgz\.a\(\) forced true' "$LOG" | tail -10 || echo "(none)"

section "Maps driving instrumentation (logging build required)"
grep -E 'MAPS-DRIVE-00[0-9]|MAPS-DRIVE-01[01]|MAPS-DRIVE-020' "$LOG" | tail -25 || echo "(none — install logging APK and reopen Maps)"

drive_audit=$(grep -c 'MAPS-DRIVE-003' "$LOG" 2>/dev/null || echo 0)
drive_voice=$(grep -c 'MAPS-DRIVE-008.*voiceOnly\|MAPS-DRIVE-001.*voice-only' "$LOG" 2>/dev/null || echo 0)
drive_search=$(grep -c 'MAPS-DRIVE-009\|MAPS-DRIVE-008.*searchHint' "$LOG" 2>/dev/null || echo 0)
drive_qha=$(grep -c 'MAPS-DRIVE-005.*qha constructed' "$LOG" 2>/dev/null || echo 0)
drive_fields=$(grep -c 'MAPS-DRIVE-007' "$LOG" 2>/dev/null || echo 0)

section "Pass/fail summary"

if grep -q '\[MAPS-INSTALL\]' "$LOG"; then
  echo "PASS: Maps hooks installed ([MAPS-INSTALL])"
  pass=$((pass + 1))
else
  echo "FAIL: no [MAPS-INSTALL] — scope Maps in LSPosed"
  fail=$((fail + 1))
fi

if grep -q '\[GH-INSTALL\]' "$LOG"; then
  echo "PASS: Gearhead hooks installed ([GH-INSTALL])"
  pass=$((pass + 1))
else
  echo "FAIL: no [GH-INSTALL] — scope gearhead in LSPosed"
  fail=$((fail + 1))
fi

if [[ "$drive_audit" -gt 0 ]]; then
  echo "PASS: MAPS-DRIVE-003 install audit present (x$drive_audit)"
  pass=$((pass + 1))
  grep 'MAPS-DRIVE-003' "$LOG" | tail -1
elif grep -q 'debug=false' "$LOG"; then
  echo "WARN: no MAPS-DRIVE-003 — install logging APK and relaunch Maps"
  warn=$((warn + 1))
else
  echo "WARN: no MAPS-DRIVE-003 — open Maps on AA with logging APK installed"
  warn=$((warn + 1))
fi

if [[ "$drive_qha" -gt 0 ]]; then
  echo "INFO: qha constructed x$drive_qha — compare driving vs parked sessions"
  grep 'MAPS-DRIVE-005.*qha' "$LOG" | tail -3
fi

if [[ "$drive_fields" -gt 0 ]]; then
  echo "INFO: MAPS-DRIVE-007 field dumps x$drive_fields"
fi

if [[ "$drive_voice" -gt 0 ]] && [[ "$drive_search" -eq 0 ]]; then
  echo "INFO: voice-only resources loaded (driving-like) x$drive_voice"
  grep -E 'MAPS-DRIVE-008.*voiceOnly|MAPS-DRIVE-001.*voice-only' "$LOG" | tail -3
elif [[ "$drive_search" -gt 0 ]] && [[ "$drive_voice" -eq 0 ]]; then
  echo "INFO: search-hint resources loaded (parked-like) x$drive_search"
  grep -E 'MAPS-DRIVE-009|MAPS-DRIVE-008.*searchHint' "$LOG" | tail -3
elif [[ "$drive_voice" -gt 0 ]] && [[ "$drive_search" -gt 0 ]]; then
  echo "INFO: both voice-only and search-hint seen — compare timestamps with DHU driving toggle"
fi

if grep -qE 'GH-MAPS-00|kcw\.k\(10\)|GH-KBD-00|MAPS-HINT-001|GH-HINT-001' "$LOG"; then
  echo "WARN: legacy Maps keyboard/overlay/hint lines present — expect none after driving-trace pivot"
  warn=$((warn + 1))
fi

echo ""
echo "Checks passed: $pass  failures: $fail  warnings: $warn"
echo ""
echo "Capture tip: toggle DHU driving/parked, wait for search bar, do not tap search."
echo "Compare MAPS-DRIVE-005/007/008 (driving) vs MAPS-DRIVE-009 (parked)."
echo "See docs/TESTING_STRATEGY.md test H and docs/maps_hook_targets.md."
