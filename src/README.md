# AA Keyboard Unlock — Gradle project

Install, usage, and release info: see [../README.md](../README.md).

The Gradle project root is this directory (`src/`).

## Debug build

```bash
cd ..
./scripts/build-debug.sh           # from repo root
./scripts/build-debug.sh --install
```

Or from here:

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## libxposed API 102

The module uses the modern Xposed entry (`META-INF/xposed/java_init.list`, `XposedModule`, API 102). Dependencies:

- `compileOnly("io.github.libxposed:api:102.0.0")` from Maven Central

Hook helpers live in `com.jon2g.aa_keyboard_unlock.xposed` (`HookChains`, `Reflect`, `HookContext`) — no legacy `de.robv.android.xposed` API.

Output: `app/build/outputs/apk/debug/app-debug.apk` (verbose logging on)

## Signed release builds (local)

Place `aa-keyboard-unlock.keystore` in this directory (gitignored). Password via environment variable or `signing-credentials.local.txt`.

```bash
export ANDROID_SIGNING_PASSWORD='your-password'
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleRelease assembleLogging -PversionName=1.0.0 -PversionCode=10000
```

| Variant | APK | `MODULE_DEBUG` |
|---------|-----|----------------|
| `release` | `app/build/outputs/apk/release/app-release.apk` | `false` |
| `logging` | `app/build/outputs/apk/logging/app-logging.apk` | `true` |

Both are signed with the release keystore when configured. Use **logging** for DHU captures (`MAPS-DRIVE-*`); use **release** for everyday use.

## CI release secrets

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `aa-keyboard-unlock.keystore` |
| `ANDROID_SIGNING_PASSWORD` | Keystore + key password |

## License

MIT — see [../LICENSE](../LICENSE)
