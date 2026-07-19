# drivetime-android — Claude Context

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that also
hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what the user
actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an *optional*
support server.

Sibling repo: `/home/lindsay/drivetime` (SPA + FastAPI backend). Read its `NATIVE_APP.md`
(shell architecture), `LOCAL_FIRST.md` (why the app bundles its own web snapshot + the
offline model), and `AUTH.md` (device-token pairing) before touching the bridge, auth, or
sync. (`STANDALONE.md` is now just the sealed-rebuild-window invariant + open items.)

## Behavior

- Be concise and make focused changes; prefer editing existing files.
- Keep secrets out of the repo.
- **Do not ship automatically — only on explicit instruction.**

## Three non-negotiables

1. **Robustness** — it must never silently stop logging. "The app quietly stopped" is the #1
   bug class; boot restart, the watchdog, OEM battery deep-links, and the kill-detector all
   exist to serve this.
2. **Standalone** — the phone is the product. The server is optional support (backup + heavy
   map enrichment). Nothing user-facing may *require* one.
3. **External control** — the app is a controllable instrument, not a walled garden. Every
   action is reachable from a shortcut, intent, or broadcast, so Samsung Modes & Routines
   (or Tasker / HA) can be the brain. There is deliberately **no in-app "modes" manager**.

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

**Presence is evidence; absence is not** — the rule the cascade turns on. A connection signal
proves you are in the car, so it may *hold* a drive open (bounded, above); a signal going *away*
proves nothing, because Bluetooth drops mid-drive all the time, so **nothing ends a drive because a
signal vanished**. Only affirmative evidence ends one, and there are two kinds: `parked` **by
dwell** (the vehicle demonstrably has not gone anywhere) and `parked` **by egress** (you
demonstrably got out and walked away). Either ends it; neither is required.

**The dwell is POSITIONAL, and that is not a detail.** It is a port of `segment.js`'s dual anchors
— a tight jitter-proof 40 m (`PARK_ANCHOR_M`, *no* speed condition) plus a wider 100 m drift anchor
that *does* require stopped speed, either dwelling 5 min. It used to be four near-identical
`mps < EXIT_MPS` timers, and `EXIT_MPS` is 1.3 m/s — **walking pace**. Park at a shop and walk
around inside and every one of those timers reset on every step, so the tier never left DRIVING
while the segmenter, reading the same fixes positionally, had long since ended the drive. The docs
claimed the two agreed; they shared the five *minutes* and nothing else. A speed reading cannot
block a park it knows nothing about.

**Egress is `confirmOnset` asked at the other end** (`confirmEgress`). The app always knew how to
tell a walk from a crawl — a car is *smooth* (low accel RMS), a body on foot is *bouncy* — but only
ever asked on the way in. Four conjuncts, all required, ~90 s: on-foot speed band + no car BT + no
`engineRunning` + no vehicle-scale displacement, then one accelerometer sample settles it. The
conjunction is the safety: no single flaky signal can end a real drive. Note what BT is doing —
never ending a drive, only *vetoing* the end — and its ~10 m range is the feature (walk into a shop
and it drops; stand at the pump and it holds). `obdConnected` is deliberately **not** consulted;
`engineRunning` is the honest half. Known gap: walking away *in a straight line* keeps relocating,
so it rides on the end of the drive until you stand still and the dwell rule ends it.

Three things the detector keeps deliberately separate — collapsing them is what caused that bug:
**`isMoving`** (are the wheels turning right now — the drive's green/red signal light, what the UI
shows), **`tier`** (how fast to sample; holds through a red light, ends at `parked`), and the
**drive session** (`markDriveStart`, which still ends when the tier leaves DRIVING). `STOP_MS` is
one 5-minute constant, the same 5 minutes `segment.js` calls a park — and now that the dwell
measures it the same *way*, the live app, the drive log and segmentation genuinely agree on what a
stop is. A plausible OBD rpm (`engineRunning`,
`ObdSession.engineRunning` — a band, **not** `rpm > 0`, which a garbled frame satisfies) is the only
thing that extends the hold: idling with the engine on is not parked. **That extension is itself
bounded** (`ENGINE_HOLD_MAX_MS`, 30 min), and the bound is the point — OBD is *additive*, never
authoritative. An OBD-II port stays powered with the ignition off, so a cheap clone keeps its socket
and can keep serving a stale nonzero rpm; unbounded, one such frame every five minutes resets
the stop clock forever, so the car never parks, the tier never leaves DRIVING, and the OBD loop
(which exits on `!isParked`) never lets go either — a closed loop with no exit, which is the very
bug `parked` was added to kill. The dwell behind the ceiling is driven by POSITION alone, so a
*flickering* dongle cannot wind it back — it gets no vote on where the car is. GPS decides whether the wheels turned; OBD only ever
adds to what GPS already knows. *(The old
activity-recognition `TripDetector` is retired — its slow-traffic car/bike guess was the unreliable
part. The code remains, opt-in behind `auto_trip`, and is not armed by default.)*

