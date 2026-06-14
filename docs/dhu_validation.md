# DHU Validation — AA Keyboard Unlock

Test matrix for the LSPosed module. Run after the module is enabled and scoped to **Android Auto** and **Google Maps**.

## Environment

| Item | Value |
|------|-------|
| Module APK | `src/app/build/outputs/apk/debug/app-debug.apk` (v1.0.0) |
| Target AA | 17.1.662404-release |
| DHU config | `dhu_config/config_stopped_sensors.ini` |
| Validation script | `scripts/dhu_validate.sh` |
| Validated | 2026-06-14 on rooted device with LSPosed (real head unit + DHU) |

## Automated checks (completed)

- [x] Gearhead APK pulled and decompiled (AA 17.1.662404)
- [x] Hook targets documented in [gearhead_hook_targets.md](gearhead_hook_targets.md)
- [x] Maps hook targets documented in [maps_hook_targets.md](maps_hook_targets.md)
- [x] Module APK builds successfully (Java 21 + AGP 8.7.3)
- [x] APK installs on test device
- [x] Settings activity launches (`com.jon2g.aa_keyboard_unlock/.SettingsActivity`)

## Manual test matrix

| Step | DHU state | Module | Expected | Result |
|------|-----------|--------|----------|--------|
| 1 | Stopped (`restrict none`, speed 0, park) | OFF | Keyboard works | Pass |
| 2 | Moving (`speed 10`, drive, brake off) | OFF | Keyboard locked | Pass |
| 3 | Moving | ON | **Keyboard unlocked** | Pass |
| 4 | Stopped | ON | Keyboard works | Pass |
| 5 | Toggle OFF while moving | OFF | Lock returns | Pass |

### Real head unit (2026-06-14)

| Scenario | Result |
|----------|--------|
| External app keyboard (WhatsApp reply, etc.) | Pass |
| Google Maps search bar while driving | Pass |
| No gearhead `:projection` crash on keyboard open | Pass |

### Procedure

1. Enable **AA Keyboard Unlock** in LSPosed Manager → scope **Android Auto** and **Google Maps**
2. Open the module app → enable **Unlock keyboard while active**
3. Optional: enable **Verbose logging**
4. Force-stop Android Auto, reconnect DHU or head unit
5. Run `./scripts/dhu_validate.sh` for adb forward + log capture commands
6. In DHU terminal, run lock/unlock sequences from [DHU_simulate_moving.md](DHU_simulate_moving.md)

### Expected LSPosed log (module ON, debug enabled)

```
[AAKeyboardUnlock] Loading hooks in com.google.android.projection.gearhead
[AAKeyboardUnlock] Loading hooks in com.google.android.apps.maps
[AAKeyboardUnlock] Spoofing car speed 10.0 -> 0
[AAKeyboardUnlock] Spoofing driving status 0x2 -> 0x0
```
