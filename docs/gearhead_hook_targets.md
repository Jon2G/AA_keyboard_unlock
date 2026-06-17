# Gearhead Hook Targets (AA 17.1.662404-release)

Reverse-engineered from `com.google.android.projection.gearhead` base APK pulled from test device on 2026-06-13.

## Tested AA version

| Field | Value |
|-------|-------|
| Package | `com.google.android.projection.gearhead` |
| versionName | `17.1.662404-release` |
| versionCode | `171662404` |
| minSdk | 32 |

## Runtime vs jadx class names

Jadx decompilation places obfuscated gearhead classes under a synthetic `defpackage/` folder (e.g. `defpackage/qso.java`). **At runtime**, R8 exposes these as **unqualified top-level class names** in the default package: `qso`, `lht`, `lhi`, `xdb`, `lhk`.

The module resolves classes by trying the bare name first, then `defpackage.<name>` as a fallback. Hooking with only `defpackage.qso` fails with `ClassNotFoundException` on AA 17.x.

## How keyboard lock works (phone-side)

Head-unit telemetry arrives as **Car sensor events** over the projection protocol. Gearhead registers listeners on `defpackage.qso` (sensor callback interface).

### Driving status byte (sensor type 11)

Sensor type **11** = `SENSOR_DRIVING_STATUS_DATA` (`xnv.SENSOR_DRIVING_STATUS_DATA`).

| Bit | Effect in gearhead |
|-----|-------------------|
| **2** | When **set**, keyboard is **locked** (`xdb.c = true`) and parking/keyboard-enable flag `lhl.c` is **false** |
| **8** | Toggles `lhl.d` (config-allowed / UX restriction path) |
| Other bits | Additional UX restrictions (e.g. no text, no setup) consumed by Maps and Car App hosts |

AA's own debug flag `ContentBrowse__keyboard_force_disabled` (`acpw.e()`) ORs bit 2 into driving status in `AppDecorService` — confirming bit 2 is the keyboard lock bit.

**Hook behavior (v1.0+):** the module sets the entire driving-status byte to `0x00` (fully unrestricted / parked), not only `&= ~2`. Clearing bit 2 alone left other restriction bits set; Maps treats any restriction as driving and hides the keyboard.

### Speed sensor (type 2)

Sensor type **2** = car speed in m/s (`float[0]`). Feeds `lhi.g()` and `lhu` wheel-speed tracking (`lht.s()`).

### Projection IME lock UI

`defpackage.xdb` (base class for `xdl` projection keyboard fragment):

- Field `c`: `true` = keyboard **locked** (lockout label visible)
- Method `d()`: toggles keyboard vs lockout visibility
- Registers `lhk(this, 6)` as sensor listener; on type 11 sets `c = (bArr[0] & 2) != 0`

### Car App Host keyboard gate

`defpackage.jtg.b` — keyboard allowed for embedded Car App templates (search, sign-in).

- Static `jtg.b()` returns `!kzr.d().q()` — when `true`, templates show **"Voice only while driving"** (`R.string.search_hint_transcription`) instead of the keyboard
- Initialized from `kzr.d().q()` → `lht.q()` → `lhl.c`
- `gdiVar.x().b` (instance field, default `true`) gates `showKeyboardByDefault` on SearchTemplate / SignInTemplate
- Material search builds `gan` with `voiceOnlyEnabled=true` when `gxz.isKeyboardAllowed` is false

Sensor hooks can lag behind template init; hook `jtg.b()` and the template/model classes directly to avoid the voice-only race.

### CarUiInfo constraint path (`npz` / `jpm`)

Separate from sensor spoofing (`lht.q()` / `lhl.c`):

- `jpm.a()` → `npz.e()` → `CarUiInfo.b` (touchscreen)
- `jpm.b()` → `npz.f()` → `CarUiInfo.d` (touchpad navigation)
- When `jpm.a() || jpm.b()` is true and keyboard is blocked, `gxy.d()` / `jyn` pick `R.string.search_hint_transcription`

**Do not hook `npz.d/e/f` to always return false.** `xdm.e()` uses those flags to pick `xdl` vs `xdu`; if all three are false it returns null and `xcu.c()` NPE-crashes the `:projection` process. Hook `jpm.a/b` instead for hint rewriting.

Maps search bar label is additionally controlled in the **Maps app** via `kur.aJ()` — see [maps_hook_targets.md](maps_hook_targets.md).

