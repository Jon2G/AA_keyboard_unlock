# Maps-on-AA Keyboard — Learnings & Failures

Session logs referenced: `logs/log/modules_2026-06-16T21:02:07.809031.log`,  
`logs/log/verbose_2026-06-16T21:02:07.808684.log`  
Environment: **Android Auto + Google Maps** (USB head unit / DHU), LSPosed module v99999.

Scope: **Maps search keyboard on the car screen**, not Fermata or other AA clients.

See also: [KEYBOARD_DEBUG_POSTMORTEM.md](KEYBOARD_DEBUG_POSTMORTEM.md) (hook-path history), [TESTING_STRATEGY.md](TESTING_STRATEGY.md) (retest matrix).

---

## Current state (as of 2026-06-17)

| Layer | Status |
|-------|--------|
| Search tap routing (`kcw.k(10)` → broadcasts) | **Works** — gearhead intercept fires, Maps receives `PREPARE` / `OPEN` |
| Hint rewrite ("Type anytime") | **Works** |
| Mic vs search separation | **Improved** — `tur.s` / `MAPS_MIC_VOICE`; mic icon opens keyboard by design |
| Stock projected IME (`xcu` / `xdb` / `rek` / `snp`) | **Fails** — no bind, no overlay discovery, `dispatchKeyEvent` not consumed |
| Custom QWERTY overlay — **`Presentation` on `maps/tws`** | **Works** — keyboard visible on car head unit |
| Overlay UX (transparent, dismiss, toggle) | **Implemented** — see [Overlay UI](#overlay-ui-2026-06-17) below |
| Voice Plate mic icon | **Replaced** with gearhead keyboard drawable (`GH-ICON-001`) or hidden (`GH-ICON-002`) |
| Auto-dismiss on AA layout change | **Implemented** — `CLOSE_MAPS_KEYBOARD` + lifecycle hooks |

**User-visible outcome:** Search / keyboard-icon tap shows a QWERTY panel at the bottom of the car screen. Maps remains visible above the keyboard. Stock Maps IME is still not used; typing goes through the custom overlay and `SUBMIT_MAPS_SEARCH`.

---

## What we learned (architecture)

### 1. Multi-process hooks are mandatory

Gearhead handles `kcw.k(10)` in `gearhead:projection`. Maps car UI runs in **`com.google.android.apps.maps`** (main process) with `GhostActivity`. Caches (`gxy`, `xcu`, `rek`, `lastRel`) are **per-PID** — a cache miss in one process does not mean the object does not exist elsewhere.

### 2. Search tap path (observed)

```
Search bar / keyboard-icon tap
  → Maps gmm_mic IPC (bundle)
  → gearhead kcw.k(10)
  → kxe.F(10) voice may start; we block kxe.G
  → PREPARE_MAPS_IME + OPEN_MAPS_KEYBOARD broadcasts to Maps
  → Maps: rek/snp/dispatchKeyEvent (stock) → ProjectedKeyboardOverlay (fallback)
```

Gearhead **cannot** draw on `GhostActivityDisplay` from its own process (no Activity hosts in gearhead PID for that display). Overlay must be **delegated to Maps** (`GH-KBD-001`).

### 3. Two virtual displays — critical

When Maps is projected, logs consistently show **three** displays in the Maps process:

| Display ID (example) | Name | Role |
|----------------------|------|------|
| 0 | Built-in Screen | Phone |
| N (e.g. 11, 25, 44) | `GhostActivityDisplay:…GmmCarProjectionService` | `GhostActivity` window |
| M (e.g. 13, 26) | `com.google.android.apps.maps/tws` | **GLES map surface — what AA mirrors** |

**Confirmed:** Android Auto mirrors the **`tws`** render path to the head unit, not arbitrary views on `GhostActivity` decor or sub-windows on `GhostActivityDisplay`.

- Decor attach: `800×200` measured, **invisible on car**
- Activity `WindowManager` on GhostActivity token: `800×200` measured, **invisible on car**
- ViewOverlay on decor: often **`0×0`**, invisible everywhere
- **`Presentation(hostActivity, twsDisplay)`**: **visible on car** ✓

Car resolution in logs: **800×400** @ density 1.0 (DHU / typical head unit).

### 4. Stock keyboard path is dead in our logs

Every session shows:

- `MAPS-004 PREPARE — no rek overlay`
- `MAPS-004 dispatchKeyEvent(KEYCODE=22) not consumed`
- `GH-MAPS-004 projected IME unavailable — no active xcu/xdb`
- `GH-MAPS-004 kxe.O(qjo) failed: no gearhead input view`

`rek` discovery via `qhf.b` / activity scan fails while only `GhostActivity` is visible in the main Maps process. **`maps:car`** (or other Maps subprocesses) may own `rek` — LSPosed scope should include all Maps processes if we pursue stock UI.

### 5. `GhostActivityDisplay` metrics lie

`createDisplayContext(carDisplay).resources.displayMetrics` can report **bogus** values (e.g. `heightPixels` ~384640 @ 1 dpi). Never use raw display metrics for window height without clamping to a sane range (200–4000 px) or reading `GhostActivity` visible frame.

### 6. Voice Plate is the Maps search UI (not SearchTemplate)

Maps AA search uses **Voice Plate** (800×80 virtual display), not gearhead `SearchTemplate` (`gan` / `gig.bi`). Placeholder and icon flow:

```
VoicePlateWidget (CarIcon, CarText)
  → jpu case 1 → hjq (fuw, fyt icon, CarText)
  → Compose → hjv (fvj, afes, fyh icon, gbg text, transcriptionState, …)
  → gig.Q(hjv) renders search bar on car
```

Mic icon on the search bar is a **`CarIcon`** on `VoicePlateWidget` (arg 1), converted to `fyt`/`fyh` in gearhead. Tapping it routes through the same `kcw.k(10)` path as search — we replace the mic glyph with `ic_keyboard_black_24dp` so the UI matches behavior.

`SearchTemplate` (`gig.bi`) uses a **hardcoded** `gs_mic_vd_theme_24` drawable — not hooked globally (would affect all mic buttons in AA). Voice Plate path is the one that matters for Maps.

---

## Overlay UI (2026-06-17)

### Working attach: `tws-Presentation`

Primary path in `ProjectedKeyboardOverlay.attachViaPresentation()`:

1. Find display whose name contains `/tws`
2. Build QWERTY panel (`buildKeyboardPanel`)
3. Wrap in transparent `KeyboardDismissRoot` (full-screen, keyboard at bottom)
4. `Presentation(hostActivity, twsDisplay)` + transparent window
5. Pass criteria:

```
MAPS-KBD-003 attach target=tws-Presentation:com.google.android.apps.maps/tws:<id>
MAPS-KBD-005 panel measured 800x<H> host=tws-Presentation
```

### Failure: full-screen opaque `TouchCaptureShell` (fixed)

Early `Presentation` builds used a full-screen shell that:

- Called `onTouchEvent` → **always `true`** — swallowed **all** touches (✕, search re-tap, map taps)
- Used default Presentation window background → **white sheet** covering the map
- Made the keyboard **impossible to close** from the car UI

**Fix:** `KeyboardDismissRoot` — transparent background; taps on empty area above keyboard call `dismissFromUserTap()`; keyboard panel keeps focus for key presses. Window: `ColorDrawable(TRANSPARENT)`, `PixelFormat.TRANSLUCENT`, no `FLAG_DIM_BEHIND`.

### Dismiss / toggle behavior

| Action | Behavior |
|--------|----------|
| Tap **map area** above keyboard | Dismiss (`MAPS-KBD-002 tap outside keyboard`) |
| Tap **✕** | Dismiss |
| Tap **search / keyboard icon** while open | Toggle close (`toggleIfVisible`) |
| **AA layout change** / leave Voice Plate | `CLOSE_MAPS_KEYBOARD` broadcast + Maps lifecycle |
| Trailing `OPEN` after toggle-close | Suppressed 800ms (`shouldSuppressNextOpen`) — gearhead sends PREPARE+OPEN |

Attach order (first success wins): **Presentation(tws)** → activity WM → ViewOverlay → decor. Never `createDisplayContext` WM.

### Overlay panel layout

- No "Search Maps" preview row — maximized letter keys (~55% display height, max 380dp)
- Bottom bar: Space | ⌫ | Search | ✕
- Submit: `SUBMIT_MAPS_SEARCH` → `MapsSearchSubmit` (`rek.e` / `snp.b` / geo intent)

---

## Overlay attach failures (chronological)

### A. Gearhead-side overlay (early)

| Attempt | Result |
|---------|--------|
| Overlay in `gearhead:projection` on car display | **Failed** — `GH-DBG-001: no Activity hosts in gearhead process` |
| `Presentation` on GhostActivityDisplay from gearhead | **Failed** — `display can not be found` / `already has a parent` |

**Lesson:** Do not attach Maps keyboard UI from gearhead.

### B. Maps-side `car-WindowManager` (display context) — **CRASH**

Log: `21:09:55` — Maps ANR ~8.6s, AA + Maps died.

**Lesson:** **Never** use `createDisplayContext(virtualDisplay)` + `WindowManager`.

### C. Maps-side `activity-decor` — invisible on car

**Lesson:** Under full-screen `tws` `SurfaceView`.

### D. `PhoneKeyboardActivity` force-start — phone flash

**Lesson:** Removed from fallback chain.

### E. `ViewOverlay` — zero size (`0×0`)

**Lesson:** Weak fallback; needs explicit measure/layout.

### F. Activity `WindowManager` (token-based) — sized but invisible on car

**Lesson:** Wrong compositing layer.

### G. `Presentation` on `maps/tws` — **SUCCESS**

Car-visible keyboard. Requires transparent root (not opaque touch-capture shell).

### H. Opaque `TouchCaptureShell` on Presentation — **UX failure (fixed 2026-06-17)**

White background hid map; could not close keyboard.

---

## Other failures & noise

| Issue | Notes |
|-------|--------|
| Tap storms | User retaps when no keyboard → many `kcw.k(10)`; debounce + toggle reduce churn |
| Duplicate broadcasts | Gearhead PREPARE + OPEN; suppress flag after toggle-close |
| LSPosed scope | Hook main `maps` process; confirm `:car` if pursuing `rek` |
| `Failed to find provider … aa_keyboard_unlock.prefs` | Maps background threads; non-fatal |
| Gradle in agent env | `26.0.1` error — user builds locally |
| `suppressNextOpen` after ✕ | ✕ clears suppress so immediate re-open works; toggle-close sets suppress |

---

## What works (keep)

1. **Surgical `kcw.k(10)`** — allow stock prelude, block `kxe.G`, open keyboard in `afterHookedMethod`.
2. **PREPARE + OPEN + CLOSE broadcasts** — decouple gearhead from Maps overlay lifecycle.
3. **Do not use `kxe.O(jxa)`** for search keyboard (dictation / type-6 transcription).
4. **Mic passthrough** — `tur.s`, `MAPS_MIC_VOICE`, `qib.l` when `inMapsMicFromHeader()`.
5. **`Presentation` on `maps/tws`** — only car-visible attach path validated so far.
6. **Transparent `KeyboardDismissRoot`** — map visible, tap-outside to close.
7. **Voice Plate icon rewrite** — `VoicePlateMicIcon` on `VoicePlateWidget` / `hjq` / `hjv`.
8. **Custom overlay + submit** — `SUBMIT_MAPS_SEARCH` → `rek.e` / `snp.b` / geo intent.
9. **Display diagnostics** — `GH-DBG-001/002` at attach time.
10. **Dismiss on navigation** — `jpf.a` non–Voice Plate template, `onConfigurationChanged`, Maps `onStop` / config change.

---

## What we stopped doing

- `kxe.O(jxa)` / synthetic `kkl` as keyboard opener
- Blocking `tur.s()` for Maps header mic
- Full block of `kcw.k(10)` without running stock `qib.p()`
- Gearhead-side overlay on GhostActivityDisplay
- `createDisplayContext` + `WindowManager` on car virtual display
- `forceStartPhoneKeyboardActivity` (phone flash)
- Full-screen opaque `TouchCaptureShell` that blocks all touches
- `FLAG_LOCAL_FOCUS_MODE` on Presentation window (removed with transparent window fix)
- Global `daf.A` hook to replace mic drawable everywhere in AA

---

## Open hypotheses (next)

1. **Stock `rek` in `maps:car`** — call `rek.d()` instead of custom overlay when discoverable.
2. **SearchTemplate mic** (`gig.bi`) — hardcoded mic icon if Maps ever uses SearchTemplate on some builds.
3. **Stronger gearhead tap debounce** — reduce voice interrupt storms during rapid retaps.
4. **Inline search preview** — optional query preview row (removed for key size; may revisit).
5. **Submit path verification** — confirm `rek.e` / `snp.b` end-to-end on head unit after overlay submit.

---

## Log cheat sheet

### Good routing (search tap)

- `>> kcw.k() trigger=10`
- `GH-MAPS-002 broadcast OPEN_MAPS_KEYBOARD to Maps`
- `MAPS-001 broadcast OPEN_MAPS_KEYBOARD`
- `MAPS-KBD-001 stock keyboard failed — showing custom QWERTY`

### Overlay attach

| Target | Verdict |
|--------|---------|
| `tws-Presentation:…/tws:N` | **Pass** — car-visible |
| `activity-WindowManager:N` | Sized OK, car invisible |
| `activity-overlay:…GhostActivity:N` | Check `MAPS-KBD-005` ≠ `0x0` |
| `activity-decor:…GhostActivity:N` | Under surface — invisible on car |
| `car-WindowManager:N` | **Crash — do not ship** |

### Overlay UX

| ID | Meaning |
|----|---------|
| `MAPS-KBD-002 tap outside keyboard` | Dismiss from transparent area |
| `MAPS-KBD-002 keyboard toggle — closed overlay` | Re-tap while open |
| `MAPS-KBD-002 hide overlay — configuration changed` | AA layout change |
| `GH-ICON-001` | Mic icon → keyboard drawable |
| `GH-ICON-002` | Mic icon hidden (no drawable found) |

### Must not see (on search tap)

- `car-WindowManager` (crash path)
- `force-started PhoneKeyboardActivity`
- `kxe.ac type=6` / `kxe.O(jxa)` on search
- Maps ANR within ~15s of overlay attach
- Full-screen white overlay covering map (regression)

---

## Code map

| File | Role |
|------|------|
| `hooks/GearheadHooks.kt` | `kcw.k(10)`, broadcasts, Voice Plate hints + mic icon, navigation dismiss |
| `hooks/MapsHooks.kt` | OPEN/CLOSE/SUBMIT receivers, `qhf`, overlay open/toggle, lifecycle dismiss |
| `hooks/VoicePlateMicIcon.kt` | Replace/hide Voice Plate mic `CarIcon` / `fyt` / `fyh` |
| `hooks/VoicePlateHints.kt` | "Type anytime" hint rewrite |
| `overlay/ProjectedKeyboardOverlay.kt` | QWERTY UI; Presentation(tws); dismiss/toggle |
| `KeyboardBridge.kt` | PREPARE / OPEN / CLOSE / SUBMIT / MAPS_MIC_VOICE actions |
| `MapsSearchSubmit.kt` | Submit query into Maps |
| `debug/DisplayDiagnostics.kt` | Display / Activity host dumps |

---

## Testing discipline

1. **One tap** per test after install; wait 5s before reading log.
2. Confirm APK hash in log (`Loading legacy module … from /data/app/~~…`).
3. Compare `MAPS-KBD-003` attach target across sessions.
4. Separate **mic retest** from **search retest**.
5. After overlay fix: verify **map visible above keyboard** and **tap-outside dismiss** on car screen.
6. Change AA layout (home, another app, map-only) — keyboard should auto-close.