**The accel extractor + burst tiers (Insights P3).** While the DRIVING tier is active — and
only then — `AccelExtractor` consumes a batched ~50 Hz accelerometer at the edge (per-second
peaks of gravity-removed magnitude, O(1) per sample, reconciler-thread confined like all tier
state). **GPS speed deltas decide events; accel only refines** timestamp/magnitude — a phone
knocked off its mount is not a hard brake, so accel alone never mints one. Hard brakes /
hard launches land in `web_events.jsonl` (`WebEventBuffer`, drained by the SPA over
`pullEvents` into `drive_stats` at seal — raw samples never cross the bridge). An event, or
an accel spike at standstill (the car is launching before 3 s GPS notices), triggers a 25 s
burst of 1 s HIGH_ACCURACY fixes (`burstUntil` in `adaptSampling`), then the normal cadence
restores. Base GPS rate and retention are untouched — density is transient and on-trigger.

**Fixes are durable.** `Uploader` writes an on-disk queue that is atomic, size-capped (16 MB,
drop-oldest), and ordered; only lines the server actually acked are deleted; failures back off
exponentially. Flushes fire on a tier-aware cadence (~10 s driving, ~45 s light), on batch-full,
on regained connectivity, on app-foreground, and on charge-connected — and idle to 300 s on a
no-server install, where nothing drains the queue anyway.

**The SPA is the app.** `WebViewActivity` serves the bundled snapshot over `WebViewAssetLoader`
at `https://appassets.androidplatform.net/assets/web/` — a secure origin, so the service worker
and IndexedDB replica work with no server. The phone segments its own GPS into drives; the
server is support. Settings are the SPA's own tabs (General / Tracking / Sync & Backup /
Advanced) reading and writing over the `DrivetimeNative` bridge; the genuinely-native flows
(permission prompts, BT/OBD pairing, QR pairing scan, backup pickers, Test connection) fire in
place from their tab. The old native `LoggerActivity`/`SettingsActivity` screens are gone.

**Everything else, briefly:** `Notify` is the single door for every notification except the
ongoing drive card — drive-complete, gas-stop, weekly digest, check-engine, tracking-interrupted
— one OS channel per kind, each with a toggle, a deep link, and honest retraction (see
`drivetime/NOTIFICATIONS.md`). `BackupStore`/`BackupWorker`/`DriveClient` take scheduled
full-data snapshots to a SAF folder and/or the user's own Google Drive (`drivetime/BACKUP.md`).
`Control` + `ControlReceiver` + `StateBroadcaster` are the routine API (`AUTOMATION.md`).
**Android Auto was removed** (2026-07-13, commit before the first Play upload): Play refuses a
manifest declaring both the `android.hardware.type.automotive` feature (Automotive OS) and the
`com.google.android.gms.car.application` meta-data (Auto). Dropping the stray automotive line
would have kept Auto, but a declared `CarAppService` makes this an Auto app in review's eyes
and we have no head unit to verify it against — so `car/`, the meta-data, `automotive_app_desc`,
and both `androidx.car.app` artifacts came out. Restore from git if it earns its keep later; do
**not** re-add the automotive `uses-feature` line with it. `Permissions.snapshot`/`checklist` is the one gate
every "can we log right now?" question goes through — the warning banner, the wizard, the start
path, and `Watchdog` all read it, so they can never disagree.

