# drivetime-android

Native Android companion for **[drivetime](https://github.com/improvmasta/drivetime)** —
the in-car telemetry source. Logs high-frequency GPS (and, in Phase B, reads the
OBD-II dongle directly) and posts it to drivetime's `/api/ingest`. Later phases add
Android Auto screens and commute alerts.

Plan & decisions: see **[`ROADMAP.md`](ROADMAP.md)** (client roadmap — robustness +
a control API driven by Samsung Modes & Routines) and drivetime's `NATIVE_APP.md`.

## Status — Phase A (GPS logger)
- [x] Project + CI (builds a debug APK on every push)
- [x] Settings (server URL, ingest token, fix interval)
- [x] Foreground `LocationService` streaming fixes via FusedLocationProvider
- [x] `Uploader` — batched POST to `/api/ingest` with an on-disk offline queue
- [x] Routine/automation control (START/STOP/TOGGLE intents) — see below
- [x] Keep-awake guidance (battery-optimization exemption + Samsung sleeping-apps steps)
- [x] **OBD-II via custom ELM327 layer** — RPM, speed, load, coolant, throttle,
  MAF, battery voltage + DTCs; merged onto each GPS fix (see OBD setup)
- [x] **Android Auto** screen — live stats when logging; today's **leave-by** card when idle
- [x] **Auto start/stop** when driving (activity-recognition IN_VEHICLE)
- [x] **Alerts** — polls the server (WorkManager) and notifies for commute/fault codes
- [ ] Background-location permission flow polish

## OBD setup
Pair the ELM327 dongle in Android Bluetooth settings, then tap **OBD dongle** in
the app and select it. While logging, the app connects over Bluetooth SPP, runs
the ELM327 init, and polls PIDs every ~1.5 s (DTCs ~every 3 min), attaching the
latest engine snapshot to each uploaded fix. If the dongle is off/unpaired, GPS
logging continues without engine data.

## Android Auto
A glanceable dashboard (Car App Library `PaneTemplate`): live **speed, RPM,
coolant, battery voltage** and a **Start/Stop** toggle, refreshing every ~2 s.
It shares live data with the logging service in-process (`LiveState`).

To see it (sideloaded, non-Play app): on the phone, Android Auto → Settings →
tap *Version* repeatedly to unlock **Developer settings** → enable **Unknown
sources**. drivetime then appears in the Android Auto launcher.

## Auto start/stop & alerts
- **Auto start/stop**: enable "Auto start/stop when driving" — uses Google
  activity-recognition; entering a vehicle starts logging, exiting stops it.
  (OBD-connect is the other half of the trigger, inside the service.)
- **Alerts**: enable "Commute & fault-code alerts" — a 15-min WorkManager job
  pulls `/api/alerts` and posts notifications (e.g. a new check-engine code),
  then marks them read.

## Phases
- **A** GPS logger ✓ → **B** OBD (custom ELM327) ✓ → **C** Android Auto ✓
  (live + leave-by card) → **D** commute alerts ✓ (notifications + auto-trip).

## Build & install
CI builds `drivetime-debug-apk` on each push (Actions → artifact). Download and
sideload to the phone. Debug APKs are installable as-is.

Local build:
```bash
gradle wrapper --gradle-version 8.7   # first time (no wrapper jar committed)
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

## Configure on the phone
Open the app → set **Server URL** (`https://drivetime.jupiterns.org`) and the
**ingest token** (same as the server's `DRIVETIME_TOKEN`) → Save → Start logging.

## Keeping it awake (important on Samsung)
Doze and Samsung's "Sleeping / Deep sleeping apps" lists will kill a background
GPS service and drop fixes mid-drive. The app detects when it isn't exempt and
shows a banner with two buttons:
- **Allow background** → system battery-optimization-exemption dialog.
- **App settings** → app info, where you also: Samsung → Battery → *unrestricted*,
  and Settings → Battery → Background usage limits → remove drivetime from
  *Sleeping apps* / *Deep sleeping apps*.
The banner hides once the app is exempt. Starting logging also prompts for the
exemption if it's missing.

## Routine / automation control
Logging can be started/stopped by any intent-capable automation, so it only runs
when needed (battery). Three actions:

| Action | Effect |
|---|---|
| `org.jupiterns.drivetime.action.START` | start logging |
| `org.jupiterns.drivetime.action.STOP` | stop logging |
| `org.jupiterns.drivetime.action.TOGGLE` | flip current state |

Two entry points: **ControlActivity** (launch it — most reliable for *starting*
from the background) and **ControlReceiver** (broadcast — great for STOP).

Examples:
```bash
# adb / Tasker "Launch Activity":
am start  -n org.jupiterns.drivetime/.ControlActivity -a org.jupiterns.drivetime.action.START
am start  -n org.jupiterns.drivetime/.ControlActivity -a org.jupiterns.drivetime.action.STOP
# broadcast (Tasker/MacroDroid "Send Intent", target = Broadcast Receiver):
am broadcast -n org.jupiterns.drivetime/.ControlReceiver -a org.jupiterns.drivetime.action.STOP
```
- **Tasker / MacroDroid:** "Launch Activity" (START) or "Send Intent → Broadcast"
  (STOP), component as above. These can be the bridge for **Samsung Modes &
  Routines** (run a Tasker task) and **Home Assistant** (companion-app intent).
- **Google Assistant routines:** add the launch-activity intent as a custom action.
- Tip: pair a "leaving home on a weekday morning" trigger → START with an
  "arrived home / engine off" trigger → STOP. Phase B can also auto-start on
  OBD-connect; routines are the manual/scheduled override.

> Background *start* on Android 12+ may need the automation app to have
> "Display over other apps"/foreground exemptions. STOP always works.

## Enabling signed release builds (next)
Generate a release keystore, add `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD` as repo secrets, add a `signingConfig` to
`app/build.gradle.kts`, and switch CI to `assembleRelease`. Tracked for Phase A wrap-up.

## Tech
Kotlin · min SDK 26 · FusedLocationProvider · OkHttp · coroutines · custom ELM327
(Phase B) · `androidx.car.app` (Phase C).
