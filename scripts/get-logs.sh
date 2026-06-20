#!/usr/bin/env bash
# Pull LSPosed logs from a rooted device and replace repo logs/.
#
# Stages the same paths LSPosed Manager bundles (lspd log dirs, scopes db,
# logcat, dmesg, tombstones, anr, dropbox, Magisk module props), then pulls
# into logs/ and writes logs/lposed_logs.zip.
#
# Usage:
#   ./scripts/get-logs.sh
#
# Requirements:
#   - adb with exactly one device (or set ANDROID_SERIAL)
#   - root (Magisk / KernelSU) with `su` for com.android.shell
#   - LSPosed installed
#
# After capture:
#   ./scripts/triage_log.sh logs/log/modules_*.log
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOGS_DIR="$ROOT/logs"
STAGE_REMOTE="/data/local/tmp/lsposed_logs_export"
TMP_PULL="$(mktemp -d "${TMPDIR:-/tmp}/aa-kbd-logs.XXXXXX")"

cleanup() {
  rm -rf "$TMP_PULL"
  adb shell rm -rf "$STAGE_REMOTE" 2>/dev/null || true
}
trap cleanup EXIT

usage() {
  sed -n '2,18p' "$0"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -gt 0 ]]; then
  echo "Unknown option: $1" >&2
  usage >&2
  exit 1
fi

if ! command -v adb >/dev/null 2>&1; then
  echo "ERROR: adb not found." >&2
  exit 1
fi

device_count=$(adb devices | awk 'NR > 1 && $2 == "device" { c++ } END { print c + 0 }')
if [[ "$device_count" -eq 0 ]]; then
  echo "ERROR: no adb device connected." >&2
  exit 1
fi
if [[ "$device_count" -gt 1 && -z "${ANDROID_SERIAL:-}" ]]; then
  echo "ERROR: multiple adb devices; set ANDROID_SERIAL." >&2
  adb devices -l
  exit 1
fi

if ! adb shell su -c true >/dev/null 2>&1; then
  echo "ERROR: root unavailable via adb shell su." >&2
  echo "Grant root to Shell (com.android.shell) in Magisk/KernelSU, then retry." >&2
  exit 1
fi

echo "== AA Keyboard Unlock — LSPosed log capture =="
echo "Device: ${ANDROID_SERIAL:-$(adb get-serialno)}"
echo "Remote stage: $STAGE_REMOTE"
echo ""

read -r -d '' REMOTE_SCRIPT <<'EOF' || true
set -eu
STAGE=/data/local/tmp/lsposed_logs_export
LSPD=/data/adb/lspd

rm -rf "$STAGE"
mkdir -p "$STAGE/log"

if [[ ! -d "$LSPD/log" ]]; then
  echo "ERROR: $LSPD/log not found — is LSPosed installed?" >&2
  exit 1
fi

cp -a "$LSPD/log/." "$STAGE/log/"

if [[ -d "$LSPD/log.old" ]]; then
  mkdir -p "$STAGE/log.old"
  cp -a "$LSPD/log.old/." "$STAGE/log.old/"
fi

if [[ -f "$LSPD/config/modules_config.db" ]]; then
  cp "$LSPD/config/modules_config.db" "$STAGE/"
  if command -v sqlite3 >/dev/null 2>&1; then
    {
      echo ">>>>> Dumping cursor [module_pkg_name, apk_path]"
      sqlite3 -line "$STAGE/modules_config.db" \
        "SELECT module_pkg_name, apk_path FROM modules ORDER BY mid;" 2>/dev/null || true
    } > "$STAGE/db_modules.txt"

    {
      echo ">>>>> Dumping cursor [app_pkg_name, module_pkg_name, user_id]"
      sqlite3 -line "$STAGE/modules_config.db" \
        "SELECT app_pkg_name, module_pkg_name, user_id FROM scope ORDER BY app_pkg_name, module_pkg_name;" 2>/dev/null || true
    } > "$STAGE/db_scope.txt"

    sqlite3 -separator $'\t' "$STAGE/modules_config.db" \
      "SELECT app_pkg_name, module_pkg_name FROM scope ORDER BY app_pkg_name, module_pkg_name;" \
      > "$STAGE/scopes.txt" 2>/dev/null || true
  fi
