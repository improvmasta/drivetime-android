# drivetime

**Every drive, logged automatically. Every mile, yours to keep.**

drivetime turns your phone into an effortless driving journal. Get in the car and it
starts. Arrive and it stops. No buttons, no "did I remember to track that trip?" — just a
complete, accurate history of everywhere you drove, with the miles, times, and routes
already added up for you.

It runs **entirely on your phone**. No account to create, no login, no cloud you're renting
space in. Your driving history lives on your device and answers only to you.

---

## Why you'll want it

**It never forgets a trip.** The one job of a mileage tracker is to actually be running when
you drive — and that's exactly where every other app quietly fails. drivetime is built around
a single rule: *never silently stop logging.* It survives reboots, app updates, dead zones,
and even the aggressive battery-killers on Samsung and Xiaomi phones. If the system kills it,
a watchdog brings it right back.

**It starts itself — in any car.** No pressing "start trip." The moment you begin moving,
drivetime wakes up and starts logging within seconds. Connect to your car stereo over
Bluetooth and it knows instantly you're driving. Borrow a friend's car or a rental? A
hardware motion sensor catches the start anyway. You genuinely never think about it.

**It sips battery when you're not driving.** All day it runs a light, low-power background
trace — enough to notice the next drive, gentle enough that you'll never see it in your
battery stats. Only when you're actually on the road does it ramp up to precise,
second-by-second tracking. You get accuracy when it matters and silence when it doesn't.

**Mark a stop without unlocking your phone.** Pull up to a job site, a client, a delivery —
tap **Mark** right on the lock-screen notification. drivetime drops a pin you can name later.
The whole drive lives on that notification card: current speed, miles so far, elapsed time,
all glanceable at a red light, all legal to see without picking up your phone.

**It reads your engine, too.** Plug in a cheap OBD-II dongle and every trip picks up RPM,
coolant temperature, throttle, fuel airflow, battery voltage, and trouble codes — merged
right onto your route. Turn your driving history into a maintenance log without lifting a
finger.

**It's on your dashboard while you drive.** drivetime speaks **Android Auto** — live speed,
RPM, coolant, and battery on the car screen while logging, and a "leave by" card when you're
parked so you make your next appointment on time.

**It updates itself.** No app-store dance. When a new version is ready, drivetime offers a
one-tap update and keeps all your settings and history intact.

---

## Your data, your rules

drivetime is **local-first and standalone by default.** The full app — maps, stats,
everything — is bundled right inside the download and runs with no server, no signup, and no
internet. Your trips are computed on your phone and stored on your phone.

Want your history backed up, or the same trips on your laptop? Point drivetime at a
**drivetime server** — one you host yourself, or a hosted subscription — and it syncs in the
background. Sync is a feature you opt into, never a fee you're forced to pay to use your own
data. Export everything to a JSON file any time and move to a new phone in seconds.

---

## Get it

1. **Download the APK** — grab it from
   `https://drivetime.jupiterns.org/dl/drivetime.apk` and install it. (You'll approve
   installing from your browser once; every update after that installs itself in place.)
2. **Open drivetime and tap Start.** That's the whole setup for standalone use — it's now
   logging.
3. **Optional — pick your car.** Tap **Car Bluetooth** and choose your stereo so drivetime
   recognizes your car instantly. Add an **OBD dongle** the same way if you want engine data.
4. **Optional — turn on sync.** Enter a **Server URL** and your login to back up and mirror
   your trips.

**One thing worth doing on Samsung/Xiaomi/OnePlus phones:** if drivetime shows a battery
banner, tap it and allow the app to run in the background. Those phones aggressively "sleep"
apps, which is the one thing that can make a tracker miss a drive. drivetime detects when
it's been restricted and walks you straight to the right setting.

---

## For power users & automation

drivetime has a full control surface so it can be driven by **Samsung Modes & Routines,
Tasker, Home Assistant, or App Shortcuts** — force dense logging, drop to eco, stop, or
change any setting from an automation, and read state back via a broadcast. Full key
reference and recipes are in **[`AUTOMATION.md`](AUTOMATION.md)** (also shown in-app under
Settings → Automation).

```bash
am start     -n org.jupiterns.drivetime/.ControlActivity -a org.jupiterns.drivetime.action.MODE_DRIVING
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.STOP
am broadcast -n org.jupiterns.drivetime/.ControlReceiver  -a org.jupiterns.drivetime.action.SET --es key mode --es value eco
```

---

## How it works, under the hood

