# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Changed

- **Release APK is fully silent** — all `ModuleLog` / `MAPS-DRIVE-*` output is compile-time gated (`MODULE_DEBUG=false`). Use the `-log` APK or `assembleDebug` for DHU traces.
- Driving-status sensor spoof now sets the full byte to `0x00` (was `&= ~2` only)
- Maps car search: signature-based dex discovery, `qjg`/`qjb` UiState restriction bypass, native `rek` overlay path (no custom keyboard overlay)
- Surgical **`kcw.k(10)`** intercept with PREPARE + OPEN broadcasts; mic passthrough via `MAPS_MIC_VOICE` / `MicSignal`

### Fixed

- Maps "Voice only while driving" / keyboard-denied labels when driving-detection hooks were missing or incomplete
- Gearhead `:projection` process crash when opening keyboard (`xcu.c` NPE path)

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
