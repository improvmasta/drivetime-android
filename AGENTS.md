# drivetime-android — Agent Instructions

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that also
hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what the user
actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an *optional*
support server.

`CLAUDE.md` carries the same instructions; keep the two in sync.

## Read first

- Sibling repo `/home/lindsay/drivetime`: `NATIVE_APP.md` (shell architecture),
  `LOCAL_FIRST.md` (why the app bundles its own web snapshot + offline model), `AUTH.md`
  (device-token pairing) — before touching the bridge, auth, or sync.
- `README.md` (the user-facing pitch), `AUTOMATION.md` (the routine/shortcut control surface),
  `EMULATOR.md` (live UI reload + running the app in an emulator).

## Working style

- Be concise; make focused changes; keep secrets out of the repo.
- **Do not ship automatically — only on explicit instruction.**

## Three non-negotiables

1. **Robustness** — it must never silently stop logging. "The app quietly stopped" is the #1
   bug class; boot restart, the watchdog, OEM battery deep-links, and the kill-detector all
   exist to serve this.
2. **Standalone** — the phone is the product. The server is optional support (backup + heavy
   map enrichment). Nothing user-facing may *require* one.
3. **External control** — the app is a controllable instrument. Every action is reachable from
   a shortcut, intent, or broadcast, so Samsung Modes & Routines (or Tasker / HA) can be the
   brain. There is deliberately **no in-app "modes" manager**.

## How it's wired

**The logger** (`LocationService`) runs two tiers: an always-on sparse **Light** trace that
ramps to dense **Driving** via `DriveDetector` — car-Bluetooth → OBD-connect → sustained-speed
cascade, plus a significant-motion **onset** path that wakes a parked phone fast. Within
Driving it samples adaptively (dense while moving, `idleIntervalSec` at red lights). Tier exit
is the detector's job, not a stationary trip-end — but the *connection* signals are **bounded by
a `parked` latch**, and that bound is load-bearing. An OBD-II port is permanently powered and a
head unit can sit on accessory power, so `obdConnected`/`carConnected` stay true with the ignition
off; when they held DRIVING unconditionally, a parked car pinned the dense tier for hours (dense
GPS in a parking lot, phantom drives out of GPS drift, and an app insisting you were driving while
you sat still). Connection signals are for *starting fast*, not for deciding you never stopped.

Three things the detector keeps deliberately separate — collapsing them is what caused that bug:
**`isMoving`** (are the wheels turning right now — the drive's green/red signal light, what the UI
shows), **`tier`** (how fast to sample; holds through a red light, ends at `parked`), and the
**drive session** (`markDriveStart`, which still ends when the tier leaves DRIVING). `STOP_MS` is
one 5-minute constant for every latch, the same 5 minutes `segment.js` calls a park, so the live
app, the drive log and segmentation agree on what a stop is. A plausible OBD rpm (`engineRunning`,
`ObdSession.engineRunning` — a band, **not** `rpm > 0`, which a garbled frame satisfies) is the only
thing that extends the hold: idling with the engine on is not parked. **That extension is itself
bounded** (`ENGINE_HOLD_MAX_MS`, 30 min), and the bound is the point — OBD is *additive*, never
authoritative. An OBD-II port stays powered with the ignition off, so a cheap clone keeps its socket
and can keep serving a stale nonzero rpm; unbounded, one such frame every five minutes resets
`parkedSince` forever, so the car never parks, the tier never leaves DRIVING, and the OBD loop
(which exits on `!isParked`) never lets go either — a closed loop with no exit, which is the very
bug `parked` was added to kill. The stationary clock behind the ceiling is driven by motion alone,
so a *flickering* dongle cannot wind it back. GPS decides whether the wheels turned; OBD only ever
adds to what GPS already knows. *(The old
activity-recognition `TripDetector` is retired — opt-in behind `auto_trip`, not armed by default.)*

**Fixes are durable.** `Uploader` writes an on-disk queue that is atomic, size-capped (16 MB,
drop-oldest), and ordered; only lines the server actually acked are deleted; failures back off
exponentially. Flushes fire on a tier-aware cadence (~10 s driving, ~45 s light), on batch-full,
on regained connectivity, on app-foreground, and on charge-connected.