**`Health` is the tracker's liveness ledger, and it exists because silence proves nothing.** The
only thing that writes a GPS fix is the location callback, so a parked car and a tracker the OEM
battery manager killed produce the same thing: no fixes. Four features were once built on a comment
claiming "the logger heartbeats every ~60s even parked, so silence means it died" — nothing
enforced that, and the app accused itself of dying every time the car sat in a driveway. `Health`
makes it true, but **not** by writing a row every 60s: a `delay` doesn't tick in deep sleep, so a
missing row would still mean *dead OR asleep*. Instead the beat is a continuously-updated **proof
of life** (`Settings.lifeBeatAt`, stamped from a fix, an upload, or a `Watchdog` pass), and the
unit of identity is the **process** — anything `startLife` finds in prefs during `onCreate` can
only describe a predecessor, so a predecessor that never ran `onDestroy` was *killed*, and the
interval since its last beat is downtime we can state as fact. A late beat still proves continuity,
because the same process wrote both ends of it. Outages land in `web_health.jsonl` as `down` rows
(with a cause: `killed`/`system` are faults, `stop`/`reboot` are not) plus `cond` rows for
transitions in what the tracker needs (location off, permission revoked, power saver). The SPA
drains them over `pullHealth` (`drivetime/frontend/src/lib/health.js`). Nothing may read a gap in
the fixes as a failure — read the ledger.

## The web assets are generated — never hand-edit them

`app/src/main/assets/web/` is a **committed build artifact**, not source. Its source lives in
`drivetime/frontend/`. To refresh it:

```bash
cd /home/lindsay/drivetime && ./sync-web-to-android.sh
```

Editing files under `assets/web/` directly is always wrong — the next sync silently overwrites
it, and the website and the phone drift apart.

## The loop: emulator first, Play on command

**Every change goes to the emulator first; shipping to Play happens only when told.** `EMULATOR.md`
has the setup. The two steps are different actions with different triggers:

1. **See it on the emulator (always, first — no ship, no commit).**
   - **SPA / UI change** (`drivetime/frontend/`): run the Vite dev server and `./dev.sh --dev`
     once; edits then hot-reload live. No build, no sync. Fastest loop.
   - **Kotlin / native change**: `./dev.sh` rebuilds + installs the real bundled APK.
   - **Caveat that bit us:** `--dev` serves the SPA from the *server's* origin, so the app runs
     as the web dashboard — server-bound, login-walled, not standalone. Anything touching
     standalone / offline / pairing / the app's own data must be checked on a plain `./dev.sh`
     build (bundled snapshot), not `--dev`.

2. **Bundle the change into the app (before shipping, not before testing).** A `drivetime/frontend/`
   change only becomes permanent in the app when `drivetime`'s `./sync-web-to-android.sh` refreshes
   `app/src/main/assets/web/` here. A dirty `assets/web/` is the correct, expected state — the app
   carries the change, ready to ship. **Do not** run `ship.sh` just to tidy it up.

3. **Ship to Play (only on explicit instruction).** Both repos, ending on Play's internal track:

   ```bash
   cd /home/lindsay/drivetime && SHIP_TOOL=claude bash ship.sh "msg"   # 1. drivetime: commit+push
   ./sync-web-to-android.sh                                            # 2. re-sync if changed since
   cd ../drivetime-android && SHIP_TOOL=claude bash ship.sh "msg"      # 3. local build+test gate, push
   ```

   Step 3 builds + runs the unit tests locally as a gate (this host has the toolchain — EMULATOR.md)
   before pushing; CI then uploads the AAB to Play's **internal track**, the only channel. Nothing
   to publish and nothing to wait for — the in-app updater is deleted, so `/dl`, `publish-apk.sh`
   and the CI GitHub release are gone (see Distribution). Shipping `drivetime` alone leaves the
   phone on the old snapshot — the #1 way a "fixed" bug survives a ship. Docs/CI-only commits that
   compile nothing can skip the gate with `SHIP_SKIP_GATE=1`.

Verify on the emulator/phone, not just a browser: touch targets, the hardware BACK button (shell
calls `window.__dtHandleBack()`), offline / no-server behavior, the `DrivetimeNative` bridge.
Where phone and desktop pull apart, the phone wins.

## Distribution — two channels, one source tree

`github` and `play` are build flavors (`app/build.gradle.kts`), and the difference is not
cosmetic:

