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
  a priority cascade — **car Bluetooth → OBD-connect → motion-onset → sustained speed**
  — *not* activity-recognition guessing, so a car crawling in traffic is never mislabeled
  a bike. A routine/shortcut can force a tier or turn logging off.
- **Second-accurate starts in any car.** A hardware **significant-motion** trigger
  (near-zero battery, no pairing) wakes an instant GPS Doppler check the moment you move,
  so dense logging begins within seconds — not just in your own paired car. The 60 s Light
  heartbeat stays as the backstop.
- **Durable, batched upload.** Every fix is written to an on-disk queue first, then
  POSTed in batches; a crash, kill, or dead-zone loses nothing.
- **Standalone by default (no server needed).** With no server URL set, the app runs the
  full drivetime SPA **bundled inside the APK**, served over a secure origin via
  `WebViewAssetLoader` ([`Shell`](app/src/main/java/org/jupiterns/drivetime/Shell.kt)) — no
  login. The phone's own GPS feeds the SPA's on-device replica (a bounded
  [`WebFixBuffer`](app/src/main/java/org/jupiterns/drivetime/WebFixBuffer.kt) drained via the
  `DrivetimeNative` JS bridge), so drives + mileage work entirely on-device. Set a server URL
  to opt into hosted sync. See drivetime's `STANDALONE.md`.
  - The bundled web lives in `app/src/main/assets/web/` — a committed snapshot. Refresh it
    with drivetime's `./sync-web-to-android.sh` (builds the SPA with `base=/assets/web/`)
    before shipping an APK when the frontend changed.
- **Robust.** Resumes after reboot/app-update; a watchdog relaunches the service if
  the OS kills it.

## Status
- [x] Foreground `LocationService` (FusedLocationProvider) with an on-disk offline queue
- [x] **Tracker settings live in the SPA's Settings tabs** (General/Tracking/Devices/Sync/More),
  not a separate native screen — every knob is a real in-tab control read/written over the
  `DrivetimeNative` bridge, and the genuinely-native flows (permission prompts, BT pairing,
  backup file-pickers, QR pairing scan, the updater) are hosted by `WebViewActivity` and fired
  in place from their tab. Tracking runs standalone — no server required (STANDALONE.md)
- [x] **Tiered tracking + layered drive detection** (car-BT / OBD / speed → Light/Driving)
- [x] **Tier-aware upload cadence** — ~10s while DRIVING (near-real-time), 45s in LIGHT,
  immediate flush on app-foreground / connectivity-regained / charge-connected
- [x] **OBD-II via custom ELM327 layer** — RPM, speed, load, coolant, throttle, MAF,
  voltage + DTCs; merged onto each fix while Driving; also a driving signal
- [x] **Android Auto** screen — live stats when logging; today's **leave-by** card when idle
- [x] **Alerts** — 15-min WorkManager poll of `/api/alerts` → notifications
- [x] **In-app updates** — checks `/dl/version.json` on foreground; a newer signed build
  downloads + installs in place (keeps settings), no browser/sideload hunt
- [x] **Robustness** — boot/update restart, self-healing watchdog, verify-before-delete
  queue with backoff + size cap, OEM-kill detector with manufacturer-specific
  deep links (Samsung, Xiaomi, Huawei, OnePlus, Oppo/Realme, Vivo, Asus)
- [x] **Permissions gate** — single source of truth (`Permissions.snapshot`) driving
  the warning banner; two-step fine → background-location flow on first Start
- [x] **Control API + App Shortcuts + STATE_CHANGED broadcast** — extended `SET`
  (every cadence + auto-trip + alerts), `QUERY`, optional shared-token gate; see
  **[`AUTOMATION.md`](AUTOMATION.md)**
- [x] **CI** — Robolectric unit tests + Android Lint run on every push before the APK
- [ ] Signed release builds

## Tracking tiers & drive detection
| Tier | When | Fix rate | Power |
|---|---|---|---|
| **Light** | not driving (default everyday state) | `lightIntervalSec` = 60s | balanced |
| **Driving · moving** | in the car, moving | `intervalSec` = 3s | high accuracy + OBD |
| **Driving · stopped** | in the car at a red light | `idleIntervalSec` = 20s | high accuracy + OBD |

`DriveDetector` resolves the tier from a cascade (first hit wins): **forced mode →
car Bluetooth connected → OBD connected → motion-onset → sustained/high GPS speed**.
Connection signals hold *Driving* even at a dead stop, so a stop never drops the tier.
The speed backstop has hysteresis so traffic crawls don't flap. Inside *Driving*, the
fix rate adapts to motion (dense moving, idle back-off at lights).

**Motion-onset (device-agnostic fast start).** In Light, a one-shot hardware
significant-motion trigger is armed (near-zero battery, no permission, no pairing). When
it fires, the app raises GPS to a brief probationary dense rate and takes one instant
`getCurrentLocation` Doppler fix plus a short accelerometer read; `confirmOnset` promotes
to *Driving* when the speed is clearly vehicular (smooth-vs-bouncy accel breaks the
ambiguous low-speed tie). Because the probationary dense fixes also feed the speed
backstop, the *start* is captured within seconds even before the tier flips — the key to
second-accurate boundaries in **any** car, not just a paired one. Tunable via the
`motion_onset` / `onset_*` settings (and routine SET keys); disable `motion_onset` to fall
back to the 60 s heartbeat + speed backstop alone.

