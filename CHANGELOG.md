# Changelog

All notable changes to this project are documented here.

## [Unreleased]

### Added

- **Custom QWERTY overlay** on car screen via `Presentation` on Maps `tws` virtual display (`ProjectedKeyboardOverlay`)
- Cross-process **`SUBMIT_MAPS_SEARCH`** and **`MapsSearchSubmit`** (rek.e / snp.b / geo intent fallback)
- **`CLOSE_MAPS_KEYBOARD`** broadcast and lifecycle hooks to dismiss overlay on AA layout / navigation change
- **Voice Plate mic icon** replaced with keyboard drawable (`VoicePlateMicIcon` — `GH-ICON-001`)
- Overlay **toggle-close** on search / keyboard-icon re-tap; **tap-outside** dismiss on map area
- Transparent Presentation window so **Maps stays visible** above the keyboard
- Surgical **`kcw.k(10)`** intercept with PREPARE + OPEN broadcasts; mic passthrough via `MAPS_MIC_VOICE` / `MicSignal`
- Display diagnostics (`DisplayDiagnostics`) at overlay attach time

### Changed

- Driving-status sensor spoof now sets the full byte to `0x00` (was `&= ~2` only)
- Maps search Voice Plate hooks (`VoicePlateWidget`, `hjq`, `hjv`) enabled for hints + icon; global `hookVoicePlateAndAssistant` remains disabled so AA voice assistant works elsewhere
- Overlay attach order: Presentation(tws) first; removed `forceStartPhoneKeyboardActivity` (phone flash)
- Removed full-screen opaque `TouchCaptureShell` (blocked close + white background over map)

### Fixed

- Maps car keyboard visible on head unit (wrong display / compositing layer)
- Keyboard could not be closed (touch capture + missing dismiss paths)
- White background hiding Maps view behind overlay
- Trailing `OPEN` broadcast reopening keyboard immediately after toggle-close (`suppressNextOpen` guard)

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
