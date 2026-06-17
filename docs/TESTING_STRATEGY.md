# Maps-on-AA — Testing Strategy

Use this matrix to isolate *which layer* fails. After the **driving-trace pivot** (2026-06-17), Maps hooks are **log-only**; gearhead still unlocks keyboard for **external** Car apps.

## Current status (2026-06-17)

| Layer | Expected |
|-------|----------|
| Gearhead sensor spoof + `xdl` unlock | **Pass** for external apps |
| Maps custom overlay / label rewrite / `kcw.k(10)` intercept | **Removed** |
| Maps driving trace (`MAPS-DRIVE-*`) | **Active** when **Debug** on |
| Maps search keyboard while driving | **Stock** (voice-only label) — not modified |

**Engineering focus:** find the Maps in-process gate that loads `CAR_VOICE_ONLY_WHEN_DRIVING` vs `CAR_SEARCH_HINT`, then design a minimal patch.

---

## Phase 0 — Preflight (5 min)

1. **LSPosed scope:** LSPosed **2.1+** (libxposed API 101+).
   - `com.google.android.projection.gearhead`
   - `com.google.android.apps.maps` — **all processes** (including `:car`)

2. Module enabled + **Debug** toggle (**required** for `MAPS-DRIVE-*`).

3. **Capture:**

```bash
adb logcat -c
# … perform ONE action …
adb logcat -d | grep -E 'AAKeyboardUnlock|LSPosed-Bridge.*AAKeyboard' > logs/log/test_$(date +%Y-%m-%dT%H-%M-%S).log
```

4. **Triage:** `./scripts/triage_log.sh logs/log/your_test.log`

---

## Phase 1 — Layer isolation (one action each)

Wait 3 seconds between actions.

| ID | Action | Proves | Pass log IDs |
|----|--------|--------|--------------|
| **A** | Open AA + Maps only | Hooks load | `[GH-INSTALL]`, `[MAPS-INSTALL]` |
| **B** | External app text field (e.g. messaging) | Gearhead IME | `xcu.h`, `xdb.onStart`, `forced c=false` |
| **H1** | Open Maps; DHU **driving** on; **do not tap** search | Driving label trace | `MAPS-DRIVE-005 qha`, `MAPS-DRIVE-008 voiceOnly` |
| **H2** | Same; DHU **parked** / sensors stopped | Parked label trace | `MAPS-DRIVE-005 qha`, `MAPS-DRIVE-009` or `008 searchHint` — **no** voice-only `008` |
| **H3** | Toggle driving ↔ parked; capture one log each | Correlation | Compare `MAPS-DRIVE-007` field dumps side by side |

**Decision tree:**

```
A fails → LSPosed scope / module not loaded
B fails → gearhead hooks (see gearhead_hook_targets.md)
H*: no MAPS-DRIVE lines → enable Debug, relaunch Maps
H1 has 008 voiceOnly, H2 does not → trace working; next step is hook the decision point
H1 and H2 identical → restriction may be cached or outside traced paths — expand 011 API dump
```

---

## Phase 2 — Event ID checklist

### Maps driving restriction (primary)

| ID | Required? | Interpretation |
|----|-------------|----------------|
| `MAPS-DRIVE-003` | Yes (H1/H2) | Install audit: kur count, qha/qwt/qhf/trt, res ids |
| `MAPS-DRIVE-005` | Yes | `qha` / `qwt` constructed |
| `MAPS-DRIVE-007` | Desired | Field dump after construct — compare driving vs parked |
| `MAPS-DRIVE-008` | Driving session | `voiceOnly` + `resId` + stack |
| `MAPS-DRIVE-009` | Parked session | Search-hint text |
| `MAPS-DRIVE-006` | Info | `qhf` routing |
| `MAPS-DRIVE-004` | Info | `trt`-like `i()` returned true |
| `MAPS-DRIVE-010` / `011` | Info | Dex / class API at install |
| `MAPS-DRIVE-020` | Info | Outgoing CarText before gearhead IPC |

Capture for test H:

```bash
adb logcat -c
# open AA + Maps; wait for search bar — do not tap
adb logcat -d | grep -E 'AAKeyboardUnlock|LSPosed-Bridge.*AAKeyboard' > logs/log/maps_drive_$(date +%Y-%m-%dT%H-%M-%S).log
./scripts/triage_log.sh logs/log/maps_drive_*.log
```

### Gearhead external keyboard

| ID | Interpretation |
|----|----------------|
| `xcu.h` | External keyboard start requested |
| `xdb.onStart` / `xdl.d` | IME fragment unlock |
| `gxy.d` / `lgz.a` | SearchTemplate keyboard allowed |

### Must NOT see (post-pivot)

| Pattern | Meaning |
|---------|---------|
| `GH-MAPS-001` / `kcw.k(10)` intercept | Removed Maps keyboard path |
| `GH-KBD-001` / `MAPS-KBD-003` | Removed custom overlay |
| `MAPS-HINT-001` / `GH-HINT-001` | Label rewrite — **out of scope; must not be present in builds** |

---

## Phase 3 — Environment matrix

Run **H1 + H2** on each setup:

| Setup | Notes |
|-------|-------|
| DHU + driving INI | Toggle `driving_status` / speed sensors |
| DHU + stopped sensors INI | Parked-like |
| Wired USB AA | Real head unit |

Record: search bar label text vs `MAPS-DRIVE-008` / `009` lines.

---

## Phase 4 — Known failure modes

1. **Debug off** — only `[MAPS-INSTALL]` appears; no `MAPS-DRIVE-*`.
2. **Wrong Maps process** — scope all processes; compare `MAPS-DRIVE-003 process=`.
3. **Gearhead spoof ≠ Maps label** — parking spoof does not change Maps resource pick; trace Maps-side state.
4. **Stale build** — overlay/hint log IDs mean old APK still installed.

---

## Phase 5 — What to send when asking for help

One log per test (H1 driving, H2 parked), with:

1. Environment (DHU driving on/off).
2. `triage_log.sh` output.
3. Full `MAPS-DRIVE-003` line + last `005`/`007`/`008` or `009`.
4. Search bar label as shown on car screen.
5. APK version from log.

---

## Current engineering priority

1. **Identify decision point** — field or caller that precedes `Resources.getText(voiceOnly)`.
2. **Minimal patch** — once traced, patch only that gate (not overlay/broadcasts).
3. **Regression guard** — keep gearhead external-app unlock unchanged.