**The SPA is the app.** `WebViewActivity` serves the bundled snapshot over `WebViewAssetLoader`
at `https://appassets.androidplatform.net/assets/web/` — a secure origin, so the service worker
and IndexedDB replica work with no server. Settings are the SPA's own tabs (General / Tracking /
Sync & Backup / Advanced) over the `DrivetimeNative` bridge; native flows (permissions, BT/OBD
pairing, QR pairing, backup pickers) fire in place from their tab.

**Everything else, briefly:** `Notify` is the single door for every notification except the
ongoing drive card (drive-complete, gas-stop, weekly digest, check-engine, tracking-interrupted
— one channel per kind, each with a toggle, deep link, and retraction).
`BackupStore`/`BackupWorker`/`DriveClient` take scheduled full-data snapshots to a SAF folder
and/or the user's own Google Drive.
`Control` + `ControlReceiver` + `StateBroadcaster` are the routine API (`AUTOMATION.md`).
**Android Auto was removed** before the first Play upload — Play rejects a manifest carrying
both the `automotive` feature and the Auto meta-data, and an Auto app draws review scrutiny we
can't verify without a head unit. Don't re-add it casually; git has it.
`Permissions.snapshot`/`checklist` is the one gate every "can we log right now?" question goes
through.

**`Health` is the tracker's liveness ledger — silence proves nothing.** The only thing that writes
a fix is the location callback, so a parked car and a killed tracker look identical (no fixes). The
app used to read that silence as failure and accused itself of dying every time the car sat parked.
`Health` fixes it, but **not** with a row every 60s — a `delay` doesn't tick in deep sleep, so a
missing row would still mean *dead OR asleep*. The beat is a continuously-updated **proof of life**
(`Settings.lifeBeatAt`, stamped from a fix, an upload, or a `Watchdog` pass), and the unit of
identity is the **process**: anything `startLife` finds in prefs during `onCreate` describes a
predecessor, so one that never ran `onDestroy` was *killed*, and the time since its last beat is
downtime stated as fact. Outages become `down` rows in `web_health.jsonl` with a cause
(`killed`/`system` are faults; `stop`/`reboot` are not), plus `cond` rows for transitions in what
the tracker needs (location off, permission revoked, power saver). The SPA drains them over
`pullHealth`. Never read a gap in the fixes as a failure — read the ledger.

## The web assets are generated — never hand-edit them

`app/src/main/assets/web/` is a **committed build artifact**, not source. Its source is
`drivetime/frontend/`. Refresh it with
`cd /home/lindsay/drivetime && ./sync-web-to-android.sh`. Editing `assets/web/` directly is
always wrong — the next sync overwrites it and the website and phone drift apart.

## The loop: emulator first, Play on command

**Every change goes to the emulator first; shipping to Play happens only when told** (setup in
`EMULATOR.md`). Three distinct steps:

1. **See it on the emulator (always, first — no ship, no commit).** SPA/UI change: Vite dev
   server + `./dev.sh --dev` once, then edits hot-reload live. Kotlin/native change: `./dev.sh`
   builds + installs the bundled APK. **Caveat:** `--dev` serves the SPA from the server origin,
   so the app runs as the web dashboard (server-bound, login-walled) — check anything
   standalone/offline/pairing/data on a plain `./dev.sh` build instead.
2. **Bundle it (before shipping, not before testing).** A `drivetime/frontend/` change becomes
   permanent in the app only when `drivetime`'s `./sync-web-to-android.sh` refreshes
   `app/src/main/assets/web/` here. A dirty `assets/web/` is the correct, expected state. **Do
   not** run `ship.sh` just to clean it up.
3. **Ship to Play (only on explicit instruction).** Both repos, ending on Play's internal track:

   ```bash
   cd /home/lindsay/drivetime && bash ship.sh "msg"        # 1. drivetime: commit + push
   ./sync-web-to-android.sh                                # 2. re-sync if changed since
   cd ../drivetime-android && bash ship.sh "msg"           # 3. local build+test gate, then push
   ```

   Step 3 builds + runs the unit tests locally as a gate (toolchain on this host — EMULATOR.md)
   before pushing; CI uploads the AAB to Play's **internal track**, the only channel. No APK
   publish and no `/dl` — the in-app updater is deleted (Play forbids self-updating), so
   `publish-apk.sh` and the CI GitHub release went too. Shipping `drivetime` alone leaves the
   phone on the old snapshot — the #1 way a "fixed" bug survives a ship. Docs/CI-only commits
   that compile nothing can skip the gate with `SHIP_SKIP_GATE=1`.