drivetime chooses one of three tracking tiers automatically:

| Tier | When | Fix rate | Power |
|---|---|---|---|
| **Light** | not driving (default everyday state) | 60s | low |
| **Driving · moving** | in the car, moving | 3s | high accuracy + OBD |
| **Driving · stopped** | in the car at a red light | 20s | high accuracy + OBD |

- **Drive detection is a cascade, not a guess** ([`DriveDetector`](app/src/main/java/org/jupiterns/drivetime/DriveDetector.kt)):
  forced mode → car Bluetooth → OBD connected → motion-onset → sustained GPS speed. A
  connection signal holds *Driving* even at a dead stop, so a red light never drops the tier,
  and a car crawling in traffic is never mislabeled a bike.
- **Second-accurate starts in any car.** A near-zero-battery hardware significant-motion
  trigger fires an instant GPS Doppler check the moment you move, so dense logging begins in
  seconds — even in a car drivetime has never seen. A 60s heartbeat is the backstop.
- **Durable, batched upload (when syncing).** Every fix is written to an on-disk outbox first
  and removed only after the server acknowledges it — a crash, kill, or dead-zone loses
  nothing. Fixes flush on a cadence, when a batch fills, or the instant connectivity returns.
- **Standalone SPA in the APK.** With no server URL set, the app serves the full drivetime web
  app bundled inside itself over a secure local origin, and the phone's own GPS feeds an
  on-device replica — drives and mileage work entirely offline. Set a server URL to opt into
  hosted sync. See drivetime's `STANDALONE.md`.
- **OBD-II via a custom ELM327 layer.** Cheap ELM327 clones that never pair in system
  Bluetooth still work — tap the dongle when the in-app picker scans it (insecure SPP, no
  PIN, the same way Torque reaches them). Engine data needs the ignition on; with the car off
  only battery voltage reads.

## Status

- [x] Foreground `LocationService` with an on-disk offline outbox
- [x] Tracker settings live in the SPA's Settings tabs, driven over the `DrivetimeNative`
  bridge; native flows (permissions, BT pairing, backup pickers, QR pairing, updater) fire in
  place. Runs standalone — no server required
- [x] Tiered tracking + layered drive detection (car-BT / OBD / speed → Light/Driving)
- [x] Tier-aware upload cadence (~10s driving, 45s light, immediate on foreground /
  reconnect / charge)
- [x] OBD-II via custom ELM327 — RPM, speed, load, coolant, throttle, MAF, voltage + DTCs
- [x] Android Auto — live stats when logging; "leave-by" card when idle
- [x] Alerts — 15-min poll of `/api/alerts` → notifications
- [x] In-app updates — self-installing signed builds that keep settings
- [x] Robustness — boot/update restart, self-healing watchdog, verify-before-delete queue,
  OEM-kill detector with manufacturer deep links
- [x] Permissions gate with a two-step fine → background-location flow
- [x] Control API + App Shortcuts + `STATE_CHANGED` broadcast (see [`AUTOMATION.md`](AUTOMATION.md))
- [x] CI — Robolectric unit tests + Android Lint on every push
- [ ] Signed release (Play Store) builds

## Build & develop

CI builds `drivetime-debug-apk` on each push (Actions → artifact); it's also served at
`https://drivetime.jupiterns.org/dl/drivetime.apk`. Every build is signed with one committed
key, so **updates install in place and keep your settings**; `versionCode` tracks the CI run
number.

The in-app updater ([`Updater.kt`](app/src/main/java/org/jupiterns/drivetime/Updater.kt))
reads `GET /dl/version.json` on foreground and offers a one-tap update when a newer build
exists. To publish a build the app can see, run the drivetime host helper:

```bash
cd /home/lindsay/drivetime && ./publish-apk.sh          # latest green CI build
./publish-apk.sh path/to/app-debug.apk 42 "note"        # or a local APK, explicit code
```

Local build (no wrapper jar committed):

```bash
gradle wrapper --gradle-version 8.7   # first time
./gradlew assembleDebug               # app/build/outputs/apk/debug/app-debug.apk
```

> The bundled web in `app/src/main/assets/web/` is a committed build artifact, not source —
> refresh it with drivetime's `./sync-web-to-android.sh`, never by hand. See this repo's
> `CLAUDE.md` and `ROADMAP.md`.

## Tech

Kotlin · min SDK 26 · FusedLocationProvider · OkHttp · coroutines · WorkManager ·
custom ELM327 · `androidx.car.app`.
