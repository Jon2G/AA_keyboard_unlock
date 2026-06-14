# Maps Hook Targets (GMM Car)

Reverse-engineered from `com.google.android.apps.maps` base APK pulled from test device on 2026-06-14.

## LSPosed scope

The module declares both packages in `xposed_scope` (recommended apps in LSPosed Manager):

- `com.google.android.projection.gearhead` (Android Auto)
- `com.google.android.apps.maps` (Google Maps — required for search bar hint fix)

Enable for **all Maps processes**, including `:car`.

## How Maps search hint works

Maps native Car UI (not gearhead VoicePlateWidget) picks the search bar label in `kur.aJ()`:

```java
// z=true + z3=false → R.string.CAR_VOICE_ONLY_WHEN_DRIVING ("Voice only while driving")
// z=false          → R.string.CAR_SEARCH_HINT or route-specific hints
```

Called from `qhf` header view model construction (`qhg.a` → `qmf`).

## Confirmed hook targets

| Priority | Class | Method | Action when module enabled |
|----------|-------|--------|---------------------------|
| **1** | `kur` | `aJ(Context, boolean×5, tqv)` | **beforeHook**: force driving + keyboardBlocked flags false; **afterHook**: rewrite voice-only hint |
| **1b** | `qha` | constructors | **beforeHook**: force `isMicRestricted` + `isKeyboardRestricted` false |
| **1c** | `qhf` | `l()` | **beforeHook**: call `rek.d()` and return (always open projected keyboard on tap) |
| **1d** | `rel` | `d()` | Entry log: keyboard overlay focus |
| **1e** | `snp` | `j(bisy)` / `k()` | Entry log: car input bind / show IME |
| **2** | `trp`, `tro`, `trn`, `trq`, `trk` | `i()` | **afterHook**: return `false` (driving restriction flag for header/reply flows) |

## Related strings (maps `res/values/strings.xml`)

| Resource | Text |
|----------|------|
| `CAR_VOICE_ONLY_WHEN_DRIVING` | Voice only while driving |
| `CAR_CANT_USE_KEYBOARD_WHILE_DRIVING` | Can't use keyboard while driving |
| `CAR_SEARCH_HINT` | Search (keyboard hint) |

## Gearhead vs Maps

| Layer | Package | What it controls |
|-------|---------|------------------|
| Maps search bar label | `com.google.android.apps.maps` | `kur.aJ()` native header UI |
| Voice Plate template host | `com.google.android.projection.gearhead` | `VoicePlateWidget` → `hjq`/`hjv` rendering |

Both are hooked for full coverage.
