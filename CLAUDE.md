# drivetime-android — Claude Context

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that also
hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what the user
actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an *optional*
support server.

Sibling repo: `/home/lindsay/drivetime` (SPA + FastAPI backend). Read its `NATIVE_APP.md`
(shell architecture), `STANDALONE.md` (why the app bundles its own web snapshot), and
`AUTH.md` (device-token pairing) before touching the bridge, auth, or sync.

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

Three things the detector keeps deliberately separate — collapsing them is what caused that bug:
**`isMoving`** (are the wheels turning right now — the drive's green/red signal light, what the UI
shows), **`tier`** (how fast to sample; holds through a red light, ends at `parked`), and the
**drive session** (`markDriveStart`, which still ends when the tier leaves DRIVING). `STOP_MS` is
one 5-minute constant for every latch, the same 5 minutes `segment.js` calls a park, so the live
app, the drive log and segmentation agree on what a stop is. OBD `rpm > 0` (`engineRunning`) is the
only thing that extends the hold — idling with the engine on is not parked. *(The old
activity-recognition `TripDetector` is retired — its slow-traffic car/bike guess was the unreliable
part. The code remains, opt-in behind `auto_trip`, and is not armed by default.)*

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

## Phone first: update always, ship only when told

A change to `drivetime/frontend/` that is only committed in `drivetime` updates the *website*
and nothing else — the phone keeps running the old bundled snapshot. So the two halves are
deliberately separate:

**Update (always, unprompted).** Every frontend change ends with `drivetime`'s
`./sync-web-to-android.sh`, which refreshes `app/src/main/assets/web/` here. It leaves the new
snapshot in this repo's **working tree** and pushes/publishes nothing. A dirty `assets/web/` is
therefore the correct, expected state: it means the app carries the change and is ready to
ship. **Do not** run `ship.sh` just to clean it up.

**Ship (only on explicit instruction).** Shipping means **both repos, ending in a published
APK** — never one without the other:

```bash
cd /home/lindsay/drivetime
SHIP_TOOL=claude bash ship.sh "message"       # 1. drivetime: commit + push
./sync-web-to-android.sh                      # 2. re-sync if anything changed since
cd ../drivetime-android
SHIP_TOOL=claude bash ship.sh "message"       # 3. commit+push, await CI APK, publish to /dl
```

Step 3 blocks on the "Build APK" CI run then calls `drivetime/publish-apk.sh --watch <sha>`.
What actually reaches a phone is the **Play internal-track upload** that the same CI run does —
the app no longer self-updates, so the published APK/`/dl` pair is now just an artifact nobody
polls, and that half of the ship is vestigial (see Distribution). Shipping `drivetime` alone
leaves the phone on the old snapshot — the single most common way a "fixed" bug survives a ship.

The one exception: a commit here that changes **nothing the app runs** (docs, CI config) can go
up with `SHIP_SKIP_PUBLISH=1 bash ship.sh "…"` — pushed, no APK built for users. Anything
touching `app/` or `assets/web/` publishes.

Verify on the phone, not just in a browser: touch targets, the hardware BACK button (the shell
calls `window.__dtHandleBack()`), offline / no-server behavior, and the `DrivetimeNative`
bridge. Where phone and desktop pull apart, the phone wins.

## Distribution — two channels, one source tree

`github` and `play` are build flavors (`app/build.gradle.kts`), and the difference is not
cosmetic:

| | `github` (sideload) | `play` |
|---|---|---|
| Build | `assembleGithubDebug` → APK | `bundlePlayRelease` → AAB |
| Updates reach users via | nothing — install by hand | Google Play |
| CI on push to `main` | publishes the APK as a GitHub release | uploads the AAB to **internal testing** |

Every real install is on **Play**, and the flavors now differ only by their Drive OAuth client
(below). The `github` APK is a build artifact you can sideload by hand, not a channel anyone
is served from.

**A push to `main` reaches Play testers.** CI uploads the AAB to the **internal** track
automatically (`PLAY_SERVICE_ACCOUNT_JSON` secret; the step is skipped, not failed, when it's
absent, and never runs on a pull request — this repo is public and a fork must not reach the
credential). Play refuses a versionCode it has already accepted, so the monotonic CI run number
behind `versionCode` is load-bearing, not a convenience. To stop shipping to testers on every
commit, change the track or gate the step on `workflow_dispatch`.

