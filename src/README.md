# AA Keyboard Unlock

LSPosed module that keeps the Android Auto on-screen keyboard unlocked by spoofing stopped/parked vehicle telemetry inside `com.google.android.projection.gearhead`.

## Prerequisites

- Rooted Android phone with **Magisk/KernelSU** and **LSPosed**
- Android Auto (`com.google.android.projection.gearhead`) installed
- For emulator testing: [Desktop Head Unit (DHU)](docs/setup_DHU.md)

## Install

1. Build or download the APK:
   ```bash
   export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Java 17 or 21 required
   ./gradlew assembleDebug
   ```
   Output: `app/build/outputs/apk/debug/app-debug.apk`

   Or download a release from [GitHub Releases](https://github.com/jon2g/AA_keyboard_unlock/releases) (`aa-keyboard-unlock-vX.Y.Z.apk`).

   Package ID: `com.jon2g.aa_keyboard_unlock`

2. Install on the phone:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **LSPosed Manager** → Modules → enable **AA Keyboard Unlock** → scope **Android Auto**

4. Open **AA Keyboard Unlock** app → enable **Unlock keyboard while active**

5. Force-stop Android Auto and reconnect to your head unit or DHU

## How it works

The module hooks gearhead's car sensor pipeline and location manager (see [docs/gearhead_hook_targets.md](docs/gearhead_hook_targets.md)):

- Clears **keyboard lock bit** in driving-status sensor events (type 11)
- Zeros **speed** sensor readings (type 2)
- Forces keyboard-enabled flags in `lht` (LocationManager)
- Unlocks projection IME when lockout UI would show

When the toggle is **off**, hooks are no-ops — stock AA behavior is unchanged.

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
| Module | 1.0.0 (`com.jon2g.aa_keyboard_unlock`) |
| LSPosed API | 82 |

AA updates may change obfuscated class names (`defpackage.*`). If hooks stop working after an AA update, re-run jadx against the new gearhead APK and update [gearhead_hook_targets.md](docs/gearhead_hook_targets.md).

## Safety disclaimer

Disabling driving-distraction protections may be illegal in your jurisdiction and increases crash risk. This module defaults to **disabled**. You are solely responsible for how you use it — only use when safe and legal.

## Releases

Build and publish from GitHub Actions (**Actions** → **Release APK** → **Run workflow**):

1. Enter the version (e.g. `1.0.0`)
2. Run the workflow

It builds a **signed** `aa-keyboard-unlock-{version}.apk`, uploads it as a workflow artifact, and creates a GitHub Release tagged `v{version}`.

Uses a dedicated release signing key. GitHub repository secrets (already configured for CI):

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `aa-keyboard-unlock.keystore` |
| `ANDROID_SIGNING_PASSWORD` | Keystore + key password |

### Signed release build (local)

Place `aa-keyboard-unlock.keystore` in `src/` (gitignored). Password is in your password manager or `src/signing-credentials.local.txt` if you just generated the key.

```bash
cd src
export ANDROID_SIGNING_PASSWORD='your-password'
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
```

Output: `app/build/outputs/apk/release/app-release.apk` (signed when keystore + password are present; unsigned otherwise).

## Project layout

```
app/                    LSPosed module (Kotlin)
docs/                   Research + hook target documentation
dhu_config/             DHU sensor INI configs
scripts/dhu_validate.sh DHU test helper
re/                     Local RE artifacts (gitignored)
```

## License

MIT — see [LICENSE](LICENSE)
