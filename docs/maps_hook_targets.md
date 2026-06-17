# Maps Hook Targets (GMM Car)

Reverse-engineered from `com.google.android.apps.maps` base APK pulled from test device on 2026-06-14.

## LSPosed scope

The module declares both packages in `xposed_scope` (recommended apps in LSPosed Manager):

- `com.google.android.projection.gearhead` (Android Auto)
- `com.google.android.apps.maps` (Google Maps — **required** for search keyboard)

Enable for **all Maps processes**, including `:car`.

## Why Maps needs its own hooks

Gearhead hooks unlock the projection IME for **external apps** and keep the **global voice assistant** working. Maps search on AA often **does not** use `qhf.l()` directly:

1. **Assistant gRPC path (common on AA):** gearhead → `NavAssistantCallbacksService.requestAssistantSession` → `aocq.a()` → `aoeb.l()` → voice dictation UI
2. **Header tap path:** `qhf.l/k/i()` → `rek.d()` / `pub.s()` (voice) → `rel` → `snp.j/k` → gearhead car-input IME

Maps-only hooks block voice dictation in the **Maps process** without touching gearhead mic/session hooks.

## How Maps search hint works

Maps native Car UI picks the search bar label in `kur.aJ()`:

```java
// z=true + z3=false → R.string.CAR_VOICE_ONLY_WHEN_DRIVING
// z=false          → R.string.CAR_SEARCH_HINT
```

`qha` UiState carries `isMicRestricted` / `isKeyboardRestricted` used by `qhf.l()` routing.

## Confirmed hook targets (Maps process only)

| Priority | Class | Method | Action when module enabled |
|----------|-------|--------|---------------------------|
| **1** | `kur` | `aJ(...)` | **beforeHook**: force driving + keyboardBlocked false; **afterHook**: rewrite voice-only hint |
| **1b** | `qha` | constructors | **beforeHook**: force `isMicRestricted` + `isKeyboardRestricted` false |
| **1c** | `qwt` | constructors | **beforeHook**: force distraction flags false (`isKeyboardRestricted`, `isConfigRestricted`) |
| **2** | `qhf` | `l/k/i()` | **beforeHook**: `rek.d()` + `reh.b()` (keyboard); skip voice (`pub.s`) |
| **3** | `aoeb` | `l(int)` | **beforeHook**: block voice session; open projected keyboard from cached `rel` |
| **3b** | `aodo` | `m(int)` | **beforeHook**: block assistant client voice start (backup) |
| **3c** | `rzb` | `s()` | **beforeHook**: block `pub.s()` voice shortcut; open keyboard |
| **4** | `rel` | `d()` | **afterHook**: cache instance; `reh.b()` → `snp.k()` show car IME |
| **4b** | `snp` | `j(bisy)` / `k()` | Entry log: car input bind / show IME |
| **5** | `trp`, `tro`, `trn`, `trq`, `trk` | `i()` | **afterHook**: return `false` (driving restriction for header) |

## Keyboard open chain

```
qhf.l/k/i  OR  aoeb.l (blocked)  →  rel.d()  →  reh.a() → snp.j (bind)
                                              →  reh.b() → snp.k (show IME)
                                                         → gearhead xcu/xdl
```

## Related strings (maps `res/values/strings.xml`)

| Resource | Text |
|----------|------|
| `CAR_VOICE_ONLY_WHEN_DRIVING` | Voice only while driving |
| `CAR_CANT_USE_KEYBOARD_WHILE_DRIVING` | Can't use keyboard while driving |
| `CAR_SEARCH_HINT` | Search (keyboard hint) |

## Gearhead vs Maps

| Layer | Package | What it controls |
|-------|---------|------------------|
| Maps search + assistant gRPC | `com.google.android.apps.maps` | `aoeb`, `qhf`, `rel`, `snp` |
| Global voice assistant | `com.google.android.projection.gearhead` | Left enabled (no `hookVoicePlateAndAssistant`) |
| External app IME | `com.google.android.projection.gearhead` | Sensor spoof + `xdl` unlock |
