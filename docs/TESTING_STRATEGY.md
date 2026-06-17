# Maps-on-AA Keyboard — Testing Strategy

When fixes “do nothing,” the hooks may be firing while UI lands on the **wrong display** or the **wrong process**. Use this matrix to isolate *which layer* fails before changing more hook logic.

## Current status (2026-06-17)

| Layer | Expected |
|-------|----------|
| `kcw.k(10)` + broadcasts | **Pass** |
| Stock `rek` / `snp` / `xcu` | **Fail** (fallback overlay) |
| `tws-Presentation` overlay | **Pass** — keyboard on car |
| Map visible above keyboard | **Pass** (transparent Presentation) |
| Close keyboard (✕, tap map, re-tap search) | **Pass** |
| Auto-close on AA layout change | **Pass** |
| Voice Plate keyboard icon | **Pass** (`GH-ICON-001`) |
| Submit search from overlay | **Verify** (`MAPS-KBD-001 submitted via …`) |

**Conclusion:** Primary engineering focus shifted from “hooks not installed” to **overlay UX**, **submit path**, and **stock IME** (optional).

---

## Phase 0 — Preflight (5 min)

1. **LSPosed scope:** LSPosed **2.1+** required (libxposed API 101+). Module should **not** show as legacy in Manager.
   - `com.google.android.projection.gearhead`
   - `com.google.android.apps.maps` — **all processes** (including `:car` if listed)

2. Module enabled + **Debug** toggle for verbose `[HOOK]` lines.

3. **Build identity** — log must show your APK path / version.

4. **Capture:**

```bash
adb logcat -c
# … perform ONE action …
adb logcat -d | grep -E 'AAKeyboardUnlock|LSPosed-Bridge.*AAKeyboard' > logs/log/test_$(date +%Y-%m-%dT%H-%M-%S).log
```

5. **Triage:** `./scripts/triage_log.sh logs/log/your_test.log`

---

## Phase 1 — Layer isolation (one action each)

Wait 3 seconds between actions.

| ID | Action | Proves | Pass log IDs |
|----|--------|--------|--------------|
| **A** | Open AA + Maps only | Hooks load | `[MODULE] onModuleLoaded`, `[GH-INSTALL]`, `[MAPS-INSTALL]` |
| **B** | Tap **search** once | Keyboard chain | `kcw.k(10)`, `MAPS-KBD-003 attach target=tws-Presentation` |
| **B2** | Type + **Search** on overlay | Submit path | `MAPS-KBD-001 submitted via rek.e` / `snp.b` |
| **C** | Tap **keyboard icon** (was mic) | Icon rewrite + same path as B | `GH-ICON-001`, then same as B |
| **D** | Tap **map area** above keys | Dismiss | `MAPS-KBD-002 tap outside keyboard` |
| **E** | Tap **search** again while open | Toggle close | `MAPS-KBD-002 keyboard toggle` |
| **F** | Open keyboard, switch AA layout | Auto-dismiss | `CLOSE_MAPS_KEYBOARD`, `configuration changed` / `stopped` |
| **G** | Tap **✕** | Close button | `GH-KBD-002 hide overlay` |

**Decision tree:**

```
A fails → LSPosed scope / module not loaded
B fails, no MAPS-KBD-003 → overlay attach (check display dump)
B passes, keyboard on car but white sheet → Presentation transparency regression
D/E/G fail → KeyboardDismissRoot / hide path regression
F fails → CLOSE broadcast or lifecycle hooks
```

---

## Phase 2 — Event ID checklist

### Search keyboard (overlay path — current default)

| ID | Required? | Interpretation |
|----|-------------|----------------|
| `GH-MAPS-002` PREPARE broadcast | Yes | gearhead → Maps IPC |
| `MAPS-001` OPEN received | Yes | Maps receiver alive |
| `MAPS-KBD-001` showing custom QWERTY | Yes | Fallback reached |
| `MAPS-KBD-003` `tws-Presentation:…/tws:N` | **Yes** | Car-visible attach |
| `MAPS-KBD-005` `800x<H>` | Yes | Panel laid out (H ≈ 200–380) |
| `MAPS-KBD-002` dismiss lines | On close tests | Toggle / tap-outside / nav |

### Search keyboard (stock path — stretch)

| ID | Interpretation |
|----|----------------|
| `MAPS-003 snp.j` / `snp.k` | Stock bind/show |
| `GH-MAPS-000` cached xcu | Projected IME |

### Mic / keyboard icon

| ID | Interpretation |
|----|----------------|
| `GH-ICON-001` | Mic replaced with keyboard icon |
| `GH-ICON-002` | Icon hidden (drawable lookup failed) |
| `GH-MIC-001` / `MAPS-MIC-001` | Real mic tap (header mic — voice) |

### Must NOT see

| ID | Meaning |
|----|---------|
| `car-WindowManager` attach | Crash path |
| `force-started PhoneKeyboardActivity` | Phone flash |
| `kxe.ac type=6` on search tap | Dictation instead of keyboard |
| White full-screen overlay | `TouchCaptureShell` regression |
| Maps ANR after attach | WM on wrong display context |

---

## Phase 3 — Environment matrix

Run phase 1 **B + D + F** on each setup:

| Setup | Notes |
|-------|-------|
| DHU + stopped sensors INI | Desktop car display |
| Wired USB AA | Real head unit |
| Wireless AA | Timing may differ for broadcasts |

Record: map visible above keyboard? dismiss works? `MAPS-KBD-003` display id?

---

## Phase 4 — Known failure modes (don’t re-debug)

1. Overlay on phone `DecorView` only — pre–`tws-Presentation` builds.
2. `car-WindowManager` ANR — never re-enable.
3. Opaque full-screen shell — blocks close + hides map (fixed 2026-06-17).
4. `suppressNextOpen` after toggle — trailing OPEN must not reopen for 800ms.
5. `rek` missing in main Maps process — overlay fallback is expected.
6. SearchTemplate `gig.bi` hardcoded mic — Maps uses Voice Plate; icon hook is on `VoicePlateWidget`.

---

## Phase 5 — What to send when asking for help

One log per test ID (B, D, E, F, G), with:

1. Environment (DHU / USB / wireless).
2. `triage_log.sh` output.
3. `MAPS-KBD-003` and `MAPS-KBD-005` lines.
4. Car screen: map visible? keyboard closable?
5. APK install path from log.

---

## Current engineering priority

1. **Submit path** — `rek.e` / `snp.b` after overlay Search key.
2. **Stock IME** — scope `maps:car`, discover `rek` in correct process.
3. **Real mic** — header mic (`tur.s`) still voice; don't confuse with keyboard icon.
4. **Regression guard** — transparent Presentation + dismiss on every overlay change.

Do not add new attach paths without logging `MAPS-KBD-003` + `MAPS-KBD-005`.