Verify on the phone, not just in a browser: touch targets, the hardware BACK button (the shell
calls `window.__dtHandleBack()`), offline / no-server behavior, and the `DrivetimeNative`
bridge. Where phone and desktop pull apart, the phone wins.

## Distribution — two channels, one source tree

`github` and `play` are build flavors (`app/build.gradle.kts`):

- **`github`** (sideload) — `assembleGithubDebug` → APK, uploaded as a CI **workflow artifact**.
  A build you download from a run and install by hand; nobody is served updates from it. (No
  longer a GitHub *release* — that carried a `version.json` for the deleted updater.)
- **`play`** — `bundlePlayRelease` → AAB, uploaded to internal testing by CI. **Every real
  install is here.**

The flavors now differ only by their Drive OAuth client. **There is no in-app updater, and
adding one back gets the app taken down:** `Updater.kt`, `REQUEST_INSTALL_PACKAGES` and the
`UPDATER_ENABLED` flag are deleted (hardening 3.1), and so is the whole distribution tail — the
server `/dl` route, `publish-apk.sh`, `version.json`, the CI GitHub release, the
`updates_enabled`/`updates_supported` bridge keys, `checkForUpdate()`, and the SPA's
check-for-updates card. Play's Device and Network Abuse policy forbids an app updating itself
outside Play. A stale cached SPA that still calls `checkForUpdate()` is harmless — `native.js`
probes `typeof` first, so a missing bridge method is a silent no-op. Do not re-add any of it.

Flavors rename every task: `testGithubDebugUnitTest`, `lintGithubDebug`, `assembleGithubDebug`.

**Signing: the channels must not share a key.** `app/signing/` is committed (password and
all) and has been public, so treat it as burned: it is fine as the sideload key and as Play's
*upload* key, but Play must **generate its own app signing key** (Google-held, never exposed).
Consequence: a Play install and a sideload install have **different signatures** and cannot
upgrade into each other — moving a phone between channels is uninstall → reinstall, which
**wipes every drive on the device**. Back up (Settings → Sync & Backup) first, always. Also
the Drive OAuth client is **per channel**: it is bound to a signing SHA-1, so `play` and
`github` each carry their own client id, set with the manifest redirect scheme it must match by
`driveClient(...)` in `app/build.gradle.kts` (`drivetime/BACKUP.md`). Registering a second
client in the Cloud console without wiring its id into the flavor changes nothing — Drive
backup still silently dies for Play installs.

We ship targetSdk 35 with `windowOptOutEdgeToEdgeEnforcement`; **Play requires 36 from
2026-08-31**, and at 36 that opt-out is ignored — the roots need real inset padding first.

## There IS a local compiler now — but never render the app yourself

**A JDK + Android SDK live on the dev host** (`~/.local/lib/jdk-17`, `~/.local/lib/gradle-8.11.1`,
`~/Android/sdk`; `local.properties` points at the SDK). Build + test locally:

```bash
export JAVA_HOME=~/.local/lib/jdk-17 ANDROID_HOME=~/Android/sdk
~/.local/lib/gradle-8.11.1/bin/gradle assembleGithubDebug testGithubDebugUnitTest
```

`ship.sh` runs exactly this as a gate before pushing, so unverified Kotlin no longer leaves the
box — and the two traps that used to cost a CI round-trip (`*/` closing a comment early, a bare
`return` in a `Boolean` function) a local `gradle` catches for free.

**Still do not try to *render* the app.** There is no emulator or device *on this host* — the
emulator runs on Lindsay's Windows PC (EMULATOR.md). The UI only exists as a real app: the
WebView's `DrivetimeNative` bridge makes the native Settings/HUD render at all, so serving
`assets/web` in a headless browser shows a surface the user never sees. **Lindsay looks at the
phone/emulator; you verify by building, testing, and reading.** When he wants a change live, the
path is EMULATOR.md (driven from Windows), not a render you produce here.

CI (`.github/workflows/android.yml`) has two jobs. `compile` (Kotlin, app **and** test sources,
~2 min) runs on **every branch**. `build` — unit tests, lint, the APK, the Play AAB, the Play
internal upload — runs only on **main, PRs and `workflow_dispatch`**. A branch push only
*compiles* in CI; `gh workflow run android.yml --ref <branch>` runs the tests remotely, though a
local `testGithubDebugUnitTest` is now faster.

