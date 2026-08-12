# Wi-Fi Watchdog

A small, rootless Android app that keeps a device on Wi-Fi. It watches connectivity,
and when the link goes away and does not come back on its own, it escalates through
progressively stronger recovery actions — ending in a **real airplane-mode cycle**.

Built for a Lenovo ThinkSmart View (`starfire`) running LineageOS 15.1 / Android 8.1,
which intermittently drops off Wi-Fi and never recovers by itself.

## What it does

A foreground service probes a host on the LAN on a fixed interval. When probes fail,
a timer starts and recovery escalates:

| Down for | Action |
|---|---|
| 60s | `reassociate` — ask the supplicant to re-join |
| 120s | Soft toggle — Wi-Fi off/on |
| 240s | Hard reset — set `wifi_scan_always_enabled=0`, toggle Wi-Fi, restore. This genuinely unloads the driver and re-enumerates `wlan0`. |
| 360s | **Airplane-mode cycle** — real airplane mode on, dwell, off |

After that it backs off, preferring the airplane cycle, doubling from 5 minutes to a
30-minute cap. Every action is written to an on-device activity log.

## Why airplane mode is special

On Android 8.1 a normal app cannot toggle airplane mode:

- Writing `Settings.Global.airplane_mode_on` alone does nothing — it is cosmetic.
- `am broadcast android.intent.action.AIRPLANE_MODE` is a **protected broadcast** and is refused.
- `ConnectivityManager.setAirplaneMode()` needs `CONNECTIVITY_INTERNAL`, which is
  `signature|privileged` and therefore not grantable via `pm grant`.
- `cmd connectivity airplane-mode` does not exist until Android 11.

The one path that works is the **assistant trick**. `com.android.settings.AirplaneModeVoiceActivity`
is exported with no permission guard and calls `setAirplaneMode()` itself as the system.
Its base class gates only on `isVoiceInteractionRoot()`, so the caller must be the current
`VoiceInteractionService` and must launch it via `VoiceInteractionSession.startVoiceActivity()`.

This app ships a minimal `VoiceInteractionService` (plus the required session and
recognition stubs), temporarily claims the assistant slot, fires the intent, and
releases the slot when asked. This is the same mechanism MacroDroid uses.

## Setup

Two things must be done once over adb. Both survive reboots and `install -r`.

```bash
# 1. Allow the app to change secure settings (used by the hard-reset rung)
adb shell pm grant com.shymoose.wifiwatchdog android.permission.WRITE_SECURE_SETTINGS

# 2. Make the app the assistant, so it can invoke real airplane mode
adb shell settings put secure voice_interaction_service \
  'com.shymoose.wifiwatchdog/.AssistantService'
```

The app's status card shows whether each prerequisite is satisfied. The overflow menu
has **Release assistant slot** to hand the slot back.

## Building

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. Release builds are signed when
`release.jks` and the matching credentials are present; otherwise use `assembleDebug`.

`targetSdk` is pinned to **28 on purpose**. `WifiManager.setWifiEnabled()` silently
returns `false` for apps targeting API 29+, which would break every recovery rung.
The `ExpiredTargetSdkVersion` lint error is expected and suppressed at build time.

## Settings

Probe host and port, check interval, escalation thresholds, airplane dwell time,
whether the airplane rung is allowed at all, and an optional Home Assistant webhook
that receives `{"event", "down_seconds", "stage"}` on each action.

## Safety

Airplane mode is the one action that can strand the device. Before enabling it the app
commits an `airplanePending` flag and arms an `AlarmManager` failsafe at `dwell + 30s`.
If the process is killed mid-cycle, both the boot receiver and the service's
`onStartCommand` see the pending flag and force airplane mode back off.