| | `github` (sideload) | `play` |
|---|---|---|
| Build | `assembleGithubDebug` → APK | `bundlePlayRelease` → AAB |
| Updates reach users via | nothing — install by hand | Google Play |
| CI on push to `main` | uploads the APK as a **workflow artifact** | uploads the AAB to **internal testing** |

Every real install is on **Play**, and the flavors now differ only by their Drive OAuth client
(below). The `github` APK is a build artifact you can download from a CI run and sideload by
hand, not a channel anyone is served from. (It used to be published as a GitHub *release*
carrying a `version.json` for the in-app updater; the updater is deleted, so the release is
gone and the APK is just an `actions/upload-artifact` now.)

**A push to `main` reaches Play testers.** CI uploads the AAB to the **internal** track
automatically (`PLAY_SERVICE_ACCOUNT_JSON` secret; the step is skipped, not failed, when it's
absent, and never runs on a pull request — this repo is public and a fork must not reach the
credential). Play refuses a versionCode it has already accepted, so the monotonic CI run number
behind `versionCode` is load-bearing, not a convenience. To stop shipping to testers on every
commit, change the track or gate the step on `workflow_dispatch`.

**There is no in-app updater, and adding one back gets the app taken down.** `Updater.kt`,
`REQUEST_INSTALL_PACKAGES`, and the `UPDATER_ENABLED` flavor flag were deleted in hardening 3.1;
its whole distribution tail — the server `/dl` route, `publish-apk.sh`, `version.json`, the CI
GitHub release, the `updates_enabled`/`updates_supported` bridge keys, `checkForUpdate()`, and
the SPA's check-for-updates card — is gone too. Play's **Device and Network Abuse** policy
forbids an app updating itself by any route other than Play, and `REQUEST_INSTALL_PACKAGES` may
not be used for self-updates, so none of it was ever allowed to run in a shipped build. A stale
cached SPA that still calls `DrivetimeNative.checkForUpdate()` by name is harmless: `native.js`
probes `typeof` before every bridge call, so a missing method is a silent no-op. **Do not
re-add any of it** — an update affordance implies an updater, and the updater is what gets the
app removed.

Flavors rename every Gradle task: it's `testGithubDebugUnitTest`, `lintGithubDebug`,
`assembleGithubDebug` — not `testDebugUnitTest`.

**Signing: the two channels must NOT share a key, and that is not a preference.** The
`app/signing/` key is committed to a **public** repo, password and all. That is fine for the
sideload channel (it exists so sideloaded updates install in place), but it means anyone can
mint a package Android accepts as an upgrade to a drivetime install. Handing that key to
Play as the **app signing key** would extend that exposure to every Play user and is not an
option. So:

1. **Let Play generate the app signing key.** Google holds it and never exposes it. The
   committed key stays the *upload* key (it only proves the AAB came from us; Play re-signs).
2. **Accept the consequence:** a Play-served install has a **different signature** from a
   sideloaded one, so the two cannot upgrade into each other. Moving a phone from the
   sideload build to the Play build means **uninstall → reinstall**, and uninstalling wipes
   every drive on the device. Anyone making that jump — you included — must run
   **Settings → Sync & Backup → Back up now** first and restore afterward. There is no
   in-place path, and finding this out after inviting 12 people is the expensive way.
3. **Drive OAuth is per channel — two clients, not one.** A Google Android client is bound to
   a signing-cert SHA-1 (`drivetime/BACKUP.md`), and Play's app signing key has a different
   one, so a Play install presenting the sideload client is refused and Drive backup dies with
   no user-visible error. Minting a second client in the Cloud console is **not enough on its
   own**: a new client means a new id, and Google's installed-app redirect is the *reversed
   client id*, so the manifest's `OAuthRedirectActivity` scheme has to change with it. Both
   halves are therefore set from one place — `driveClient(...)` in `app/build.gradle.kts` sets
   `BuildConfig.DRIVE_CLIENT_ID` **and** derives the `${driveRedirectScheme}` manifest
   placeholder from it. Change a client id there and the scheme follows; hardcode either one
   and the callback silently never comes home.

Play also needs, before it will accept a release: a **privacy policy URL** (mandatory — the
app handles location), a completed **Data safety** form, and a **background-location
permission declaration** with a prominent-disclosure demo video.