## Car Bluetooth & OBD setup
Tapping **Car Bluetooth** or **OBD dongle** opens a live picker that **scans for
nearby devices** (like Torque) and lists them alongside already-paired ones. The
first tap requests the Bluetooth permission itself — if it's ever missing on
Android 12+, grant **Nearby devices** in app settings.

- **Car Bluetooth (the #1 driving signal):** pick your car stereo from the scan.
  Connecting to it = Driving, deterministically.
- **OBD dongle:** many cheap ELM327 clones **never pair and never appear in system
  Bluetooth settings** — they connect over *insecure* Bluetooth SPP without bonding
  (that's how Torque reaches them). So just let the picker scan and **tap the dongle
  when it appears**; no pairing/PIN needed. (**Enter MAC** is there as a fallback if
  you know the address.) When it has reason to think you're driving, the app connects
  (secure SPP → insecure SPP → channel-1 fallback), runs the ELM327 init, and polls
  PIDs (~1.5s; DTCs ~3min) onto each fix — also treating a successful connect as a
  driving signal. Off/out-of-range → GPS keeps logging without engine data.
- **Engine data needs the ECU awake** (ignition on / engine running). With the car
  off the bus is asleep, so only battery **voltage** reads (ATRV, read by the dongle
  itself) — RPM/coolant/load/throttle/MAF require a running engine.
- **Diagnostics:** every OBD connect writes the ELM327 init transcript, the **raw
  per-PID adapter responses**, and the first decoded sample to the **activity log**,
  and connection failures are logged too — so a protocol/parse fault is visible
  instead of guessed.

## Uploads & local storage
- **Local store** is a durable on-disk **outbox** (`queue.jsonl`), not a history:
  every fix is appended before any network attempt and removed **only after the
  server acks it** (verify-before-delete). Survives crash/kill/dead-zones; bounded at
  16 MB (drop-oldest); atomic rewrites.
- **Batched upload:** fixes flush to `POST {serverUrl}/api/ingest` (authenticated with
  HTTP Basic — your dashboard username/password) on a
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
| Generic | `…action.SET` extras `key=… value=…` | full routine API (cadences, BT, alerts…) |
| Query | `…action.QUERY` | emit a `STATE_CHANGED` broadcast back |

(`…` = `org.jupiterns.drivetime`.) Full key reference, recipes for Samsung Modes &
Routines / Tasker / HA, and the `STATE_CHANGED` extras schema are in
**[`AUTOMATION.md`](AUTOMATION.md)** (also shown verbatim in the in-app Settings → Automation).

Two entry points: **ControlActivity** (launch — most reliable for *starting* from
the background) and **ControlReceiver** (broadcast).

```bash
am start     -n org.jupiterns.drivetime/.ControlActivity -a org.jupiterns.drivetime.action.MODE_DRIVING
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.STOP
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.SET --es key mode --es value eco
```

> Background *start* on Android 12+ prefers ControlActivity (an Activity context
> dodges the foreground-service-start limit). STOP/force-Eco always work.

## Settings backup
Settings → Backup → **Export** / **Import** writes every editable knob as JSON whose
keys match the routine `SET` names — the same file restores onto a new phone *or*
serves as a one-shot routine preset.

## Android Auto
A glanceable `PaneTemplate`: live **speed, RPM, coolant, battery** + a **Start/Stop**
toggle when logging, today's **leave-by** card when idle; shares data in-process via
`LiveState`. To see it (sideloaded): Android Auto → Settings → tap *Version* to
unlock **Developer settings** → enable **Unknown sources**.

## Build & install
CI builds `drivetime-debug-apk` on each push (Actions → artifact); it's also served at
`https://drivetime.jupiterns.org/dl/drivetime.apk`. Sideload it once — **updates install in
place and keep your settings**, because every build is signed with one committed key
(`app/signing/drivetime-signing.p12`, wired up in `app/build.gradle.kts`) and `versionCode`
tracks the CI run number. *(One-time exception: the first install that moved onto this
stable key needed an uninstall+reinstall; everything after updates over the top.)*

**In-app updates (after that first sideload).** The app now updates itself
([`Updater.kt`](app/src/main/java/org/jupiterns/drivetime/Updater.kt)): on foreground it
reads `GET /dl/version.json` and, if its `versionCode` beats this build's
`BuildConfig.VERSION_CODE`, offers a one-tap **Update** that downloads the APK and hands it
to the system installer. Settings → **UPDATES** has a manual "Check for updates now" and an
auto-check toggle. First install of a build that *has* the updater is still a manual
sideload (bootstrap); every build after that is in-app.

To publish a build so the app can see it, run the drivetime host helper — it grabs the
latest green CI build and writes both `/dl` files (versionCode = the CI run number, so no
manual bookkeeping):

```bash
cd /home/lindsay/drivetime && ./publish-apk.sh          # latest CI build
./publish-apk.sh path/to/app-debug.apk 42 "note"        # or a local APK, explicit code
```

```bash
gradle wrapper --gradle-version 8.7   # first time (no wrapper jar committed)
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
```

## Configure on the phone
Open the app → **Server URL** (`https://drivetime.jupiterns.org`) + your **dashboard
username + password** (the same login you use on the site) → Save → pick **Car
Bluetooth** (and OBD if used) →
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
