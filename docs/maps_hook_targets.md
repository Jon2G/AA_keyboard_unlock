# Maps Hook Targets (GMM Car)

Reverse-engineered from `com.google.android.apps.maps` base APK pulled from test device on 2026-06-14.

**Design principle:** [DESIGN_GOALS.md](DESIGN_GOALS.md) — hook **driving detection** only. **Label/hint rewrite is out of scope and must not be implemented.**

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

## Confirmed hook targets (Maps process)

Patch driving/restriction at the source. Log IDs in parentheses are for debug builds.

| Priority | Class | Method | Action when module enabled |
|----------|-------|--------|----------------------------|
| **1** | `kur` | `aJ(Context,…)` | **beforeHook**: force `driving=false`, clear restriction bools; **afterHook**: rewrite voice-only result only if still leaked |
| **1b** | `qha` | constructors | **beforeHook**: `isMicRestricted`/`isKeyboardRestricted` false; fix `hintString` if ctor still gets voice-only |
| **1c** | `qwt` | constructors | **beforeHook**: `isKeyboardRestricted` / `isConfigRestricted` false |
| **1d** | `qhf` | `l` | **beforeHook**: `rek.d()` keyboard tap; block voice path |
| **2** | `trt` / `trp`…`trk` | `i()` | **afterHook**: force `false` (keyboard not restricted) |
| **2b** | `azob` | `getCarParameters()` | **afterHook**: `csrh.A=true`, `csrh.c=false` (keyboard branch in `qhf.l`) |
| **3** | `rel` / `snp` | `d`, `j`, `k` | Cache + `PREPARE`/`OPEN` native IME bind/show |
| **4** | `aoeb` / `tur` | `l`, `s` | Block voice bypass; prefer `rek.d()` |

**Out of scope (do not add):** `Resources.getString` / `CarText` / `VoicePlateHints` hint substitution, `MAPS-HINT-*` / `GH-HINT-*` layers. Wrong label → fix rows 1–4, not the string.

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
| Maps driving + keyboard | `com.google.android.apps.maps` | `kur.aJ`, `qha`, `trt.i()`, `qhf.l`, `rek`/`snp` — **hook detection, not label-only** |
| External app IME | `com.google.android.projection.gearhead` | Sensor spoof + `xdl`/`gxy`/`jtg` keyboard gates |
| Global voice assistant | `com.google.android.projection.gearhead` | Left enabled (no full voice-assistant kill) |

Maps search keyboard requires **both** scoped packages. See [DESIGN_GOALS.md](DESIGN_GOALS.md).