The account is **personal** (no D-U-N-S). That matters less than it sounds: the
12-testers/14-days rule gates **production only**, so internal testing (up to 100 testers, no
full review) hands the app to testers immediately, while the **closed** track runs the clock
in the background. Revisit an organization account before monetizing — a personal account
publishes your full legal address once you charge money.

## There IS a local compiler now — but still never render the app yourself

**A JDK + Android SDK live on the dev host** (`~/.local/lib/jdk-17`,
`~/.local/lib/gradle-8.11.1`, `~/Android/sdk`; `local.properties` points at the SDK). CI is no
longer the only Kotlin compiler. Build and test locally:

```bash
export JAVA_HOME=~/.local/lib/jdk-17 ANDROID_HOME=~/Android/sdk
~/.local/lib/gradle-8.11.1/bin/gradle assembleGithubDebug testGithubDebugUnitTest
```

`ship.sh` runs exactly this as a gate before it pushes, so a syntax error or a red test is
caught here in ~2 min instead of a full CI round-trip. The two Kotlin traps that used to cost a
round-trip — `*/` closing a comment early, a bare `return` in a `Boolean` function — a local
`gradle` now catches for free; there's little excuse to push either.

**Still do not try to *render* the app.** There is no emulator or device *on this host* (the
emulator runs on Lindsay's Windows PC — EMULATOR.md), and the UI only exists as a real app: the
WebView's `DrivetimeNative` bridge is what makes the native Settings/HUD render at all, so
serving `assets/web` in a headless browser shows a surface the user never sees. **Lindsay looks
at the phone/emulator; you verify by building, testing, and reading.** Say what changed and let
him look. (When he wants to see a change live, the path is EMULATOR.md, driven from Windows —
not a render you produce here.)

CI (`.github/workflows/android.yml`) has two jobs. `compile` (Kotlin, app **and** test sources,
~2 min) runs on **every branch** — a test that doesn't compile is indistinguishable from one that
doesn't exist. `build` — unit tests, lint, the sideload APK, the Play AAB, the Play internal
upload — runs only on **main, PRs and `workflow_dispatch`**. A branch push still only *compiles*
in CI; to run the tests remotely use `gh workflow run android.yml --ref <branch>` — though a
local `testGithubDebugUnitTest` is now the faster answer.

**A `workflow_dispatch` run publishes nothing, so it stays safe even under "don't ship."** The
Play staging and Play upload are gated on `github.event_name == 'push' && github.ref ==
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

The toolchain moves as a unit: **compileSdk 36 needs AGP ≥ 8.9.1, and AGP 8.10 needs Gradle
≥ 8.11.1** (CI generates the wrapper). Bumping one without the other fails at configuration.
**Robolectric is part of that unit** — it picks its `android-all` jar from *targetSdk*, so a
version with no jar for the new level fails every Robolectric test with `initializationError`
(`DefaultSdkPicker`), which looks like a broken test and is really a stale dependency. 4.13 had
no SDK 35 jar; we run 4.16.1, which covers 35 and 36.

`ship.sh` here is leaner than the generic `/home/lindsay/scripts/ship.sh` (no ship log to
stamp), but it now **gates on a local `assembleGithubDebug` + `testGithubDebugUnitTest`** before
pushing — the host has the toolchain, so unverified Kotlin no longer leaves the box. `dev.sh`
builds + installs onto the Windows emulator over LAN ADB (EMULATOR.md); a UI-only change needs
neither script — the Vite dev server hot-reloads it.

## Next up

- **targetSdk 36 + real edge-to-edge — hard deadline 2026-08-31.** We ship on targetSdk 35
  with `windowOptOutEdgeToEdgeEnforcement` (themes.xml). Play requires 36 for new apps *and
  updates* from that date, and at 36 the opt-out is **ignored** — so the WebView would start
  drawing under the status bar and the tab bar under the gesture pill. The fix is to pad the
  activity roots by the system-bar + IME insets (`ViewCompat.setOnApplyWindowInsetsListener`),
  and it needs a real phone to verify. Do it *before* the bump, not with it.
- **Quick Settings tile** (`TileService`) — the last unbuilt entry-point in the control API.
- **Origin-scoped bridge** — expose `DrivetimeNative` via `addWebMessageListener` bound to the
  bundled origin, and stop handing the raw device token through `authHeader()`. Deferred from the
  hardening pass as higher-regression to the native↔web contract; do it with device verification.
- **Cloud-restore-arrives-unpaired prompt** — a cloud (not device-to-device) restore lands with
  settings but no device token (`drivetime_secrets.xml` is excluded from Google's backup on
  purpose). Detect `settings-restored-but-no-token` on first run and send the user to Settings →
  Pair a device. The fix is a prompt, not a secret.
- **OBD reconnect** — backoff retry on a dongle drop (GPS already continues regardless).
- **"Alive but blind" alarm** — the payoff `Health` was built for, and still unbuilt: heartbeat
  present + motion/OBD says the engine is running + no fixes for minutes = we are losing a drive
  *right now*. Today that is a completely dark failure. The `cond` rows (location off, permission
  revoked, power saver) are already being recorded to tune the thresholds against — deliberately
  collected before the alarm is wired, so it can't nag on a guess.
- **Low-accuracy / no-fix handling** — flag or drop poor fixes. (The "location services off" half
  is done: `Health` records it as a `cond` transition and the Drives timeline names it as the
  cause of a gap.)
- **~~One logging state machine~~ — done (hardening 5.1).** `TierReconciler` is the single
  thread tier state lives on; manual, detector and routine commands all `submit` to it and are
  applied serially, in order. Fixes are delivered straight onto that thread, so the hot path is
  not a hop — it *is* the thread. The invariant ("nothing mutates tier fields directly") is
  enforced by `requireOwnThread`, which **warns rather than throws** in production: an app whose
  purpose is to not stop logging must not acquire a new way to die. If you see
  "touched tier state on '<thread>'" in the Activity log, something is racing again.
- **Credentials in Keystore** — the five secrets (`Settings.SECRET_KEYS`: device token, control
  token, legacy username/password, Drive refresh + access token) still sit in plain
  `SharedPreferences`, and `SettingsExport` writes them in cleartext — deliberately, because the
  app's own backup is the full-fidelity restore path. Plaintext **at rest** is what Keystore
  would fix, and it is still open. Hardening 3.4 closed the *exfiltration* half: they live in
  their own prefs file (`drivetime_secrets.xml`) purely so the backup rules can exclude them —
  Android can exclude a prefs **file** but not a **key** — so Google's cloud backup never holds a
  token, while the ordinary settings in `drivetime.xml` still back up and restore. A direct
  device-to-device transfer still carries everything, so a new phone just works. **The two rule
  files must agree** (`res/xml/data_extraction_rules.xml` for API 31+, `backup_rules.xml` for
  ≤ 30). Adding a secret means adding it to `SECRET_KEYS` *and* pointing its accessor at
  `secrets` — `SecretsMigrationTest` fails if you do only one.
- **A pre-release checklist** — permissions, FGS, boot, queue, OEM battery. The Windows emulator
  (EMULATOR.md) now covers much of this without a physical phone; a written checklist still backs
  the OEM-battery and real-device behaviours an emulator can't reproduce.
- **R8/minify is OFF, and that is load-bearing.** `isMinifyEnabled = false` on both build types
  because the `@JavascriptInterface` bridge methods are reached by name from JS — R8 would rename
  or strip them and the whole `DrivetimeNative` surface goes silently dead. Turning minify on
  requires keep rules for every bridge class *first*. Don't flip it casually.

## Read also

- `EMULATOR.md` — **live UI reload + running the app in an emulator.** The compiler is on this
  host; the emulator is on Windows; they meet over LAN ADB. Read before `dev.sh`, the
  `-PdevServer` flag, or any "how do I see this change" question.
- `README.md` — the user-facing pitch: what the app does and why.
- `AUTOMATION.md` — the routine/shortcut control surface (shortcuts, intents, `SET` keys,
  `STATE_CHANGED`, recipes). Mirrored in-app under Settings → Advanced → Automation.
- `AGENTS.md` — the same instructions for non-Claude agents (keep in sync with this file).
- Sibling repo: `drivetime/NOTIFICATIONS.md`, `BACKUP.md`, `AUTH.md`, `STANDALONE.md`.
