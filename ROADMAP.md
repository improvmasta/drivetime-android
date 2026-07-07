# drivetime-android — client roadmap

> The in-car telemetry source. A purpose-built logger that owns the whole pipeline:
> GPS + OBD → offline-safe upload → drivetime (and a filtered mirror to Dawarich),
> with Android Auto and alerts on top.

Working draft to react to and edit — not a commitment. Items are tagged
**[built]**, **[next]**, **[later]**, **[idea]**. Two non-negotiables drive every
decision here: **(1) robustness — it must never silently stop logging**, and
**(2) external control — the app is a controllable *instrument*; your phone's
**Samsung Modes & Routines** (or any Android automation) is the *brain*. Every
action — start/stop, change a setting, read state — is exposed as an API (App
Shortcuts, intents, Quick Settings tiles, state broadcasts) so a Routine can drive
it. Settings stay directly editable in the app.** There is **no in-app "modes"
manager** — your phone's Modes & Routines fill that role.

Master product roadmap lives in `drivetime/ROADMAP.md`; this is the client half.

---

## Status — what's built
- [built] **Phase A** — foreground `LocationService` (FusedLocationProvider) → batched
  upload with an on-disk **offline queue** (`Uploader`); Settings (server, login, interval).
- [built] **Phase B** — custom **ELM327** Bluetooth-SPP client; RPM/load/coolant/throttle/
  MAF/fuel/voltage + DTCs merged onto each fix; dongle picker.
- [built] **Phase C** — **Android Auto** pane (Car App Library): live stats logging / leave-by idle.
- [built] **Phase D** — **AlertWorker** (15-min WorkManager poll of `/api/alerts`) → notifications.
- [built] **Tiered tracking + layered drive detection** — always-on **Light** background logging that ramps to dense **Driving** via `DriveDetector` (car-BT → OBD-connect → speed cascade, *not* activity-recognition). Routine/shortcut can force a tier. *(Supersedes the activity-recognition `TripDetector`, retired because its slow-traffic car/bike guess was the unreliable part.)*
- [built] **Routine control** — `START/STOP/TOGGLE` intents (`ControlActivity`/`ControlReceiver`, `org.jupiterns.drivetime.action.*`).
- [built] **Adaptive sampling** within Driving — dense while moving, `idleIntervalSec` at red lights; tier exit is now the detector's job (drops to Light), not a stationary trip-end.
- [built] **Dawarich mirror** — server forwards a >50m-filtered copy (drivetime side); app is the single GPS source.
- [built] Keep-awake guidance (battery-exemption prompt + Samsung sleeping-apps steps).
- [built] **Background-location two-step flow** — fine-location first, then a rationale screen routing to "Allow all the time" via a separate launcher (Q+).

---

## Pillar 1 — Robustness *(the priority: never silently stop logging)*

A personal logger is only as good as its worst dead-zone, OEM battery-killer, or
permission revocation. Treat "the app quietly stopped" as the #1 bug class.

### 1a. Process survival
- [built] **Restart on boot & update** — `BootReceiver` on `BOOT_COMPLETED` + `MY_PACKAGE_REPLACED` (+ `QUICKBOOT_POWERON`) resumes the service when `loggingEnabled`, and re-arms activity-recognition + the alert poll that reboot clears.
- [built] **Self-healing watchdog** — `Watchdog` (15-min WorkManager) reconciles *should-be-logging* (`loggingEnabled`) vs *is-logging* (`LocationService.isRunning`) and relaunches the service if the OS killed the process; also drains the upload backlog.
- [built] Declared **`foregroundServiceType="location"`** (+ FGS-location permission) and START_STICKY resume path; `loggingEnabled` is now kept across an OS kill (only intentional stops clear it) so STICKY/watchdog can resume. *(Android 14/15 FGS-rules audit still device-only.)*
- [later] Detect process death gaps (no fix for N min while logging) → silent self-restart, then a user warning if it recurs.