**A `workflow_dispatch` run publishes nothing, so it stays safe under "don't ship."** The Play
staging and Play upload are gated on `github.event_name == 'push' && github.ref ==
'refs/heads/main'` — a branch run builds the APK and AAB as CI artifacts and stops there.

There is no device on the dev host, so the unit tests *are* the safety net. The ones that exist
because the thing they cover fails **silently**, which is this app's whole bug class:

- `WatchdogTest` / `HealthLedgerTest` / `LocationServiceTest` / `DriveSessionTest` — the
  silent-stop spine. Is a gap an outage or a parked car; is a dead service a kill, a reboot, or the
  user switching it off; does the OFF path really clear `loggingEnabled` (and does the
  `startForeground` degrade really *not*); is a surviving drive-start mark the same drive.
- `TierReconcilerTest` — the tier race. It pins the *invariant*, not an outcome, because the
  outcome of a data race is usually "fine".
- `BridgeSerializerTest` — contract #4. A dropped bridge key throws nothing and logs nothing; a
  settings row just quietly shows its default forever.
- `DriveEndProcessorTest` — the gas-stop heuristic, whose two distance checks read alike and mean
  opposite things.
- `AutomationHelpTest` holds the in-app cheat-sheet to `Control.SET_KEYS`; `Uploader`,
  `DriveDetector`, `ControlParse`, `JsonlRing`, `BackupStore` and `ObdSession` have real coverage.

Time in the spine goes through `Clock` (wall clock + time-since-boot), so tests can move it instead
of waiting twenty minutes. Everything outside the spine still calls `System.currentTimeMillis()`
directly, on purpose — a clock no test needs to move is not worth a seam.

`ship.sh` here is leaner than the generic `/home/lindsay/scripts/ship.sh` (no ship log to
stamp), but it now **gates on a local `assembleGithubDebug` + `testGithubDebugUnitTest`** before
pushing. `dev.sh` builds + installs onto the Windows emulator over LAN ADB (EMULATOR.md); a
UI-only change needs neither — the Vite dev server hot-reloads it.
`SHIP_SKIP_PUBLISH=1` commits and pushes without waiting for the APK.

## Next up

- **Quick Settings tile** (`TileService`) — the last unbuilt entry-point in the control API.
- **OBD reconnect** — backoff retry on a dongle drop (GPS already continues regardless).
- **"Alive but blind" alarm** — what `Health` was built for, still unbuilt: heartbeat + engine
  running + no fixes for minutes = a drive is being lost right now. `cond` rows are already being
  recorded to tune it against, so the alarm isn't wired on a guess.
- **Low-accuracy / no-fix handling** — flag or drop poor fixes. ("Location services off" is done:
  `Health` records it and the Drives timeline names it as a gap's cause.)
- **~~One logging state machine~~ — done (hardening 5.1).** `TierReconciler` is the single
  thread tier state lives on; manual, detector and routine commands all `submit` to it and are
  applied serially, in order. Fixes are delivered straight onto that thread, so the hot path is
  not a hop — it *is* the thread. The invariant ("nothing mutates tier fields directly") is
  enforced by `requireOwnThread`, which **warns rather than throws** in production: an app whose
  purpose is to not stop logging must not acquire a new way to die. If you see
  "touched tier state on '<thread>'" in the Activity log, something is racing again.
- **Credentials in Keystore** — the secrets (`Settings.SECRET_KEYS`) are still plaintext
  `SharedPreferences` at rest. Hardening 3.4 moved them into their own file
  (`drivetime_secrets.xml`) so the backup rules can exclude them — Android can exclude a prefs
  **file** but not a **key** — keeping tokens out of Google's cloud backup while the ordinary
  settings still restore, and letting a device-to-device transfer carry everything. The two rule
  files (`data_extraction_rules.xml` API 31+, `backup_rules.xml` ≤ 30) must agree. A new secret
  goes in `SECRET_KEYS` *and* gets its accessor pointed at `secrets`; `SecretsMigrationTest`
  catches half a job.
- **A pre-release checklist** — permissions, FGS, boot, queue, OEM battery. The Windows emulator
  (EMULATOR.md) covers much of this now; the checklist still backs OEM-battery and real-device
  behaviours an emulator can't reproduce.
