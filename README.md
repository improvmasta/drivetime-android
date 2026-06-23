# drivetime-android

Native Android companion for **[drivetime](https://github.com/improvmasta/drivetime)** —
the in-car telemetry source. Logs GPS at two tiers (sparse background → dense while
driving), reads an OBD-II dongle, and posts to drivetime's `/api/ingest`, with
Android Auto and commute alerts on top. Built for one rule above all: **never
silently stop logging.**

Plan & decisions: see **[`ROADMAP.md`](ROADMAP.md)** (robustness + a control API
driven by Samsung Modes & Routines) and drivetime's `NATIVE_APP.md`.

## How it works (short version)
- **Two tiers, chosen automatically.** Always-on **Light** background logging
  (sparse, low-power — a continuous everyday timeline) ramps to dense **Driving**
  logging (high-accuracy GPS + OBD) when you're actually in the car.
- **Layered drive detection** ([`DriveDetector`](app/src/main/java/org/jupiterns/drivetime/DriveDetector.kt)),
  a priority cascade — **car Bluetooth → OBD-connect → sustained speed** — *not*
  activity-recognition guessing, so a car crawling in traffic is never mislabeled a
  bike. A routine/shortcut can force a tier or turn logging off.
- **Durable, batched upload.** Every fix is written to an on-disk queue first, then
  POSTed in batches; a crash, kill, or dead-zone loses nothing.
- **Robust.** Resumes after reboot/app-update; a watchdog relaunches the service if
  the OS kills it.

## Status
- [x] Foreground `LocationService` (FusedLocationProvider) with an on-disk offline queue
- [x] Settings (server URL, ingest token, intervals, car-BT, OBD)
- [x] **Tiered tracking + layered drive detection** (car-BT / OBD / speed → Light/Driving)
- [x] **OBD-II via custom ELM327 layer** — RPM, speed, load, coolant, throttle, MAF,
  voltage + DTCs; merged onto each fix while Driving; also a driving signal
- [x] **Android Auto** screen — live stats when logging; today's **leave-by** card when idle
- [x] **Alerts** — 15-min WorkManager poll of `/api/alerts` → notifications
- [x] **Robustness** — boot/update restart, self-healing watchdog, verify-before-delete
  queue with backoff + size cap (see [`ROADMAP.md`](ROADMAP.md) Pillar 1)
- [x] **Control API + App Shortcuts** — modes drivable by Samsung Modes & Routines
- [ ] Background-location permission-flow polish; signed release builds

## Tracking tiers & drive detection
| Tier | When | Fix rate | Power |
|---|---|---|---|
| **Light** | not driving (default everyday state) | `lightIntervalSec` = 60s | balanced |
| **Driving · moving** | in the car, moving | `intervalSec` = 3s | high accuracy + OBD |
| **Driving · stopped** | in the car at a red light | `idleIntervalSec` = 20s | high accuracy + OBD |

`DriveDetector` resolves the tier from a cascade (first hit wins): **forced mode →
car Bluetooth connected → OBD connected → sustained/high GPS speed**. Connection
signals hold *Driving* even at a dead stop, so a stop never drops the tier. The
speed backstop has hysteresis so traffic crawls don't flap. Inside *Driving*, the
fix rate adapts to motion (dense moving, idle back-off at lights).

## Car Bluetooth & OBD setup
- **Car Bluetooth (the #1 driving signal):** pair the phone to the car stereo, then
  tap **Car Bluetooth** in the app and pick it. Connecting to it = Driving,
  deterministically.
- **OBD dongle:** pair the ELM327 in Android Bluetooth settings, tap **OBD dongle**,
  pick it. The app connects over Bluetooth SPP when it has reason to think you're
  driving, runs the ELM327 init, polls PIDs (~1.5s; DTCs ~3min) onto each fix, and
  treats a successful connect as a driving signal. Off/unpaired → GPS keeps logging
  without engine data.

## Uploads & local storage
- **Local store** is a durable on-disk **outbox** (`queue.jsonl`), not a history:
  every fix is appended before any network attempt and removed **only after the
  server acks it** (verify-before-delete). Survives crash/kill/dead-zones; bounded at
  16 MB (drop-oldest); atomic rewrites.
- **Batched upload:** fixes flush to `POST {serverUrl}/api/ingest?key={token}` on a
  cadence — the periodic tick (`uploadIntervalSec` = 45s), when a batch fills
  (`BATCH_FIXES` = 25), or **immediately when connectivity returns** — instead of one
  POST per fix. A single flush drains the whole backlog; failures back off
  exponentially (5s→5min, jittered). The permanent record lives server-side.
- **Payload:** OwnTracks-shaped JSON array — `{_type,lat,lon,tst,vel,acc,cog}` plus
  engine fields (`rpm,coolant_c,voltage,dtc,…`) while Driving. Idempotent on the server.

## Control surface (modes · shortcuts · routines)
Logging mode is the *desired behaviour*, set by you or any automation. `AUTO` lets
the detector decide; the others force it:

| Mode | Action | Effect |
|---|---|---|
| Auto | `…action.MODE_AUTO` / `…action.START` | detector decides Light vs Driving |
| Driving | `…action.MODE_DRIVING` | force dense logging |
| Eco | `…action.MODE_ECO` | force Light logging |
| Off | `…action.STOP` | stop logging |
| (generic) | `…action.SET` extras `key=mode value=<auto\|driving\|eco\|off>` | routine-friendly |

(`…` = `org.jupiterns.drivetime`.) Two entry points: **ControlActivity** (launch —
most reliable for *starting* from the background) and **ControlReceiver** (broadcast).

- **App Shortcuts** (`res/xml/shortcuts.xml`): Driving / Auto / Eco / Off appear on
  the launcher-icon long-press and under **Samsung Modes & Routines → app actions** —
  no extra automation app needed. e.g. Samsung *Driving* mode → **Driving**, *arrive
  home* → **Eco**/**Off**.
- **Tasker / MacroDroid / Home Assistant:** launch the activity or broadcast the action:
```bash
am start     -n org.jupiterns.drivetime/.ControlActivity -a org.jupiterns.drivetime.action.MODE_DRIVING
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.STOP
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.SET --es key mode --es value eco
```

> Background *start* on Android 12+ prefers ControlActivity (an Activity context
> dodges the foreground-service-start limit). STOP/force-Eco always work.

## Android Auto
A glanceable `PaneTemplate`: live **speed, RPM, coolant, battery** + a **Start/Stop**
toggle when logging, today's **leave-by** card when idle; shares data in-process via
`LiveState`. To see it (sideloaded): Android Auto → Settings → tap *Version* to
unlock **Developer settings** → enable **Unknown sources**.

## Build & install
CI builds `drivetime-debug-apk` on each push (Actions → artifact). Sideload it; debug
APKs install as-is (uninstall a prior build first if signatures differ).

```bash
gradle wrapper --gradle-version 8.7   # first time (no wrapper jar committed)
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
```

## Configure on the phone
Open the app → **Server URL** (`https://drivetime.jupiterns.org`) + **ingest token**
(the server's `DRIVETIME_TOKEN`) → Save → pick **Car Bluetooth** (and OBD if used) →
**Start** (enters Auto). The status line shows mode + tier, e.g. `● Auto · DRIVING (car BT)`.

## Keeping it awake (important on Samsung)
Doze and Samsung's "Sleeping / Deep sleeping apps" lists will kill a background GPS
service and drop fixes. The app shows a banner when it isn't battery-exempt:
- **Allow background** → battery-optimization-exemption dialog.
- **App settings** → app info: Samsung → Battery → *unrestricted*, and Settings →
  Battery → Background usage limits → remove drivetime from *Sleeping / Deep sleeping apps*.
The banner hides once exempt. The boot receiver + watchdog resume logging after a
reboot or an OS kill, but only if the app is allowed to run in the background.

## Tech
Kotlin · min SDK 26 · FusedLocationProvider · OkHttp · coroutines · WorkManager ·
custom ELM327 · `androidx.car.app`.