**There is no in-app updater, and adding one back gets the app taken down.** `Updater.kt`,
`REQUEST_INSTALL_PACKAGES`, and the `UPDATER_ENABLED` flavor flag were deleted (hardening 3.1):
Play's **Device and Network Abuse** policy forbids an app updating itself by any route other
than Play, and `REQUEST_INSTALL_PACKAGES` may not be used for self-updates — so the flag only
ever had one legal setting, and keeping the code meant keeping an APK downloader-and-installer
no shipped build was allowed to run. The bridge reports `updates_supported=false` (a constant),
and the SPA hides the whole affordance; `DrivetimeNative.checkForUpdate()` survives as an honest
no-op toast, because a WebView on a stale cached snapshot still has the button and calls it by
name.

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
publishes your full legal address once you charge money. See `PLAY.md`.

## No local compiler — and never try to render the app yourself

There is **no JDK or Android SDK on the dev host**, no emulator, and no device here. Do not
try to build, launch, screenshot, or otherwise *look at* the app — not with an emulator, not
by serving `app/src/main/assets/web` in a headless browser. The UI only exists once it's a
real app on a real phone (the WebView's `DrivetimeNative` bridge is what makes the native
Settings/HUD render at all), so an attempt at it costs time and produces a picture of
something the user never sees. **Lindsay looks at the phone; you verify by building, testing,
and reading.** Say what changed and let him look.

CI is the only Kotlin compiler, so a syntax error costs a full CI round-trip. Read Kotlin edits carefully before pushing; two that
have bitten us: `*/` inside a comment closes the block early, and a bare `return` in a function
declared to return `Boolean` won't compile.

CI (`.github/workflows/android.yml`) runs unit tests + lint, builds the sideload APK and the
Play AAB, publishes the APK as a GitHub release, and uploads the AAB to Play internal testing.
Tests worth knowing about: `AutomationHelpTest` holds the in-app cheat-sheet to
`Control.SET_KEYS`, and `Uploader`/`DriveDetector`/`ControlParse` have real coverage. There is
no device on the dev host, so CI *is* the safety net.

The toolchain moves as a unit: **compileSdk 36 needs AGP ≥ 8.9.1, and AGP 8.10 needs Gradle
≥ 8.11.1** (CI generates the wrapper). Bumping one without the other fails at configuration.
**Robolectric is part of that unit** — it picks its `android-all` jar from *targetSdk*, so a
version with no jar for the new level fails every Robolectric test with `initializationError`
(`DefaultSdkPicker`), which looks like a broken test and is really a stale dependency. 4.13 had
no SDK 35 jar; we run 4.16.1, which covers 35 and 36.

`ship.sh` here is intentionally leaner than the generic `/home/lindsay/scripts/ship.sh` (no
ship log to stamp, no local build to gate on); CI plus `--watch` are the pre-publish gate.

## Next up

- **targetSdk 36 + real edge-to-edge — hard deadline 2026-08-31.** We ship on targetSdk 35
  with `windowOptOutEdgeToEdgeEnforcement` (themes.xml). Play requires 36 for new apps *and
  updates* from that date, and at 36 the opt-out is **ignored** — so the WebView would start
  drawing under the status bar and the tab bar under the gesture pill. The fix is to pad the
  activity roots by the system-bar + IME insets (`ViewCompat.setOnApplyWindowInsetsListener`),
  and it needs a real phone to verify. Do it *before* the bump, not with it.
- **Quick Settings tile** (`TileService`) — the last unbuilt entry-point in the control API.
- **OBD reconnect** — backoff retry on a dongle drop (GPS already continues regardless).
- **"Alive but blind" alarm** — the payoff `Health` was built for, and still unbuilt: heartbeat
  present + motion/OBD says the engine is running + no fixes for minutes = we are losing a drive
  *right now*. Today that is a completely dark failure. The `cond` rows (location off, permission
  revoked, power saver) are already being recorded to tune the thresholds against — deliberately
  collected before the alarm is wired, so it can't nag on a guess.
- **Low-accuracy / no-fix handling** — flag or drop poor fixes. (The "location services off" half
  is done: `Health` records it as a `cond` transition and the Drives timeline names it as the
  cause of a gap.)
- **One logging state machine** — manual, detector, and routine commands can still race.
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
- **A pre-release checklist** — permissions, FGS, boot, queue, OEM battery. Validation is
  sideload-only, so a checklist is the only gate a device would otherwise provide.

## Read also

- `README.md` — the user-facing pitch: what the app does and why.
- `AUTOMATION.md` — the routine/shortcut control surface (shortcuts, intents, `SET` keys,
  `STATE_CHANGED`, recipes). Mirrored in-app under Settings → Advanced → Automation.
- `PLAY.md` — **live plan:** getting the app onto Google Play (account type, signing, the
  tracks, what Play won't let you skip). Delete it once we're on Play; the durable half is
  the Distribution section above.
- `AGENTS.md` — the same instructions for non-Claude agents (keep in sync with this file).
- Sibling repo: `drivetime/NOTIFICATIONS.md`, `BACKUP.md`, `AUTH.md`, `STANDALONE.md`.
