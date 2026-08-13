# Wi-Fi Watchdog

**Keeps an Android device on Wi-Fi — without root.**

Some devices drop off Wi-Fi and simply never come back. The radio looks up, the icon
looks fine, and nothing on the device notices until a human walks over and toggles
something. Wi-Fi Watchdog is the thing that walks over.

It probes the network continuously, and when the link dies it escalates through
progressively stronger recovery actions — ending in a **genuine airplane-mode cycle**,
which is normally impossible for an unprivileged app on Android 8.1.

[![Build](https://github.com/dimatx/wifi-watchdog/actions/workflows/build.yml/badge.svg)](https://github.com/dimatx/wifi-watchdog/actions/workflows/build.yml)
[![Licence: MIT](https://img.shields.io/badge/licence-MIT-blue.svg)](LICENSE)
![Android 8.1+](https://img.shields.io/badge/Android-8.1%2B-3ddc84)

> Built for a Lenovo ThinkSmart View (`starfire`) on LineageOS 15.1, but nothing in it
> is device-specific. Any rootless Android 8.1+ device you can reach once over adb
> will work.

---

## Highlights

- 🔁 **Four-rung recovery ladder** — from a polite `reassociate` all the way to a full
  driver unload and a real airplane-mode cycle.
- 🔓 **No root required.** Two one-time adb commands and that's it.
- ✈️ **Real airplane mode from an unprivileged app** — via the assistant trick, the only
  path that actually works on Android 8.1. [How ↓](#why-airplane-mode-is-special)
- 🎯 **Probes your gateway, not a server.** A remote service restarting can't trigger a
  destructive recovery.
- 📮 **Push alerts that survive the outage.** Notifications are queued while the link is
  down and flushed on recovery, with their original timestamps intact.
- 💓 **Heartbeat webhook** for Uptime Kuma or anything else that accepts a `GET`.
- 🪶 **Tiny.** One foreground service, one alarm, an on-device event log. No analytics,
  no network calls you didn't configure.

---

## Quick start

```bash
# Build and install
./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk

# Grant the two capabilities it needs (both survive reboots and reinstalls)
adb shell pm grant com.shymoose.wifiwatchdog android.permission.WRITE_SECURE_SETTINGS
adb shell settings put secure voice_interaction_service \
  'com.shymoose.wifiwatchdog/.AssistantService'
```

Open the app. The status card confirms both prerequisites are satisfied, and the
watchdog starts immediately — and on every boot from then on.

Want the assistant slot back? **Release assistant slot** in the overflow menu.

---

## How recovery works

A foreground service probes the network's default gateway on a fixed interval. When
probes start failing, a timer runs and recovery escalates:

| Down for | Action | What it actually does |
|---|---|---|
| 60s | Reassociate | Asks the supplicant to re-join the current network |
| 120s | Soft toggle | Wi-Fi off, Wi-Fi on |
| 240s | Hard reset | Sets `wifi_scan_always_enabled=0`, toggles Wi-Fi, restores it — this genuinely unloads the driver and re-enumerates `wlan0` |
| 360s | Airplane cycle | Real airplane mode on, dwell, off |

If the link is still down after all four, it backs off — preferring the airplane cycle,
doubling the gap from 5 minutes to a 30-minute cap. Every action lands in the on-device
activity log.

All thresholds are configurable, and the airplane rung can be disabled entirely.

### Why the gateway is the probe target

The probe follows the Wi-Fi network's default gateway, discovered from `LinkProperties`
with a `DhcpInfo` fallback. That's deliberate: the gateway is the nearest host that can
answer, so a failed probe means the **link** is broken rather than some remote service
being down. Point the probe at a fixed service host and an ordinary server reboot could
trigger a destructive airplane cycle.

ICMP (`/system/bin/ping`) is tried first, because a router usually has no open TCP port
and a TCP-first probe would burn the full timeout every cycle. A TCP connect to port 53
is the fallback — and a *refused* connection counts as reachable, since a refusal still
proves packets made the round trip.

The last known gateway is remembered, because route discovery returns nothing once the
route table is torn down — precisely when the watchdog needs a target. Setting a probe
host in Settings pins the probe to that host and port instead.

---

## Why airplane mode is special

This is the interesting part. On Android 8.1 an ordinary app **cannot** toggle airplane
mode. Every obvious route is closed:

| Approach | Result |
|---|---|
| Write `Settings.Global.airplane_mode_on` | Cosmetic — flips the flag, changes nothing |
| `am broadcast android.intent.action.AIRPLANE_MODE` | Refused — it's a protected broadcast |
| `ConnectivityManager.setAirplaneMode()` | Needs `CONNECTIVITY_INTERNAL` (`signature\|privileged`, not grantable) |
| `cmd connectivity airplane-mode` | Doesn't exist until Android 11 |

The one path that works is the **assistant trick**.
`com.android.settings.AirplaneModeVoiceActivity` is exported with no permission guard,
and calls `setAirplaneMode()` itself — as the system. Its base class gates on exactly one
thing: `isVoiceInteractionRoot()`. So the caller must be the *current*
`VoiceInteractionService`, and must launch it via
`VoiceInteractionSession.startVoiceActivity()`.

So the app ships a minimal `VoiceInteractionService` (plus the required session and
recognition stubs), temporarily claims the assistant slot, fires the intent, and hands
the slot back on request. This is the same mechanism MacroDroid uses.

### Failsafe

Airplane mode is the one action that can strand the device, so it's guarded. Before
enabling it the app commits an `airplanePending` flag and arms an `AlarmManager`
failsafe at `dwell + 30s`. If the process is killed mid-cycle, both the boot receiver
and the service's `onStartCommand` see the pending flag and force airplane mode back
off.

---

## Notifications (ntfy)

Push alerts via [ntfy](https://ntfy.sh). Four fields: **server URL** (defaults to
`https://ntfy.sh`), **topic**, **username** and **password**. Only the topic is
required — leave it blank to disable notifications entirely.

With a username, the password is sent as HTTP basic auth. Without one it's sent as a
bearer token, so an ntfy access token works in the password field alone.

Every message carries the event time plus the device's hostname, IP and MAC:

```
Wi-Fi restored — living-room-tablet
living-room-tablet · 10.0.0.42 · a1:b2:c3:d4:e5:f6
Occurred: Aug 12, 6:23 PM EDT
Down for: 2m 23s
Stage: 3
Probe target: 10.0.0.1 (last known gateway)
```

**Every event except recovery happens while the link is down**, so messages are never
posted inline — that couldn't succeed anyway, and would stall the recovery ladder behind
a socket timeout. Instead they go to a persistent outbox and flush on the next
successful probe, oldest first. A delayed message gains a
`Queued while offline, delivered …` line, so the original timestamp is never ambiguous.

The outbox holds 25 messages, discards anything older than 24 hours, and backs off 60
seconds after a failed attempt. IP and MAC are cached whenever they're readable, so an
outage report can still identify the device once the interface is gone — a cached value
is marked `(last known)`.

**Send test notification** in Settings publishes immediately and records the outcome in
the event log.

### Setting the device name

The hostname in each message comes from `Settings.Global.device_name`. Android 8.1 has
no UI for it, so set it over adb — effective immediately, no restart:

```bash
adb shell settings put global device_name 'living-room-tablet'
```

---

## Heartbeat webhook

Optional, and independent of ntfy. While the link is healthy the app sends a plain `GET`
to a URL of your choice every *n* seconds (default 300).

It's a heartbeat, not an event hook: during an outage the app deliberately sends
**nothing**, and that silence is what makes a push monitor raise the alarm.

Built against an [Uptime Kuma](https://uptime.kuma.pet/) push monitor —

```
https://kuma.example.com/api/push/<token>?status=up&msg=OK&ping={ping}
```

— but the URL is treated as opaque, so anything accepting a `GET` works. Four
placeholders are substituted when present: `{ping}` (last probe round-trip in ms, left
unencoded so it can be appended to Kuma's `ping=`), `{device}`, `{ip}` and `{mac}`.

Nothing is queued or retried — a missed heartbeat is *meant* to be missed. Only
transitions are logged, so a long outage can't flood the event log.

---

## Settings

Probe host (blank = follow the gateway) and port · check interval · the four escalation
thresholds · airplane dwell time · whether the airplane rung is allowed at all · ntfy
server, topic and credentials · heartbeat URL and interval.

---

## Building

```bash
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`. `minSdk` is 27. There are no
prebuilt release artifacts — build it yourself.

**No keystore required.** If one isn't configured, release builds fall back to the debug
signing key, so a fresh clone builds out of the box. To sign properly, set
`KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` — either as
environment variables (for CI) or in an untracked `keystore.properties` at the repo
root.

> ⚠️ **`targetSdk` is pinned to 28 on purpose.** `WifiManager.setWifiEnabled()` silently
> returns `false` for apps targeting API 29+, which would break every rung of the
> recovery ladder. The resulting `ExpiredTargetSdkVersion` lint error is expected and
> suppressed at build time. Don't "fix" it.

---

## Licence

MIT — see [LICENSE](LICENSE).
