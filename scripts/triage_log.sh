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
echo ""

section() { echo ""; echo "--- $1 ---"; }

section "Install / hook health"
grep -E '\[INSTALL\]|\[GH-INSTALL\]|\[MAPS-INSTALL\]|Hooked kcw\.k Maps' "$LOG" | tail -5 || echo "(none)"

section "Search tap path (last 5)"
grep -E 'kcw\.k\(10\)|GH-MAPS-001.*opening keyboard|GH-MAPS-002.*attempt' "$LOG" | tail -5 || echo "(none)"

section "Stock keyboard"
grep -E 'GH-MAPS-003|GH-MAPS-004.*projected IME|xcu\.h|force-started PhoneKeyboard' "$LOG" | tail -8 || echo "(none)"

section "Custom overlay (CRITICAL)"
grep -E 'GH-KBD-00[1-4]|GH-DBG-00[1-4]' "$LOG" | tail -15 || echo "(none — overlay never attempted)"

section "Mic path"
grep -E 'GH-MIC|MAPS-MIC|tur\.s|qib\.l\(10\)|mic passthrough|kxe\.F\(10\)' "$LOG" | tail -10 || echo "(none)"

section "Maps process"
grep -E '\[MAPS\].*MAPS-00[1-4]|GhostActivity|no rek overlay' "$LOG" | tail -8 || echo "(none)"

section "Pass/fail summary"
pass=0
fail=0

if grep -q 'GH-KBD-001.*show overlay' "$LOG"; then
  echo "PASS: overlay attach attempted (GH-KBD-001)"
  pass=$((pass + 1))
else
  echo "FAIL: no GH-KBD-001 show overlay — custom keyboard never reached UI layer"
  fail=$((fail + 1))
fi

if grep -q 'GH-KBD-004.*no attach host' "$LOG"; then
  echo "FAIL: GH-KBD-004 no attach host — no Activity decor in gearhead process"
  fail=$((fail + 1))
fi

if grep -q 'attach target=decor:' "$LOG"; then
  echo "INFO: overlay attached to phone-side DecorView (may be invisible on car/DHU)"
  grep 'GH-DBG-003\|attach target=decor' "$LOG" | tail -3
fi

if grep -q 'kxe\.F(10) blocked' "$LOG"; then
  echo "WARN: kxe.F(10) blocked — mic may be broken on voice-plate path"
  fail=$((fail + 1))
elif grep -q 'kxe\.F(10) allowed' "$LOG"; then
  echo "PASS: kxe.F(10) allowed (newer build)"
  pass=$((pass + 1))
fi

if grep -q 'force-started PhoneKeyboardActivity' "$LOG" && ! grep -q 'GH-KBD-001' "$LOG"; then
  echo "FAIL: force-start returned early — overlay skipped (older build bug)"
  fail=$((fail + 1))
fi

if grep -q 'MAPS-004.*no rek overlay' "$LOG"; then
  echo "WARN: Maps main process has no rek overlay — stock Maps keyboard path dead"
fi

echo ""
echo "Checks passed: $pass  warnings/failures flagged: $fail"
echo ""
echo "Next: run one controlled tap, capture fresh log, re-run this script."
echo "See docs/TESTING_STRATEGY.md for the full matrix."
