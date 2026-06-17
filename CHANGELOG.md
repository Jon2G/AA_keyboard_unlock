# Changelog

All notable changes to this project are documented here.

## [Unreleased]

## [2.0.0] - 2026-06-17

Major release: native Maps car keyboard path on Android Auto, with silent production builds.

### Added

- Signature-based dex discovery for obfuscated Maps builds (`MapsSignatureDiscovery`) — finds `rek` overlays, header taps, car IME, and restriction gates without hard-coded class names
- Voice-only / keyboard-denied path hooks (`MapsVoiceOnlyPathHooks`, `MapsCarUiStatePatches`) — `qjg`/`qjh`/`qjb` UiState restriction bypass at construction
- `CAR_CANT_USE_KEYBOARD_WHILE_DRIVING` resource tracing (`MAPS-DRIVE-008`) for keyboard-denial diagnosis
- **`scripts/build-release.sh`** — local signed `release` + `logging` APK build helper
- Surgical **`kcw.k(10)`** intercept with `PREPARE_MAPS_NATIVE_IME` / `OPEN_MAPS_NATIVE_IME` broadcasts; mic passthrough via `MAPS_MIC_VOICE` / `MicSignal`

### Changed

- **Release APK is fully silent** — all `ModuleLog` / `MAPS-DRIVE-*` output is compile-time gated (`MODULE_DEBUG=false`). Use the `-log` APK or `assembleDebug` for DHU traces.
- Driving-status sensor spoof sets the full byte to `0x00` (was `&= ~2` only)
- Maps car search targets driving detection at the source (not hint/label rewrites); native `rek` overlay cache and tap path replace overlay-based workarounds
- Design documented in `docs/DESIGN_GOALS.md` — fix detection, not strings

### Fixed

- Maps stuck on **"Voice only while driving"** when dex scanner found zero obfuscated classes (bare-name entries)
- Progression to keyboard UI with **"Can't use keyboard while driving"** when partial hooks applied — `isMicRestricted` / keyboard denial path now hooked
- Gearhead `:projection` process crash when opening keyboard (`xcu.c` NPE path)
- `PREPARE no rek` / missing signature hooks on current Maps builds

## [1.0.0] - 2026-06-14

First public release.

### Added

- LSPosed module scoped to Android Auto (`com.google.android.projection.gearhead`) and Google Maps (`com.google.android.apps.maps`)
- Sensor spoofing and keyboard-enabled flag overrides in gearhead
- Projection IME unlock (`xdb` / `xdl` / `xdu`) for external app text fields
- Maps search bar hint rewrite and keyboard tap routing (`kur`, `qha`, `qhf`, `rel`, `snp`)
- Car App SearchTemplate and Voice Plate placeholder rewriting
- Voice dictation blocking with QWERTY keyboard path preserved
- Settings UI with enable toggle and verbose logging
- GitHub Actions signed release workflow

### Fixed

- Gearhead `:projection` process crash when opening keyboard (`npz` invalid input config → `xdm.e()` null → `xcu.c` NPE)
- Maps "Voice only while driving" label and no-op search tap when restriction flags remained true
