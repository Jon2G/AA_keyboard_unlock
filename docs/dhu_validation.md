# DHU Validation — AA Keyboard Unlock

Test matrix from the implementation plan. Run after LSPosed module is enabled and scoped to `com.google.android.projection.gearhead`.

## Environment (2026-06-13)

| Item | Value |
|------|-------|
| Module APK | `app/build/outputs/apk/debug/app-debug.apk` (v1.0.0) |
| Target AA | 17.1.662404-release |
| DHU config | `dhu_config/config_stopped_sensors.ini` |
| Validation script | `scripts/dhu_validate.sh` |

## Automated checks (completed)

- [x] Gearhead APK pulled and decompiled (AA 17.1.662404)
- [x] Hook targets documented in [gearhead_hook_targets.md](gearhead_hook_targets.md)
- [x] Module APK builds successfully (Java 21 + AGP 8.7.3)
- [x] APK installs on test device (`57041FDCQ0088C`)
- [x] Settings activity launches (`com.jon2g.aa_keyboard_unlock/.SettingsActivity`)

## Manual DHU test matrix (requires LSPosed)

| Step | DHU state | Module | Expected | Result |
|------|-----------|--------|----------|--------|
| 1 | Stopped (`restrict none`, speed 0, park) | OFF | Keyboard works | _Pending_ |
| 2 | Moving (`speed 10`, drive, brake off) | OFF | Keyboard locked | _Pending_ |
| 3 | Moving | ON | **Keyboard unlocked** | _Pending_ |
| 4 | Stopped | ON | Keyboard works | _Pending_ |
| 5 | Toggle OFF while moving | OFF | Lock returns | _Pending_ |

### Procedure

1. Enable **AA Keyboard Unlock** in LSPosed Manager → scope **Android Auto** and **Google Maps**
2. Open the module app → enable **Unlock keyboard while active**
3. Optional: enable **Verbose logging**
4. Force-stop Android Auto, reconnect DHU
5. Run `./scripts/dhu_validate.sh` for adb forward + log capture commands
6. In DHU terminal, run lock/unlock sequences from [DHU_simulate_moving.md](DHU_simulate_moving.md)

### Expected LSPosed log (module ON, debug enabled)

```
[AAKeyboardUnlock] Loading hooks in com.google.android.projection.gearhead (enabled=true)
[AAKeyboardUnlock] Hooks installed for com.google.android.projection.gearhead
[AAKeyboardUnlock] Spoofing car speed 10.0 -> 0
[AAKeyboardUnlock] Spoofing driving status 0x2 -> 0x0
```

## Blocker

Test device did not have LSPosed/Magisk packages installed during automated run. Hook behavior must be confirmed on a rooted device with LSPosed enabled.
