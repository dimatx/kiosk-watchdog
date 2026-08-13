# Wi-Fi Watchdog

A small, rootless Android app that keeps a device on Wi-Fi. It watches connectivity,
and when the link goes away and does not come back on its own, it escalates through
progressively stronger recovery actions — ending in a **real airplane-mode cycle**.

Built for a Lenovo ThinkSmart View (`starfire`) running LineageOS 15.1 / Android 8.1,
which intermittently drops off Wi-Fi and never recovers by itself.

## What it does

A foreground service probes the network's default gateway on a fixed interval. When
probes fail, a timer starts and recovery escalates:

| Down for | Action |
|---|---|
| 60s | `reassociate` — ask the supplicant to re-join |
| 120s | Soft toggle — Wi-Fi off/on |
| 240s | Hard reset — set `wifi_scan_always_enabled=0`, toggle Wi-Fi, restore. This genuinely unloads the driver and re-enumerates `wlan0`. |
| 360s | **Airplane-mode cycle** — real airplane mode on, dwell, off |

After that it backs off, preferring the airplane cycle, doubling from 5 minutes to a
30-minute cap. Every action is written to an on-device activity log.

## Probe target

The probe follows the Wi-Fi network's default gateway, discovered from
`LinkProperties` (with a `DhcpInfo` fallback). This is deliberate: the gateway is the
nearest host that can answer, so a failed probe means the *link* is broken rather than
some remote service being down. Probing a fixed service host would let, say, a Home
Assistant restart trigger a destructive airplane cycle.

The gateway is checked with ICMP (`/system/bin/ping`) first, because a router usually
has no open TCP port and a TCP-first probe would burn the whole timeout every cycle. A
TCP connect to port 53 is the backup, and a *refused* connection counts as reachable —
a refusal still proves packets made the round trip.

The last known gateway is remembered, since route discovery returns nothing once the
route table is torn down — exactly when the watchdog needs a target. Setting a probe
host in Settings pins the probe to that host and port instead.

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

Probe host (blank = follow the gateway) and port, check interval, escalation
thresholds, airplane dwell time, whether the airplane rung is allowed at all, and the
ntfy notification settings.

## Notifications (ntfy)

Four fields: **server URL** (defaults to `https://ntfy.sh`), **topic**, **username**
and **password**. Only the topic is required — leave it blank to disable notifications
entirely. With a username the password is sent as HTTP basic auth; without one it is
sent as a bearer token, so an ntfy access token works in the password field alone.

Every notification body carries the event time plus the device's hostname, IP and MAC:

```
Wi-Fi restored — starfire
starfire · 192.168.27.227 · 94:08:53:2a:fb:75
Occurred: Aug 12 18:23:17
Down for: 2m 23s
Stage: 3
Probe target: 192.168.27.1 (last known gateway)
```

Because every event except recovery happens **while the link is down**, messages are
never posted inline — that could not succeed and would stall the recovery ladder behind
a socket timeout. They are written to a persistent outbox and flushed on the next
successful probe, oldest first. A delayed message gains a
`Queued while offline, delivered …` line so the original timestamp is never ambiguous.
The outbox holds 25 messages, discards anything older than 24 hours, and backs off 60
seconds after a failed attempt. IP and MAC are cached whenever they are readable so an
outage report can still identify the device once the interface is gone; a cached value
is marked `(last known)`.

**Send test notification** in Settings publishes immediately and records the outcome in
the event log.

## Safety

Airplane mode is the one action that can strand the device. Before enabling it the app
commits an `airplanePending` flag and arms an `AlarmManager` failsafe at `dwell + 30s`.
If the process is killed mid-cycle, both the boot receiver and the service's
`onStartCommand` see the pending flag and force airplane mode back off.
