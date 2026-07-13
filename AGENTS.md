# drivetime-android — Agent Instructions

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that also
hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what the user
actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an *optional*
support server.

`CLAUDE.md` carries the same instructions; keep the two in sync.

## Read first

- Sibling repo `/home/lindsay/drivetime`: `NATIVE_APP.md` (shell architecture),
  `STANDALONE.md` (why the app bundles its own web snapshot), `AUTH.md` (device-token
  pairing) — before touching the bridge, auth, or sync.
- `README.md` (the user-facing pitch), `AUTOMATION.md` (the routine/shortcut control surface).
- `PLAY.md` — **live plan:** getting the app onto Google Play. Delete once we're on Play.

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
app, the drive log and segmentation agree on what a stop is. OBD `rpm > 0` (`engineRunning`) is the
only thing that extends the hold — idling with the engine on is not parked. *(The old
activity-recognition `TripDetector` is retired — opt-in behind `auto_trip`, not armed by default.)*

**Fixes are durable.** `Uploader` writes an on-disk queue that is atomic, size-capped (16 MB,
drop-oldest), and ordered; only lines the server actually acked are deleted; failures back off
exponentially. Flushes fire on a tier-aware cadence (~10 s driving, ~45 s light), on batch-full,
on regained connectivity, on app-foreground, and on charge-connected.

**The SPA is the app.** `WebViewActivity` serves the bundled snapshot over `WebViewAssetLoader`
at `https://appassets.androidplatform.net/assets/web/` — a secure origin, so the service worker
and IndexedDB replica work with no server. Settings are the SPA's own tabs (General / Tracking /
Sync & Backup / Advanced) over the `DrivetimeNative` bridge; native flows (permissions, BT/OBD
pairing, QR pairing, backup pickers, updater) fire in place from their tab.

**Everything else, briefly:** `Notify` is the single door for every notification except the
ongoing drive card (drive-complete, gas-stop, weekly digest, check-engine, tracking-interrupted
— one channel per kind, each with a toggle, deep link, and retraction).
`BackupStore`/`BackupWorker`/`DriveClient` take scheduled full-data snapshots to a SAF folder
and/or the user's own Google Drive. `Updater` polls GitHub Releases for one-tap APK updates.
`Control` + `ControlReceiver` + `StateBroadcaster` are the routine API (`AUTOMATION.md`).
**Android Auto was removed** before the first Play upload — Play rejects a manifest carrying
both the `automotive` feature and the Auto meta-data, and an Auto app draws review scrutiny we
can't verify without a head unit. Don't re-add it casually; git has it.
`Permissions.snapshot`/`checklist` is the one gate every "can we log right now?" question goes
through.

## The web assets are generated — never hand-edit them

`app/src/main/assets/web/` is a **committed build artifact**, not source. Its source is
`drivetime/frontend/`. Refresh it with
`cd /home/lindsay/drivetime && ./sync-web-to-android.sh`. Editing `assets/web/` directly is
always wrong — the next sync overwrites it and the website and phone drift apart.

## Phone first: update always, ship only when told

A change to `drivetime/frontend/` that is only committed in `drivetime` updates the *website*
and nothing else; the phone keeps running the old bundled snapshot. The two halves are
deliberately separate:

**Update (always, unprompted).** Every frontend change ends with `drivetime`'s
`./sync-web-to-android.sh`, which refreshes `app/src/main/assets/web/` here. It leaves the new
snapshot in this repo's **working tree** and pushes/publishes nothing. A dirty `assets/web/` is
therefore the correct, expected state: the app carries the change and is ready to ship. **Do
not** run `ship.sh` just to clean it up.

**Ship (only on explicit instruction).** Shipping means **both repos, ending in a published
APK** — never one without the other:

```bash
cd /home/lindsay/drivetime
bash ship.sh "message"                  # 1. drivetime: commit + push
./sync-web-to-android.sh                # 2. re-sync if anything changed since
cd ../drivetime-android
bash ship.sh "message"                  # 3. commit+push, await CI APK, publish to /dl
```

Step 3 blocks on the "Build APK" CI run then calls `drivetime/publish-apk.sh --watch <sha>`, so
a ship is not finished until the in-app updater offers the new APK. Shipping `drivetime` alone
leaves the phone on the old snapshot — the single most common way a "fixed" bug survives a ship.

The one exception: a commit here that changes **nothing the app runs** (docs, CI config) can go
up with `SHIP_SKIP_PUBLISH=1 bash ship.sh "…"` — pushed, no APK built for users. Anything
touching `app/` or `assets/web/` publishes.

Verify on the phone, not just in a browser: touch targets, the hardware BACK button (the shell
calls `window.__dtHandleBack()`), offline / no-server behavior, and the `DrivetimeNative`
bridge. Where phone and desktop pull apart, the phone wins.

## Distribution — two channels, one source tree

`github` and `play` are build flavors (`app/build.gradle.kts`):

- **`github`** (sideload) — `assembleGithubDebug` → APK, published as a GitHub release. Keeps
  the in-app `Updater` and declares `REQUEST_INSTALL_PACKAGES` (`src/github/AndroidManifest.xml`).
- **`play`** — `bundlePlayRelease` → AAB. The updater is **compiled out**
  (`BuildConfig.UPDATER_ENABLED=false`) and the permission is absent, because Play's Device
  and Network Abuse policy forbids an app updating itself outside Play. Putting either back
  gets the app taken down. The SPA hides the affordance when the bridge reports
  `updates_supported=false`.

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

CI (`.github/workflows/android.yml`) runs unit tests + lint, builds the APK, and publishes it
as the GitHub release the in-app updater reads. `AutomationHelpTest` holds the in-app
cheat-sheet to `Control.SET_KEYS`. There is no device on the dev host, so CI *is* the safety net.

`ship.sh` here is intentionally leaner than the generic `/home/lindsay/scripts/ship.sh` (no
ship log to stamp, no local build to gate on); CI plus `--watch` are the pre-publish gate.
`SHIP_SKIP_PUBLISH=1` commits and pushes without waiting for the APK.

## Next up

- **Quick Settings tile** (`TileService`) — the last unbuilt entry-point in the control API.
- **OBD reconnect** — backoff retry on a dongle drop (GPS already continues regardless).
- **Low-accuracy / no-fix handling** — flag or drop poor fixes; notice "location services off".
- **One logging state machine** — manual, detector, and routine commands can still race.
- **Credentials in Keystore** — the device token is in plain `SharedPreferences` today.
- **A pre-release checklist** — permissions, FGS, boot, queue, OEM battery (sideload-only
  validation means the checklist is the only gate a device would otherwise provide).
