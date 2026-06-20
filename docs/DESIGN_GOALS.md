# Design goals — AA Keyboard Unlock

## Phone Maps safety (invariant)

**Phone Maps (`MapsActivity`) must behave identically to stock — whether or not Android Auto / gearhead is connected.**

Behavioral hooks in the Maps process apply only when projected car UI is active:

- **`GhostActivity` is foreground** in the main Maps process (`MapsCarContext.ghostActivityCount > 0`), or
- The hook runs in the **`:car`** process.

Hooks must **not** run on phone UI (`MapsActivity`, lists, place details, add-to-list) even when AA remains connected in the background. Gating on gearhead connection alone is insufficient.

Implementation: [`MapsCarContext.kt`](../src/app/src/main/java/com/jon2g/aa_keyboard_unlock/hooks/MapsCarContext.kt) — `shouldApplyBehavioralHooks()`.

## Primary goal: hook driving detection

The module must **defeat in-process driving / distraction detection** so Google Maps and Android Auto choose the **stock keyboard path** on their own.

| In scope | Out of scope |
|----------|--------------|
| Spoof or clear flags that mean “driving” / “keyboard restricted” | **Label / hint text rewrite** (see below) |
| Force `kur.aJ(…)` inputs so `driving=false` and restrictions are off | Custom overlay keyboard for Maps search |
| Clear `qha` `isKeyboardRestricted` / `isMicRestricted` at construction | Opening gearhead `xdl` shell without Maps `rek`/`snp` bind |
| Hook `trt.i()` and related distraction sources | Cosmetic fixes that hide symptoms without fixing detection |
| Patch `azob.getCarParameters()` for `qhf.l()` routing | |
| Gearhead sensor spoof + `jtg` keyboard gates | |

## Out of scope: label rewrite (do not implement)

**Search bar hint / label rewrite is explicitly out of scope and must not be added or restored.**

This includes, but is not limited to:

- `Resources.getString` / `getText` hooks that swap voice-only strings
- `VoicePlateHints`, `CarText.create`, or template placeholder rewriting
- Rewriting `qha` `hintString` constructor args after the fact
- `kur.aJ` **afterHook** that replaces the returned hint string
- Gearhead `GH-HINT-*` / Maps `MAPS-HINT-*` string substitution layers

Rationale:

1. **“Voice only while driving” is a diagnostic** — it proves driving-detection hooks failed or did not install. Hiding it delays fixing the real bug.
2. **Label-only fixes break tap routing** — `qhf.l()` and `rek`/`snp` stay on the voice path even if the bar shows “Search”.
3. **Product goal** is native projected QWERTY via spoofed detection, not a reskinned voice-only UI.

If the label is wrong, fix `kur.aJ` args, `qha`/`qwt` flags, `trt.i()`, `csrh`, and `qhf.l` — **never** patch the string.

## Why the search bar label matters

Stock Maps sets “Voice only while driving” when `kur.aJ()` sees `driving=true` (and related flags). Success means the bar shows the normal search hint **because detection was spoofed**, not because we intercepted `getString`.

## Target behavior (Maps search on AA)

Native stock projected QWERTY via Maps car IME:

```
search tap → qhf.l() or equivalent → rek.d() → reh.b() / snp.j+k → gearhead IME with keys
```

## Hook layers (priority order)

### Maps process

1. **`kur.aJ(Context, driving, …)`** — **beforeHook only**: force `driving=false`, clear restriction bools.
2. **`qha` constructors** — `isMicRestricted` / `isKeyboardRestricted` false (do not rewrite `hintString`).
3. **`qwt` DistractionState** — unrestricted keyboard/config flags.
4. **`trt.i()`** (and `trp`…`trk`) — must not report keyboard restricted.
5. **`azob.getCarParameters()` → `csrh`** — keyboard-allowed flags for `qhf.l()`.
6. **`qhf.l()`** — route search tap to `rek.d()`; block voice bypass when appropriate.
7. **`rel` / `snp` cache** — `PREPARE` / `OPEN` → `rek.d()` + `reh.b()` or `snp.j`/`k`.

### Gearhead (`:projection`)

1. Sensor spoof (speed, driving-status byte, parked).
2. **`jtg.b()`**, **`jyn`/`jys` template gates** — keyboard allowed at template build (flags, not hint strings).
3. **`kcw.k(10)`** — block Maps search voice; broadcast Maps native IME prep/open.

## Success criteria

| Check | Pass |
|-------|------|
| Search bar hint (stock) | “Search” / “Search all destinations” from real `kur.aJ` args |
| Tap | `rek.d()` / `MAPS-003 snp.k` in log |
| Head unit | QWERTY keys visible |
| Logs | `hook kur.aJ` or kur signature hook installed on main Maps process |
| Logs | **No** `MAPS-HINT-001` / `GH-HINT-001` (those hooks must not exist) |

## References

- [maps_hook_targets.md](maps_hook_targets.md)
- [gearhead_hook_targets.md](gearhead_hook_targets.md)
- [KEYBOARD_DEBUG_POSTMORTEM.md](KEYBOARD_DEBUG_POSTMORTEM.md)
