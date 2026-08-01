# Changelog

All notable changes to this project are documented here.

## [Unreleased]

## [2.1.5] - 2026-07-31

Fix shape-first discovery regressions on Android Auto **17.4** + Maps **26.31** (Maps car search QWERTY, Gearhead `:projection` stability).

### Fixed

- Gearhead dex scan returned after the first classloader dex (~6803 classes) and missed sensors/demand hooks in secondary APK dex — always merge `classes*.dex` from the host APK zip
- Maps gap-fill preloaded ~48k classes and blocked hook install for 15–90s (or aborted entirely) — shape-validated fallbacks now infer UiState from ranked header taps only
- Maps ranked wrong search headers (`bbms`, `aqfr`, …) after `qok`/`qoq` gap-fill was removed — restore header-field UiState linkage and boost `rlr` keyboard-opener scoring (26.31 `qoq.b`)
- `inferUiStateFromHeaderFields` crashed on XR stub types (`NoClassDefFoundError: com.android.extensions.xr.node.Node`) — guard constructor parameter inspection
- Gearhead `:projection` crash loop (`NoClassDefFoundError: kwv` / PhenotypeContext) — stop probing obfuscated parking enums during early dex scan
- APK dex extraction failed (`Writable dex file … is not allowed`; missing cache dir during `onPackageReady`) — mark extracted dex read-only and fall back to `/data/user/0/<pkg>/files`

### Changed

- **`DiscoveryCache` schema v10–v11** — invalidates stale shape-first caches with wrong Maps headers or empty Gearhead discovery

## [2.1.4] - 2026-07-31

Discovery is **shape-first** — no obfuscated short-name seeds on cache miss.

### Changed

- **Gearhead:** removed `resolveAnchors()` fast-path (`lic`, `qfx`, `qqe`, …); every cache miss runs full dex scan by API shape. Stable FQCNs only (`TouchInputMethodService`, `DemandClientService`, `VoiceSessionConfig`, `CarRegionId`)
- **Maps:** gap-fill fallbacks walk dex for UiState/header/hint shapes — dropped `qok`/`qoq`/`onl` probe lists
- **`DiscoveryCache` schema v8** — invalidates version-specific seed caches

### Fixed

- Shape-first Gearhead scan missed obfuscated targets in secondary dex — enumerate via ClassLoader multidex (same as Maps), not `DexFile(apk)` alone

- **`DiscoveryCache` schema v9** — invalidates empty v8 discovery caches

## [2.1.3] - 2026-07-31

Maps car-search keyboard on AA **17.4** / Maps **26.31**: fix Gearhead→Maps fallback and `rlr` opener discovery.

### Fixed

- Maps ignored `OPEN_MAPS_NATIVE_IME` / `PREPARE_MAPS_NATIVE_IME` broadcasts when GhostActivity lifecycle counters lagged — receiver no longer gated on `hooksActive()`
- Projected IME fallback no longer instantiates detached `xbk` fragments (`factory.d` InvocationTargetException) — uses live IME service fragment (`f()` / discovered factory)
- Maps rek discovery treats **`rlr`** keyboard opener fields (26.31 `qoq.b`) same as rek overlays; stock tap path prefers `qoq` header
- AA **17.4** virtual-device path: rek/header discovery scans all activities and services (not only GhostActivity); broadcast sets projected-keyboard flag for behavioral hooks

## [2.1.2] - 2026-07-31

Bugfix for Android Auto **17.4** + Maps **26.31**: keyboard unlock works again after R8 remaps broke Gearhead discovery and Maps UiState/header anchors.

### Fixed

- External AA apps showing **"Park to use keyboard"** — Gearhead discovery now targets `qqe` sensor callbacks (`lic`/`lhs`), `lib`/`lhi` location/parking, `qfx` demand open, `kxp` voice, and `xba` IME fragments (support-v4 Fragment)
- Maps car search **voice-only / no QWERTY** — UiState discovery via `qok` shape-validated fallback, full-dex string anchors (`UiState(searchQuery=`), and `qoq` header with `rlr` keyboard opener field
- Dex string-anchor scan returning zero UiState candidates when restriction strings live far from type descriptors in secondary dex

### Changed

- **`DiscoveryCache` schema v7** — forces fresh Gearhead/Maps discovery after 17.4/26.31 anchor updates

## [2.1.1] - 2026-07-25

Bugfix for Maps **26.30** on Android Auto **17.3**: starting navigation no longer crashes after unlocking the car keyboard.

### Fixed

- Maps crash when selecting a place to start navigation (`ExceptionInInitializerError` in `bofy`/`bofz`) — voice-bypass discovery no longer matches unrelated `l(int)` methods (e.g. `bofy.l`); only search-header/rek controllers in the car-search graph
- Car-params patching no longer mutates boolean `A`/`c` on navigation/ads/map parameter hubs (`getNavigationCameraParameters`, `getMapAdsParameters`, …)
- Search-header / UiState hooks no longer blanket-clear every `true` constructor/rebuilder bool — only mic/keyboard restriction flags on car-search UiState shapes

### Changed

- **`DiscoveryCache` schema v6** — forces a fresh Maps/Gearhead discovery resolve after the tighter matchers

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