### Maps search uses Voice Plate (not SearchTemplate)

Maps AA search bar does **not** go through `jyn`/`jys`/`gxy` SearchTemplate hooks. Logs show:

- Virtual display: `VoicePlateCarService` + `GmmCarProjectionService` (800×80)
- Tap triggers `kxe.ac()` → `startVoiceSearchMainThread trigger=10`
- Placeholder text arrives via `VoicePlateWidget` → `hjq` / `hjv` Compose models

Session id for voice plate Car App host: `voice_plate` (`gir.u()`).

### Notification reply uses assistant voice dictation

Notification **Reply** action (`ACTION_REPLY` in `qki`/`ltr`) calls:

- `kcw.l().t(messagingInfo)` → `kxe.aa(messagingInfo, 3)` → voice session type **direct reply**
- Opens `TouchInputMethod` virtual display (`xdm` → `xdl` or `xdu`)

This bypasses Car App SearchTemplate hooks. IME unlock requires both `xdl.d` and `xdu.d`.

## Confirmed hook targets

| Priority | Class (jadx / runtime) | Method | Action when module enabled |
|----------|------------------------|--------|---------------------------|
| **1** | `lhk`, `lhu` | `d(...)` | **beforeHook**: type 2 → `fArr[0]=0`; type 11 → `bArr[0]=0` (full unrestricted byte; concrete `qso` implementors) |
| **1b** | ~~`qso`~~ | `d(...)` | Interface only — hook concrete classes above |
| **2** | `lht` | `q()` | **afterHook**: return `true` (keyboard enabled / parked) |
| **2b** | `lht` | `c()` | **afterHook**: return `lha.b` (CAR_PARKED; unblocks `xcu.h()` external keyboard) |
| **2c** | `lhi` | `d()` | **afterHook**: return `lha.b` (CAR_PARKED backup from speed provider) |
| **2d** | `kxk` | `a()` | **afterHook**: return `true` (messaging keyboard path enabled) |
| **2e** | `jpm` | `a/b()` | **afterHook**: return `false` (used by `gxy`/`jyn` hint selection; safe vs forcing `npz`) |
| **3** | `lht` | `s()` | **afterHook**: return `false` (wheel speed never non-zero) |
| **4** | `lhi` | `f()` | **afterHook**: return `0.0f` (reported speed) |
| **5** | `xdb` | `onStart()` | **afterHook**: set `c = false`, call `d()` so initial lockout cannot stick |
| **5b** | `xdl`, `xdu` | `d()` | **beforeHook**: always set field `c = false` before UI refresh |
| **5c** | `xdu` | `k()` | **afterHook**: return `false` (bypass rotary/HWR lockout branch) |
| **5d** | `xdm` | `e()` | **afterHook**: if null, return new `xdl` (prevents `xcu.c` NPE on invalid input config) |
| **6** | `jtg` | `b()` | **afterHook**: return `false` (Car App Host keyboard-not-blocked; avoids "Voice only while driving" at template init) |
| **7** | `jyn`, `jys`, `jyu` | `b(boolean)` / `d(boolean)` | **beforeHook**: force `true` (show keyboard instead of voice-only hint) |
| **7b** | `jyn`, `jys` | `p(boolean)` | **beforeHook**: force `true` (set hint to keyboard text, not "Voice only while driving") |
| **8** | `gxy` | `d(gyb, boolean, hgx)` | **beforeHook**: `isKeyboardAllowed = true` |
| **9** | `gxz` | constructor | **beforeHook**: `isKeyboardAllowed = true` |
| **10** | `gan` | constructor | **beforeHook**: `voiceOnlyEnabled = false`, `showKeyboardByDefault = true` |
| **11** | `lgz` | `a(boolean)` | **beforeHook**: force keyboard-enabled notifications to `true` |
| **12** | `jtg` | constructor | **afterHook**: force `jtg.g` state to `true`, dispatch refresh event 6 |
| ~~**12–17**~~ | Voice Plate / assistant (`kxe`, `kwt`, full `hookVoicePlateAndAssistant`) | — | **Partial** — global assistant hooks stay disabled; **Maps search** hooks Voice Plate hints + mic icon + `hjv` transcription state |
| **12a** | `VoicePlateWidget` | `<init>`, `getIcon`, `getPlaceholderText` | Rewrite placeholder; replace mic `CarIcon` with keyboard icon |
| **12b** | `hjq` | constructor | Placeholder rewrite; replace `fyt` icon |
| **12c** | `hjv` | constructor | Force transcriptionState INACTIVE (1); rewrite voice-only text; replace `fyh` icon |
| **12d** | `jpf` | `a(ComponentName, SessionInfo, TemplateWrapper)` | When Maps template ≠ VoicePlateWidget → `CLOSE_MAPS_KEYBOARD` broadcast |
| **12e** | `Activity` | `onConfigurationChanged` | Broadcast `CLOSE_MAPS_KEYBOARD` (AA layout change) |
| **13** | `kcw` | `k(kvl, int)` | Maps search: surgical intercept, PREPARE/OPEN broadcasts, keyboard in `afterHookedMethod` |
| **17c** | `xcu` | `h()` | Entry log for external keyboard start |

