# Changelog

All notable changes to this project are documented here.

## [Unreleased]

## [2.1.0] - 2026-07-24

Android Auto **17.3** + Maps **26.30** keyboard unlock: signature/anchor discovery is the source of truth; short names are discovery probes only and rejected unless API shape matches. Hook layers consume **discovered targets only**.

### Added

- **`DiscoveryCache`** — versioned persistence of resolved Gearhead/Maps hook descriptors keyed by `package@longVersionCode` (schema v5)
- **`GearheadSignatureDiscovery`** — FQCN + shape anchors (`TouchInputMethodService`, `VoiceSessionConfig`, `DemandClientService`, `CAR_PARKED`) with short-name **seeds** validated by API shape; skips full dex walk when anchors complete
- **`MapsSignatureDiscovery`** — multidex ClassLoader scan, string anchors (`isMicRestricted=`), UiState ctor shape, header-tap scoring that prefers controllers holding car-search UiState; shape-validated fallbacks only when scan leaves a gap
- **`MapsCarUiStatePatches`** — clear mic/keyboard restrictions via UiState toString/ctor rebuild (no field-letter hardcoding)
- Maps voice-only path hooks consume **discovered targets only** (no short names in the hook layer)
- In-app updates from GitHub Releases — auto-check when opening settings, manual **Check for updates**, download and install via system package installer
- About section on settings screen — app version, author, GitHub link, MIT license

### Fixed

- Android Auto **17.3** `ClassNotFound` on legacy short names (`xdl`/`kcw`/`kxe`/…) — discovery replaces permanent remaps
- Discovery false positives (`aemn`/`ajjr`) and hung full-dex scan after anchors — anchor-first + early complete
- Maps dex scan missing car-search types in secondary dexes — enumerate ClassLoader `dexElements` (not `DexFile(apk)` primary dex only)
- Maps drive-mode search opening **voice dictation** instead of QWERTY — force rek keyboard open on shape-validated header taps; clear UiState restrictions; driving hint-gate bool forced false
- Maps car-search tap showing caret but **no keyboard** (including when parked) — bind-only `rek.d()` path; skip speculative `reh.b()` overlay show that broke IME
- Maps **"Can't use keyboard while driving"** / voice-only label — resource + hint-gate rewrite
- External AA apps **"Park to use the keyboard"** — parking/location/IME hooks install again after discovery completes
- Voice-only install aborting on missing XR classes (`NoClassDefFoundError`) — per-step isolation so one bad type cannot skip keyboard hooks

### Changed

- Release path stays **silent** (`MODULE_DEBUG=false`); use `-log` / debug APK for LSPosed traces
- Gearhead/Maps short names remain **discovery seed/probe only**; hook install no longer falls back to hardcoded obfuscated class names when discovery misses — log and skip instead
- Unit/`done` return values resolved from the hooked method's return type (singleton static field), not a fixed class name
- Demand voice interrupt / Gearhead context resolution use discovered types + stable Android APIs (`ActivityThread`), not short-name helpers

## [2.0.1] - 2026-06-19

Bugfix release: phone Google Maps no longer crashes when adding a place to a list while the module is enabled.

### Fixed

- **Phone Maps crash (add-to-list)** — `IllegalStateException` on `GmmComposeView` during RecyclerView layout when behavioral hooks ran on phone `MapsActivity` instead of projected car UI only
- Behavioral Maps hooks now gated by **`MapsCarContext`**: active only in the `:car` process or while `GhostActivity` is foreground — **not** merely when Android Auto is connected
- Auxiliary Maps processes (`:primes_lifeboat`, `:server_recovery_process`, etc.) skip hook install entirely
- Tighter dex signature discovery reduces false-positive hooks (car params, search header taps, keyboard-restricted getters)

### Changed

- `scripts/triage_log.sh` flags behavioral hook lines in the main Maps process without projected UI
- `docs/DESIGN_GOALS.md` documents the phone Maps safety invariant

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