fi

logcat -b all -d > "$STAGE/full.log" 2>/dev/null || true
dmesg > "$STAGE/dmesg.log" 2>/dev/null || true

[[ -d /data/tombstones ]] && cp -a /data/tombstones "$STAGE/" 2>/dev/null || true
[[ -d /data/anr ]] && cp -a /data/anr "$STAGE/" 2>/dev/null || true
[[ -d /data/system/dropbox ]] && cp -a /data/system/dropbox "$STAGE/" 2>/dev/null || true

if [[ -d /data/adb/modules ]]; then
  mkdir -p "$STAGE/modules"
  for module_dir in /data/adb/modules/*; do
    [[ -d "$module_dir" ]] || continue
    name="$(basename "$module_dir")"
    dest="$STAGE/modules/$name"
    mkdir -p "$dest"
    for f in module.prop remove disable update sepolicy.rule; do
      [[ -f "$module_dir/$f" ]] && cp "$module_dir/$f" "$dest/" 2>/dev/null || true
    done
  done
fi

mkdir -p "$STAGE/self"
for f in maps mountinfo status; do
  [[ -f "/proc/self/$f" ]] && cp "/proc/self/$f" "$STAGE/self/" 2>/dev/null || true
done

chown -R shell:shell "$STAGE"
chmod -R a+rX "$STAGE"
echo "staged $(find "$STAGE" -type f | wc -l | tr -d ' ') files"
EOF

REMOTE_SH="$(mktemp "${TMPDIR:-/tmp}/aa-kbd-stage.XXXXXX.sh")"
printf '%s\n' "$REMOTE_SCRIPT" > "$REMOTE_SH"
adb push "$REMOTE_SH" /data/local/tmp/stage_lsposed_logs.sh >/dev/null
rm -f "$REMOTE_SH"

echo "[1/4] Staging LSPosed logs on device..."
if ! adb shell su -c "sh /data/local/tmp/stage_lsposed_logs.sh" 2>&1; then
  echo "ERROR: failed to stage logs on device." >&2
  exit 1
fi
adb shell su -c "rm -f /data/local/tmp/stage_lsposed_logs.sh" 2>/dev/null || true

echo "[2/4] Pulling staged logs..."
mkdir -p "$TMP_PULL/staged"
adb pull "$STAGE_REMOTE/." "$TMP_PULL/staged/"

if [[ ! -d "$TMP_PULL/staged/log" ]]; then
  echo "ERROR: pull did not contain log/ — check root and LSPosed." >&2
  exit 1
fi

echo "[3/4] Replacing $LOGS_DIR ..."
rm -rf "$LOGS_DIR"
mkdir -p "$LOGS_DIR"
shopt -s dotglob nullglob
mv "$TMP_PULL/staged"/* "$LOGS_DIR/" 2>/dev/null || true
shopt -u dotglob nullglob

echo "[4/4] Writing logs/lposed_logs.zip ..."
if command -v zip >/dev/null 2>&1; then
  (cd "$LOGS_DIR" && zip -qr lposed_logs.zip . -x lposed_logs.zip)
else
  echo "WARN: zip not found locally; skipping logs/lposed_logs.zip" >&2
fi

latest_modules="$(ls -t "$LOGS_DIR"/log/modules_*.log 2>/dev/null | head -1 || true)"
verbose_log="$(ls -t "$LOGS_DIR"/log/verbose_*.log 2>/dev/null | head -1 || true)"

echo ""
echo "Done. Logs saved to: $LOGS_DIR"
[[ -n "$latest_modules" ]] && echo "Latest modules log: $latest_modules"
[[ -n "$verbose_log" ]] && echo "Latest verbose log: $verbose_log"
echo ""
if [[ -n "$latest_modules" ]]; then
  echo "Triage:"
  echo "  ./scripts/triage_log.sh \"$latest_modules\""
else
  echo "No modules_*.log found yet — reproduce the issue, then run this script again."
fi