### Voice Plate mic icon (`VoicePlateMicIcon`)

The search bar trailing icon is a mic in stock AA but opens the keyboard when our `kcw.k(10)` intercept runs. `VoicePlateMicIcon` replaces it at three layers:

| Layer | Class | Field | Drawable |
|-------|-------|-------|----------|
| Car App model | `VoicePlateWidget` | `CarIcon` (arg 1) | `CarIcon.createWithResource` → `ic_keyboard_black_24dp` |
| Internal | `hjq` | `fyt` (arg 1) | `new fyh(drawable, null, null)` |
| Compose UI | `hjv` | `fyh` (arg 2) | same |

If no gearhead drawable resolves: icon slot set to `null` (hidden). Logs: `GH-ICON-001` (replaced), `GH-ICON-002` (hidden).

**Not hooked:** `gig.bi` SearchTemplate mic (hardcoded `gs_mic_vd_theme_24`) — Maps AA search uses Voice Plate, not SearchTemplate.

### Cross-process keyboard broadcasts (`KeyboardBridge`)

| Action | Direction | Purpose |
|--------|-----------|---------|
| `PREPARE_MAPS_IME` | gearhead → Maps | `rel.d` / `snp.j` bind before stock IME |
| `OPEN_MAPS_KEYBOARD` | gearhead → Maps | Open overlay / stock fallback |
| `CLOSE_MAPS_KEYBOARD` | gearhead → Maps | Dismiss overlay on layout / template change |
| `SUBMIT_MAPS_SEARCH` | overlay → Maps | Query from custom QWERTY |
| `MAPS_MIC_VOICE` | Maps → gearhead | Allow real mic through `kcw.k(10)` window |

### Sensor callback multiplexer (`lhk`)

`defpackage.lhk` implements `qso` and dispatches by synthetic switch index to:

- `lhl` (case 0) — driving status → parking/keyboard enable flags
- `xdb` (case 6) — projection keyboard lock
- `AppDecorService` (case 5) — system UI driving status
- `lhi` (case 3) — speed updates
- `aecg` (case 2) — gear sensor

Hooking `qso.d` at the interface level covers all implementors including `lhk`, `lhu`, and standalone handlers.

## DHU correlation

From [DHU_simulate_moving.md](DHU_simulate_moving.md):

| DHU command | Expected sensor effect |
|-------------|------------------------|
| `speed 10` | type 2, `fArr[0] ≈ 10` |
| `speed 0` | type 2, `fArr[0] = 0` |
| `gear 100` (Drive) | contributes to driving status restrictions |
| `gear 101` (Park) | relaxed driving status |
| `parking_brake true` | parking brake sensor (type 7) |
| `restrict keyboard` | forces keyboard restriction via DHU |

With module enabled, hooks spoof **stopped/parked** regardless of DHU state.

## Log tags (baseline capture)

Captured from device logcat (`re/aa_logcat_snapshot.log`):

- `com.google.android.projection.gearhead` — HUN virtual display lifecycle
- `GH.InputMethodFragment` — IME fragment (`xdb`)
- `GH.UnlimitedPrvdr` — driving status provider (`lhu`)
- `GH.LocationManager` — location/sensor manager (`lht`)
- `CarApp.H.Constraints` — keyboard enablement dispatch (`jtg`)
- `ADU.AppDecorService` — driving status override path

## References

- Prior art: [SAAX](https://gitlab.com/agentdr8/saax) sensor spoofing (`bArr[0]=0` for type 11, `fArr[0]=0` for type 2) — method was `a()` in AA 6.x, renamed to `d()` in AA 17.x
- Internal RE output: `re/gearhead_src/` (gitignored, regenerate with jadx)
