# Maps Hook Targets (GMM Car)

Reverse-engineered from `com.google.android.apps.maps` base APK pulled from test device on 2026-06-14.

## LSPosed scope

The module declares both packages in `xposed_scope` (recommended apps in LSPosed Manager):

- `com.google.android.projection.gearhead` (Android Auto)
- `com.google.android.apps.maps` (Google Maps — **required** for search keyboard)

Enable for **all Maps processes**, including `:car`.

## Why Maps needs its own hooks

Gearhead hooks unlock the projection IME for **external apps** and keep the **global voice assistant** working. Maps search on AA often **does not** use `qhf.l()` directly:

1. **Voice Plate / IPC path (observed on Fermata + AA 17.x):** Maps `gmm_mic` binder → gearhead `xqd.e` → `qcu` case 10 → `kcw.k(10)` → `qib.p()` + `kxe.F(10)`. **Maps `tur.s` / `lka.z` / `qhf.l` hooks may not run on search tap** — only gearhead-side intercept + cross-process broadcast.
2. **Assistant gRPC path:** gearhead → `NavAssistantCallbacksService` → `aoeb.l()` → voice dictation UI
3. **Header tap path (when used):** `qhf.l/k/i()` → `rek.d()` / `pub.s()` (voice) → `rel` → `snp.j/k` → gearhead car-input IME

Maps-only hooks block voice dictation in the **Maps process** without touching gearhead mic/session hooks.

### Multi-process notes

- LSPosed must scope **all Maps processes**, including `:car` if present.
- Static caches (`lastRel`, `lastMapsCarHost`, `lastSnp`) are per-process; gearhead `OPEN_MAPS_KEYBOARD` / `PREPARE_MAPS_IME` broadcasts target the main Maps process.
- QWERTY requires Maps to run `rel.d` → `snp.j` (bind) → `snp.k` (show) **before** gearhead can use cached `xcu`/`xdb`. Module proactively invokes this chain on prepare broadcast.

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
| **2** | `qhf` | `l()` | **replace**: open projected keyboard; `k`/`i()` mic passthrough |
| **3** | `aoeb` | `l(int)` | **beforeHook**: block voice session; open projected keyboard from cached `rel` |
| **3b** | `aodo` | `m(int)` | **beforeHook**: block assistant client voice start (backup) |
| **3c** | `rzb` | `s()` | **beforeHook**: block `pub.s()` voice shortcut; open keyboard |
| **4** | `rel` | `d()` | **afterHook**: cache instance; `reh.b()` → `snp.k()` show car IME |
| **4b** | `snp` | `j(bisy)` / `k()` | Entry log: car input bind / show IME |
| **5** | `trp`, `tro`, `trn`, `trq`, `trk` | `i()` | **afterHook**: return `false` (driving restriction for header) |
| **6** | `tur` / `tvj` / `llj` | `s()` | **passthrough**: real header mic voice; `MAPS_MIC_VOICE` broadcast |
| **7** | `lka` | `z`/`A` | **passthrough** when `gmm_mic` bundle |
| **8** | Broadcast receiver | `OPEN` / `CLOSE` / `PREPARE` / `SUBMIT` | Overlay lifecycle + submit |
| **9** | `Application` | `ActivityLifecycleCallbacks` + `ComponentCallbacks2` | Hide overlay on car activity stop; hide on app `onConfigurationChanged` |

## Custom overlay (`ProjectedKeyboardOverlay`)

When stock `rek` / `snp` / `dispatchKeyEvent` fail, Maps shows a full QWERTY panel:

**Attach order** (first success):

1. `Presentation` on display name containing `/tws` — **car-visible** (primary)
2. `hostActivity.windowManager` + activity token (invisible on car — fallback only)
3. `decor.overlay` (often `0×0`)
4. `decor.addView` (under GLES surface)

**Never:** `createDisplayContext(carDisplay).getSystemService(WINDOW_SERVICE)` — caused Maps ANR and AA death.

**UI (2026-06-17):**

- Transparent Presentation window; map visible above keyboard
- `KeyboardDismissRoot`: tap empty area above keys → dismiss
- Toggle: re-tap search / keyboard icon while open → close
- ✕ on bottom row → close
- Submit → `ACTION_SUBMIT_MAPS_SEARCH` → `MapsSearchSubmit`

## Keyboard open chain

```
qhf.l/k/i  OR  aoeb.l (blocked)  →  rel.d()  →  reh.a() → snp.j (bind)
                                              →  reh.b() → snp.k (show IME)
                                                         → gearhead xcu/xdl
```

Gearhead search tap (Voice Plate) may bypass Maps `qhf` hooks. Module uses cross-process broadcasts:

- `ACTION_PREPARE_MAPS_IME` — Maps runs `rel.d` → `snp.j/k` **before** gearhead opens `xcu`/`xdb`
- `ACTION_OPEN_MAPS_KEYBOARD` — open overlay / stock fallback when gearhead has no cached projected IME
- `ACTION_CLOSE_MAPS_KEYBOARD` — dismiss overlay on AA layout change, template navigation, or config change
- `ACTION_SUBMIT_MAPS_SEARCH` — custom overlay submitted a query (`EXTRA_QUERY`)

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