### 1b. OEM battery-killer mitigation *(the usual silent-death cause)*
- [built] **Per-OEM deep links** — Samsung / Xiaomi / Huawei / OnePlus / Oppo / Vivo / Asus auto-start / protected / sleeping-apps pages, detected from `Build.MANUFACTURER` with step-by-step advice strings (`OemBatteryLinks.kt`).
- [built] **Killed-since-last-run detector** — the watchdog compares expected vs actual fix cadence; a gap > 20 min when logging was meant to be on records `lastKillDetectedAt`, and the dashboard surfaces the OEM-specific "fix this setting" card until acknowledged.
- [built] Battery-optimization exemption prompt — Settings screen shows current state + a one-tap exemption request + the OEM page deep link.

### 1c. Permissions resilience
- [built] **Background-location two-step flow** — `MainActivity` requests fine first, then a rationale dialog routes to the system "Allow all the time" prompt; `pendingStartAfterGrant` resumes the user's Start once both halves are granted.
- [built] Runtime **permission-revocation handling** — the dashboard polls [Permissions] every tick and degrades to a specific actionable warning ("Location services off", "BT permission needed for your dongle"), no crash and a one-tap re-grant.
- [built] A single **permission/health gate** (`Permissions.snapshot`) — one source of truth shared by the warning banner, the start path, and `Watchdog` (won't try a doomed FGS-start when `isReady` is false).

### 1d. Data integrity & offline queue
- [built] On-disk queue survives kill/crash; writes are now **atomic** (temp-file + rename on trim), **size-capped** (16 MB, drop-oldest), and **ordered**.
- [built] **Verify-before-delete** — only the exact lines acked by the server are dropped (fixes appended mid-POST are preserved); single-flight + process-wide lock so service/watchdog can't double-drain; **exponential backoff + jitter** on failure.
- [built] **Batched flush triggers** — periodic, batch-full (`BATCH_FIXES`), connectivity-regained (`NetworkCallback`), **tier-aware cadence** (~10s `drivingUploadIntervalSec` while DRIVING for near-real-time position; `uploadIntervalSec` ≈45s while LIGHT), **app-foreground** (Main.onResume), and **charge-connected** (`ACTION_POWER_CONNECTED` runtime receiver).
- [built] Idempotent uploads (server `INSERT OR IGNORE`) — keep dedup keys stable (epoch + lat/lon).

### 1e. Connectivity & sensors
- [built] `NetworkCallback`-driven flush (regained connectivity drains the backlog immediately); OkHttp connect/write timeouts. → [next] cancellation + optional **Wi-Fi-only backfill** of the backlog (live fixes always go).
- [next] **Low-accuracy / no-fix handling** — flag or drop poor fixes; detect "location services off" and notify.
- [next] **OBD reconnect** — backoff retry on dongle drop; GPS continues regardless (already true); never block the main thread.

### 1f. Crash resilience & state
- [next] Global uncaught-exception handler → persist a local crash log, then restart the service.
- [next] **One state machine** for logging — reconcile manual + auto-trip + routine commands so they can't fight (e.g. routine STOP vs activity-recognition START).
- [later] Optional self-hosted crash/heartbeat ping (no Google) so you know remotely it's alive.

### 1g. Security & hardening
- [next] Login **credentials** in **EncryptedSharedPreferences / Keystore**; never logged.
- [next] **Harden the exported intent surface** — gate state-changing actions behind an optional shared token; keep START/STOP open (low-risk).
- [later] Optional TLS cert-pinning for the ingest host.

### 1h. CI as the safety net *(no device on the dev host)*
- [built] **Lint + unit tests** — JVM JUnit + Robolectric tests for `ControlParse`, `Control.SET_KEYS`, `DriveDetector` thresholds, `Uploader` queue durability, `Settings` defaults, and `AutomationHelp` ↔ docs sync. CI runs `testDebugUnitTest` + `lintDebug` before the APK build.
- [next] A **pre-release checklist** (permissions, FGS, boot, queue, OEM battery) since validation is sideload-only.

---

## Pillar 2 — External control API *(driven by Samsung Modes & Routines)* — the centerpiece

The app is **headless-controllable**. Your phone's **Samsung Modes & Routines**
(e.g. the *Driving* mode, or a routine on car-Bluetooth / place / time) is the brain
that decides when and how to log; it drives the app through a clean, documented API.
There is no in-app mode manager — the "modes" are the ones you already build in
Samsung Modes & Routines. The app's job is to expose **every action as something a
Routine can invoke**, and to **report state back** so a Routine can react.

**Inbound — what a Routine can do to the app:**
- [built] `START` / `STOP` / `TOGGLE` logging (`ControlReceiver`, `org.jupiterns.drivetime.action.*`).
- [built] `SET key=…  value=…` covers **every** advertised key — `mode`, the four interval cadences (`interval_sec`, `idle_interval_sec`, `light_interval_sec`, `upload_interval_sec`, `driving_upload_interval_sec`), `drive_by_speed`, `stationary_stop_min`, `auto_trip`, `alerts_enabled` (see `Control.SET_KEYS`). One verb — the Routine composes the "mode."
- [built] `QUERY` → emits a [StateBroadcaster] STATE_CHANGED back so the routine can read state without polling.

**How Routines actually invoke it** (so it works with Samsung's real capabilities):
- [built] **App Shortcuts** — static Start/Stop/Toggle shortcuts (`res/xml/shortcuts.xml`, published via the launcher activity's `android.app.shortcuts` meta-data) each launch the no-UI `ControlActivity`. This is the Samsung *Open app → shortcut* hook that needs **no extra apps**. → [next] dynamic/pinnable shortcuts for parameterized presets ("High-accuracy", "Battery-saver") once `SET` lands.
- [next] **Custom intents** to `ControlReceiver` — for **Good Lock → RoutinePlus**, Tasker, MacroDroid, or Home Assistant, which can send arbitrary broadcasts/extras.
- [next] **Quick Settings tiles** — manual one-tap from the shade; also flippable by some automation.

**Outbound — so a Routine/Mode can react to the app:**
- [built] `org.jupiterns.drivetime.event.STATE_CHANGED` broadcast (`logging`, `tracking_mode`, `tier`, `reason`, `queue_depth`, `obd_connected`, `last_fix_at`, `last_success_at`, `last_command_source`, `source`, `ts`) — emitted on tier flips, OBD link changes, service lifecycle, and every Control action.

**Guardrails:**
- [built] Every state-changing entry point sets the **same flat settings** the Settings screen edits — `SettingsExport` JSON keys = `Control.SET_KEYS`, so the API never introduces a parallel config the UI can't show.
- [built] **Security**: optional shared token (`controlToken` setting) gates `SET` / `QUERY`; `START` / `STOP` / `TOGGLE` / mode actions stay open per ROADMAP. See Pillar 1g.
- [built] Ship recipes in **`AUTOMATION.md`** — Samsung Modes & Routines, Tasker, Home Assistant, plus the cheat-sheet appears verbatim in Settings → Automation.

---

## Pillar 3 — Entry-point matrix *(the concrete surfaces of the Pillar-2 API)*

| Surface | Who drives it | What it does |
|---|---|---|
| [built] **App Shortcuts** (`res/xml/shortcuts.xml`) | **Samsung Modes & Routines (native)** | *Open app → shortcut* runs a no-UI control action (Start/Stop/Toggle) — needs no extra apps |
| [built→next] **Broadcast intents** (`ControlReceiver`) | Good Lock RoutinePlus / Tasker / MacroDroid / HA | full `START/STOP/TOGGLE/SET/QUERY` |
| [next] **Quick Settings tiles** (`TileService`) | you, manually | logging toggle + quick setting from the shade |
| [next] **State broadcast out** (`STATE_CHANGED`) | Routines / Modes / HA | react to logging/queue/OBD state |
| [later] **Google Assistant** (App Actions) | voice | "start drivetime" |
| [later] **Android Auto** | in-car | logging toggle on the pane |
| [later] **Home Assistant** | HA | two-way: state out, commands in |

---

## Pillar 4 — Built-in auto start/stop *(thin fallback — Modes & Routines are the brain)*

Deciding *when* to log is mostly your phone's Samsung Modes & Routines (Pillar 2).
The app keeps only lightweight, no-setup triggers so it still works before you wire
a single routine:
- [built] **Car Bluetooth** connect/disconnect — the #1 built-in driving signal (deterministic; pick the car device in Settings), layered with **OBD-connect** and a **sustained-speed backstop** in `DriveDetector`. Any one promotes to Driving; connection signals hold Driving even at a dead stop.
- ~~Activity-recognition IN_VEHICLE~~ — **retired**: its low-speed car/bike/walk guess was exactly the misclassification we're avoiding. Code remains but is no longer armed.
- [idea] Geofence / time / charging triggers — only if you'd prefer them in-app; otherwise skip, since the Pillar-2 API already lets a Samsung Routine do exactly these.

---

## Pillar 5 — Settings UX *(the primary surface — everything the API touches lives here)*
- [built] **One native Tracker screen** (`LoggerActivity`) — live status + all setup in a single scroll, reached in one tap from the shell's status pill (the old separate `SettingsActivity` is folded in). Ordered by how often it's touched: Status · Access & battery · Devices · **Sync (optional)** · Updates · **Advanced** (Drive-detection · Timing · Automation · Notifications, collapsed) · Backup — per-row help, a Test connection button, edits auto-save on leave. Every routine-controllable knob (`Control.SET_KEYS`) is editable. Sync is genuinely optional: tracking runs standalone with no server (STANDALONE.md).
- [built] **Automation cheat-sheet** (`AutomationHelp`) — shown verbatim in Settings → Automation; a unit test (`AutomationHelpTest`) keeps it in sync with `Control.SET_KEYS` and `StateBroadcaster.ACTION_STATE_CHANGED`.
- [built] **Import / Export settings as JSON** (`SettingsExport`) — system file pickers; keys match the routine API so an exported file doubles as a routine preset.
- [built] All previously defaults-only knobs (`idleIntervalSec`, `lightIntervalSec`, `uploadIntervalSec`, `drivingUploadIntervalSec`, `stationaryStopMin`, `driveBySpeed`, `controlToken`) now surface as editable rows.

## Pillar 6 — Observability / diagnostics
- [built] **Status dashboard**: last fix time + accuracy, queue depth, OBD link, upload health (last success / retry countdown / auth fail), permission/battery health surfaced as a single actionable warning banner, and **what last changed state** via `Settings.lastCommandSource` carried in the STATE_CHANGED broadcast.
- [built] In-app **log viewer** (`LogActivity` / `EventLog`) — persisted, shareable history.
- [later] No-fix watchdog notification while logging.

## Pillar 7 — In-car / Assistant / HA polish
- [later] Android Auto: live elapsed-vs-typical, MPG, logging toggle, spoken alerts.
- [later] Assistant voice control; HA two-way hooks.

---

## Phasing
1. **Robustness core** *(Pillar 1a–1d)* — boot restart, self-healing watchdog, OEM battery deep-links, background-permission flow, queue verify-before-delete + backoff. *Earn "never silently stops" first.*
2. **Control API** *(Pillar 2/3)* — App Shortcuts (the Samsung-native hook), `SET`/`QUERY` on `ControlReceiver`, `STATE_CHANGED` out, Quick Settings tile, + `AUTOMATION.md` recipes. *This is what "modes" means here.*
3. **Built-in auto start/stop** *(Pillar 4: Car-BT)* as the no-routine fallback.
4. **Settings redesign** + import/export + the automation cheat-sheet *(Pillar 5)*.
5. **Observability** *(Pillar 6)* + remaining robustness (crash handler, security hardening, CI tests).
6. **In-car / Assistant / HA polish** *(Pillar 7)*.

---

## Open questions
1. ~~In-app modes~~ — **Decided: no in-app mode/preset manager. "Modes" = your Samsung Modes & Routines, which drive the app through the Pillar-2 control API. Settings stay primary and directly editable in-app.**
2. ~~Trigger engine home~~ — **Decided: the brain is Samsung Modes & Routines; the app keeps only a thin built-in auto start/stop (activity-recognition + optional car-BT) as a no-setup fallback.**
3. **Intent security** — leave control intents open, or require a shared token for state-changers?
4. **Crash/heartbeat reporting** — local-only, or a self-hosted ping so you know remotely it's alive?
5. **Wi-Fi-only backfill** — worth it, or always upload on cellular?
