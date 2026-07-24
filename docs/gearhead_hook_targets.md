# Gearhead Hook Targets

Reverse-engineered originally from AA **17.1.662404-release**. As of **v2.x**, hooks are resolved by **signature discovery + versioned cache**, not by pinning short names to a single AA build.

## Runtime strategy

1. `GearheadSignatureDiscovery` scans dex for stable shapes (sensor `d(int,long,float[],byte[])`, `EditorInfo`+`CarRegionId` IME, `VoiceSessionConfig`, `DemandClientService`, parking enum `CAR_PARKED`, etc.).
2. `DiscoveryCache` persists resolved member descriptors under the hooked app’s private prefs, keyed by `packageName@longVersionCode` + schema version.
3. On AA update → fingerprint miss → one rescan → rewrite cache.
4. Short-name fallbacks remain only as last resort for known shapes (e.g. `lhu.q`, `juv.b`, `xaq.c`, `kxi.F`, `qfy.k`).

Workspace notes: `.cursor/projects/AA_keyboard_unlock/docs/gearhead-17.3-discovery.md`.

## Tested AA versions

| versionName | versionCode | Notes |
|-------------|-------------|-------|
| `17.1.662404-release` | `171662404` | Original hard-coded short-name table |
| `17.3.662814-release` | `173662814` | Discovery + cache (short names largely renamed) |

## Runtime vs jadx class names

Jadx places obfuscated gearhead classes under synthetic `defpackage/`. **At runtime**, R8 exposes them as **unqualified** top-level names. The module tries bare name first, then `defpackage.<name>`.

## How keyboard lock works (phone-side)

Head-unit telemetry arrives as **Car sensor events**. Listeners implement a callback shaped like `d(int, long, float[], byte[])`.

### Driving status byte (sensor type 11)

| Bit | Effect |
|-----|--------|
| **2** | Keyboard locked when set |
| **8** | Config-allowed / UX restriction path |

**Hook:** set driving-status byte to `0x00` (fully unrestricted).

### Speed sensor (type 2)

`float[0]` car speed → spoof to `0`.

### Projection IME

Abstract fragment base with boolean lock field `c` and `d()` UI refresh; service method `c(EditorInfo, CarRegionId)` caches active IME; `h()` starts external keyboard when parked.

### Car App Host keyboard gate

Static `boolean b()` returning “keyboard blocked” (historically `!location.q()`). Template / Compose paths also consult search-hint builders and `isKeyboardAllowed` models.

### Maps search → voice plate

Maps search opens demand space with trigger **10** (`DemandClientService.b` / demand controller `k(int)`), then voice controller `F`/`G`/`VoiceSessionConfig`. Module blocks Maps voice sessions and opens stock projected IME.

## Historical short-name table (AA 17.1 only)

Informative only — **not durable** across AA R8 passes.

| Priority | Class | Method | Action |
|----------|-------|--------|--------|
| **1** | `lhk`, `lhu` | `d(...)` | Spoof speed / driving status |
| **2** | `lht` | `q()` / `s()` / `c()` | Keyboard enabled / no wheel speed / CAR_PARKED |
| **5** | `xdb` / `xdl` / `xdu` | `onStart` / `d` / `k` | Unlock projection IME |
| **6** | `jtg` | `b()` | Car App keyboard not blocked |
| **12a+** | `kxe` / `kcw` | `F`/`G`/`ac` / `k` | Maps voice block + open IME |

Prefer discovery logs: `GH-DRIVE-010` (cache hit/miss/write) and `GH-INSTALL`.
