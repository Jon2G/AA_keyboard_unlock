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

## Signed release build (local)

Place `aa-keyboard-unlock.keystore` in this directory (gitignored). Password via environment variable or `signing-credentials.local.txt`.

```bash
export ANDROID_SIGNING_PASSWORD='your-password'
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleRelease -PversionName=1.0.0 -PversionCode=10000
```

Output: `app/build/outputs/apk/release/app-release.apk`

## CI release secrets

| Secret | Description |
|--------|-------------|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded `aa-keyboard-unlock.keystore` |
| `ANDROID_SIGNING_PASSWORD` | Keystore + key password |

## License

MIT — see [../LICENSE](../LICENSE)
