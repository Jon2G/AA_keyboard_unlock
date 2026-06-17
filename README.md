# AA Keyboard Unlock

LSPosed module that unlocks the Android Auto on-screen keyboard while driving by spoofing stopped/parked vehicle state and bypassing keyboard restriction gates in Android Auto and Google Maps.

**Package:** `com.jon2g.aa_keyboard_unlock`  
**Tested with:** Android Auto `17.1.662404-release`

## What it unlocks

| Scenario | How |
|----------|-----|
| External app text fields (WhatsApp reply, messaging, etc.) | Gearhead projection IME (`xcu` → `xdl`/`xdu`) |
| Google Maps Car search bar | Maps process hooks — label rewrite + keyboard tap routing |
| Car App SearchTemplate hints | Gearhead `jtg` / template keyboard gates |

The **voice assistant** is left enabled in gearhead (no `hookVoicePlateAndAssistant`). **Google Maps** blocks voice dictation in the Maps process (`aoeb.l`, `pub.s`) and opens the projected keyboard instead — see [maps_hook_targets.md](docs/maps_hook_targets.md).

When the toggle is **off**, all hooks are no-ops — stock Android Auto behavior is unchanged.

## Prerequisites

- Rooted Android phone with **Magisk** or **KernelSU** and **LSPosed**
- Android Auto (`com.google.android.projection.gearhead`) installed
- Google Maps (`com.google.android.apps.maps`) — required for Maps search bar fix
- For emulator testing: [Desktop Head Unit (DHU)](docs/setup_DHU.md)

## Install

1. Download the latest APK from [GitHub Releases](https://github.com/Jon2G/AA_keyboard_unlock/releases) (`aa-keyboard-unlock-vX.Y.Z.apk`), or build locally (see below).

2. Install on the phone:
   ```bash
   adb install -r aa-keyboard-unlock-1.0.0.apk
   ```

3. **LSPosed Manager** → Modules → enable **AA Keyboard Unlock** → scope **both**:
   - Android Auto (`com.google.android.projection.gearhead`)
   - Google Maps (`com.google.android.apps.maps`)

4. Open **AA Keyboard Unlock** → enable **Unlock keyboard while active**

5. Force-stop Android Auto and reconnect to your head unit or DHU

## Build from source

```bash
./scripts/build-debug.sh           # build debug APK
./scripts/build-debug.sh --install # build and adb install
```

Output: `src/app/build/outputs/apk/debug/app-debug.apk`

Requires JDK 17 or 21. See [src/README.md](src/README.md) for Gradle details and signed release builds.

## How it works

The module hooks two scoped packages:

**Android Auto (gearhead)** — sensor spoofing and IME unlock ([hook targets](docs/gearhead_hook_targets.md)):

- Zeros the **driving-status** byte (type 11) to `0x00` — fully unrestricted/parked, not just keyboard bit 2
- Zeros **speed** sensor readings (type 2)
- Forces keyboard-enabled flags in `lht` (LocationManager)
- Unlocks projection IME when lockout UI would show
- Rewrites "Voice only while driving" hints in Car App templates

**Google Maps** — search bar constraint bypass ([hook targets](docs/maps_hook_targets.md)):

- Rewrites voice-only hint text to keyboard hint
- Clears `isKeyboardRestricted` / `isMicRestricted` flags on search UI state
- Routes search bar tap to projected keyboard instead of voice-only no-op

```
Phone (gearhead)                    Phone (maps)
  sensor spoof ──► parked state       kur/qha/qhf ──► keyboard tap
  xdl/xdu unlock ──► QWERTY IME       snp.k ──► car input IPC ──► gearhead IME
```

## Testing with DHU

See [docs/DHU_simulate_moving.md](docs/DHU_simulate_moving.md) and run:

```bash
./scripts/dhu_validate.sh
```

Validation checklist: [docs/dhu_validation.md](docs/dhu_validation.md)

## Tested versions

| Component | Version |
|-----------|---------|
| Android Auto | 17.1.662404-release |
| Module | 1.0.0 |
| LSPosed API | 82 |

AA updates may change obfuscated class names. If hooks stop working after an AA update, re-run jadx against the new gearhead APK and update [docs/gearhead_hook_targets.md](docs/gearhead_hook_targets.md).

## Troubleshooting

- **Keyboard still locked after install:** Force-stop Android Auto, verify both packages are scoped in LSPosed, and confirm the module toggle is on.
- **Maps search shows voice-only label or tap does nothing:** Ensure Google Maps is scoped (not just Android Auto).
- **Gearhead UI crashes when opening keyboard:** Do not force all `npz.d/e/f` hooks to false — see [gearhead hook docs](docs/gearhead_hook_targets.md). v1.0.0 uses `jpm` hooks and an `xdm.e()` fallback instead.
- **Debug logging:** Enable **Verbose logging** in the module app; filter logcat with `AAKeyboardUnlock`.

## Safety disclaimer

Disabling driving-distraction protections may be illegal in your jurisdiction and increases crash risk. This module defaults to **disabled**. You are solely responsible for how you use it — only use when safe and legal.

## Releases

Published via GitHub Actions (**Actions** → **Release APK** → enter version → **Run workflow**).

Each release produces a signed `aa-keyboard-unlock-{version}.apk` and a GitHub Release tagged `v{version}`. See [CHANGELOG.md](CHANGELOG.md) for version history.

## Project layout

```
src/app/                LSPosed module (Kotlin)
docs/                   Research + hook target documentation
dhu_config/             DHU sensor INI configs
scripts/                build-debug.sh, dhu_validate.sh
re/                     Local RE artifacts (gitignored)
```

## License

MIT — see [LICENSE](LICENSE)
