# Maps Hook Targets (GMM Car)

Reverse-engineered from `com.google.android.apps.maps` base APK pulled from test device on 2026-06-14.

**Current focus (2026-06-17):** instrumentation-only in the Maps process to trace **in-process driving detection** that selects the search bar label. Custom keyboard overlay, label rewrites, and gearhead Maps keyboard intercepts were removed.

## LSPosed scope

The module declares both packages in `xposed_scope`:

- `com.google.android.projection.gearhead` (Android Auto)
- `com.google.android.apps.maps` (Google Maps — **required** for driving trace)

Enable for **all Maps processes**, including `:car`.

## Why Maps needs its own hooks

Gearhead hooks unlock the projection IME for **external Car apps** and spoof parked sensors. Maps search on AA chooses the search bar label **inside the Maps process** based on driving/distraction state. Observed on recent builds:

| DHU state | Log signature | Search bar label |
|-----------|---------------|------------------|
| Driving | `qha constructed` then `MAPS-DRIVE-008` with `voiceOnly` / `resId=2132018912` | Voice only while driving |
| Parked | `qha constructed` only — no voice-only resource load | Search all destinations |

Gearhead parking spoof (`lht.s`, `lhi CAR_PARKED`) runs but **does not** stop Maps from loading `CAR_VOICE_ONLY_WHEN_DRIVING` when DHU driving sensors are on.

## How Maps search hint works (stock)

Maps native Car UI picks the search bar label in `kur.aJ()` (when present on build):

```java
// z=true + z3=false → R.string.CAR_VOICE_ONLY_WHEN_DRIVING
// z=false          → R.string.CAR_SEARCH_HINT
```

`qha` UiState and `qhf` routing carry distraction flags on some builds. On the tested 2026-06 build, `qha`/`qwt` constructors have **no boolean restriction args**; `kur` static resolvers and `trp`–`trk` `i()` methods are **missing** — trace hooks log what exists instead of patching.

## Confirmed hook targets (Maps process — trace only)

| Priority | Class | Method | Action when module enabled + **Debug** |
|----------|-------|--------|----------------------------------------|
| **1** | `kur` | `aJ(...)` (if found) | **afterHook**: log hint text (`MAPS-DRIVE-001` / `009`) |
| **1b** | `qha` | constructors | **afterHook**: log ctor args (`005`), dump fields (`007`) |
| **1c** | `qwt` | constructors | **afterHook**: log ctor args (`005`), dump fields (`007`) |
| **1d** | `qhf` | `l` / `k` / `i` | **afterHook**: log invocation (`006`) |
| **2** | `trp`, `tro`, `trn`, `trq`, `trk` | `i()` (if present) | **afterHook**: log when returns `true` (`004`) |
| **3** | `Resources` | `getText` / `getString` | **afterHook**: log voice/search res ids (`008`) |
| **4** | `CarText` / `gbh` | create / IPC | **afterHook**: log outgoing hint (`020`) |
| **5** | Install | dex probe | **once**: kur-like classes, driving strings (`010`, `011`) |

No patching, broadcasts, overlay, or `rek`/`aoeb` keyboard routing in MapsHooks.

## Related strings (maps `res/values/strings.xml`)

| Resource | Text |
|----------|------|
| `CAR_VOICE_ONLY_WHEN_DRIVING` | Voice only while driving |
| `CAR_CANT_USE_KEYBOARD_WHILE_DRIVING` | Can't use keyboard while driving |
| `CAR_SEARCH_HINT` | Search (keyboard hint) |

Resolved at install: `voiceOnlyResId` / `searchHintResId` (e.g. `2132018912` for voice-only on test build).

## Debug event IDs (`MAPS-DRIVE-*`)

**Requires module Debug toggle.** Single always-on line: `[MAPS-INSTALL]`.

| ID | Meaning |
|----|---------|
| `MAPS-DRIVE-001` | Voice-only hint text observed (kur / CarText path) |
| `MAPS-DRIVE-003` | Per-process install audit (hook counts, res ids, kur/qha/qwt/qhf/trt status) |
| `MAPS-DRIVE-004` | `trt`-like `i()` returned `true` |
| `MAPS-DRIVE-005` | `qha` / `qwt` constructed — ctor arg summary |
| `MAPS-DRIVE-006` | `qhf.l/k/i` invoked |
| `MAPS-DRIVE-007` | Instance field dump after `qha` / `qwt` construct |
| `MAPS-DRIVE-008` | `Resources` access for voice-only or search-hint res id + stack |
| `MAPS-DRIVE-009` | Search-hint text observed |
| `MAPS-DRIVE-010` | Install-time dex probe (driving strings, kur-like classes) |
| `MAPS-DRIVE-011` | Class API dump at install (constructors / methods) |
| `MAPS-DRIVE-020` | Outgoing `CarText` / `gbh.a` hint before IPC |

Triage: `./scripts/triage_log.sh your.log` — section **Maps driving instrumentation**.

## Gearhead vs Maps

| Layer | Package | What it controls |
|-------|---------|------------------|
| Maps driving label | `com.google.android.apps.maps` | `qha`, `Resources`, `kur` (build-dependent) — **trace only** |
| External app IME | `com.google.android.projection.gearhead` | Sensor spoof + `xdl`/`gxy` keyboard gates |
| Global voice assistant | `com.google.android.projection.gearhead` | Left enabled (no `hookVoicePlateAndAssistant`) |

Maps search keyboard on AA is **not** modified by this pivot; use gearhead stock path when parked (`xcu.h`, `xdb.onStart`).
