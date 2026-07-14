# drivetime-android — hardening & refactor plan

Built from a six-agent review (logger core, WebView bridge/security, data durability,
control/settings/notifications, UI/UX, architecture). The app is **released to testers**, so
every item below is scoped to preserve an already-installed app. Read the *Compatibility
contract* before starting anything.

---

## ~~Confirm first — it changes Phase 3~~ — **ANSWERED: Play internal, fully migrated**

Every install (Lindsay's phone included) now comes from Play internal testing; the migration in
`PLAY.md` §5 is done. So removing the updater was a no-op for testers — it was already compiled
out of the `play` flavor — and Phase 3 shipped in full.

CI still publishes a sideload APK to GitHub Releases on every push to `main`. With the updater
gone that release is a **build artifact you can install by hand, not a channel**: nothing polls
it, and no install updates itself from it. Retiring it (and `publish-apk.sh` / the server `/dl`
route / `ship.sh`'s publish gate, which all exist only to feed the updater) is a real cleanup,
but it reaches into the sibling repo and `ship.sh`, so it is **left as a follow-up decision**,
not done silently. See "Follow-ups" at the bottom.

---

## The compatibility contract (the "nothing breaks testers" spine)

Every commit must preserve all of these, or it breaks an installed tester:

1. **Signing key, `applicationId`, monotonic `versionCode`** (the CI run number). Never reset,
   never re-key — Play rejects a repeated versionCode; a re-key strands every install.
2. **On-disk filenames + formats:** `web_fixes.jsonl`, `web_markers.jsonl`,
   `web_vehicles.jsonl`, `web_battery.jsonl`, `queue.jsonl`, `backup/snapshot.json.gz`, and the
   backup zip entries `pending_fixes/markers/vehicles.jsonl`. The `JsonlRing` refactor and any
   backup change must stay byte-compatible or existing data orphans and prior backups won't
   restore.
3. **The `drivetime` SharedPreferences** — every key name and type. (`Uploader.Health` →
   `UploadHealth` is an internal class rename, no prefs impact — safe.)
4. **`DrivetimeNative` bridge method names/signatures** — the bundled SPA calls them by name.
5. **Exported control action strings + default-open behavior** — no tester's existing Samsung
   Routine / Tasker broadcast may start being silently rejected (see 3.2).

## Sequencing principle

**Surgical fixes first, refactors last.** Each item is an independently-shippable, CI-green
commit. Behavior-fixes (Phases 1–4) are small and verifiable; the structural refactors (Phase 5)
are where a regression could *introduce* a silent-stop, so they ship to testers only after a
phone check. Never bundle a fix with the refactor that makes it pretty.

---

## Phase 1 — surgical safety fixes — **DONE, in the working tree, unshipped**

- **1.1 OBD socket fd leak** — ✅ `Elm327Client.connect()` is now atomic: everything after
  `openSocket` returns is wrapped in `try/catch { close(); throw }`, so a throwing init (the mute
  clone that never answers `handshake`) can no longer strand a socket the service never got a
  reference to. The guard starts at `socket = s`, because `s.inputStream`/`s.outputStream` can
  throw too and a leak there is the same bug one line up. `LocationService` unchanged.
  *Phone check still owed:* drive with a mute/clone dongle, fd count stable across retries.
- **1.2 + 1.3 Clamp cadences** — ✅ done together, and deliberately **not where this doc said**.
  The plan put the clamp in `ControlParse` (routine SET) and again in `SettingsExport.fromJson`
  (import) — but those are two of *three* writers; the SPA bridge (`WebViewActivity.setSetting`)
  is the third. So the bounds table lives in `ControlParse.BOUNDS` / `clampSetting()` (pure,
  unit-tested, no Context) and is **enforced in the `Settings` accessors**, which every writer
  passes through. That follows the idiom already in that file — `backupKeep`, `digestDay` and
  `backupSchedule` all self-clamp — and it can't be bypassed by a future call site.
  `SettingsExport` needed no code change as a result.
  - Clamped on **read as well as write**: a poisoned pref written by an older build would
    otherwise survive an app update and keep an installed phone's logger dead.
  - The routine path keeps its parse gate — `interval_sec=0` is still *rejected* by `parsePosInt`,
    not clamped. Only the import path (where `optInt` hands 0 straight through) needs the clamp's
    lower bound.
  - `Control.setPosInt` now `EventLog.warn`s when it clamps, so a routine that asked for something
    absurd can find out its recipe doesn't do what it says.
  - Tests: `ControlParseTest` (the bounds table) and `SettingsTest` (write, read/poisoned-pref,
    import, routine SET, and `stationary_stop_min=0` still meaning disabled).

## Phase 2 — data durability — **DONE, in the working tree, unshipped**

No on-disk filename, format, prefs key or bridge method changed (contract #2/#3/#4 intact). Two
new prefs (`backup_fail_streak`, `notify_backup_health`) and one new bridge *key* are additive.

- **2.1 SAF backup temp-name rename** — ✅ `BackupWorker.writeToFolder` writes
  `tmp-drivetime-backup-….zip` and renames on the last byte (`BackupStore.tempName`), deleting
  the temp if the write throws. **Not `.part`, deliberately:** a SAF provider appends an
  extension matching the MIME type when the display name's disagrees, so `…zip.part` comes back
  as `…zip.part.zip` — which *does* match `isArchiveName`, i.e. the exact bug reintroduced under
  a new name. The temp-ness lives in the prefix, where nothing rewrites it. `namesToPrune` also
  sweeps stray temps (nothing else ever would).
- **2.2 Snapshot-before-restore** — ✅ `BackupStore.restore` archives the current install to
  `cacheDir/pre-restore.zip` before applying anything, best-effort (a failed undo never blocks
  the restore) and only for a file it actually recognises. It restores like any other archive —
  `BackupStoreTest` proves it by round-tripping a phone back through it.
- **2.3 Backup silent-failure notification** — ✅ new `Notify.KIND_BACKUP_HEALTH` kind (its own
  channel, `DEFAULT` importance, default-ON toggle `notify_backup_health` — a *health alert*, not
  a nag) posted after `BackupWorker.FAIL_STREAK` = 3 consecutive failed **automatic** runs
  (`Settings.backupFailStreak`). Manual runs don't count (the card the user is watching already
  says so) and "no destination configured" is not a failure. Producer-retracted by the first
  successful run, and excluded from the SPA's attention sweep like the other health kinds.
  Deviation from "android-only": the kind also gets its SPA toggle row (Settings → Notifications)
  and a `SettingsExport` key, because NOTIFICATIONS.md's contract is that every kind has an
  in-app toggle — so this ends with `./sync-web-to-android.sh` like a Phase 4 item.
- **2.4 `NetworkType.CONNECTED` when Drive is a destination** — ✅ `BackupWorker.constraints(s)`,
  applied to the periodic *and* after-drive work; a folder-only backup stays unconstrained (it
  works offline), and **Back up now** stays unconstrained on purpose. The constraint is derived
  from the destination set, so every mutation of that set now re-arms the work (folder
  picked/forgotten, Drive connected — `OAuthRedirectActivity` — /disconnected, settings restored
  with a Drive token).
- **2.5 `fsync` + drop the non-atomic fallback** — ✅ `Uploader.atomicWriteLocked` writes the
  temp, `fd.sync()`s it, then renames; the old "rename failed → `writeText` straight over
  `queue.jsonl`" fallback is gone (it was the exact non-atomic write the temp file exists to
  avoid, run at the moment the filesystem has already said it is unhappy). It now returns
  `Boolean`, and a failed rewrite keeps the previous queue and reseeds the cached count (`-1`)
  instead of caching a number the file doesn't support; re-sending an acked fix is safe, the
  server ingests idempotently on `(ts, source)`. `BackupStore.endSnapshot` fsyncs the gzip
  snapshot before its rename for the same reason.
- **Tests** — `BackupStoreTest` (temp names are never archives + always pruned; the pre-restore
  undo restores; a refused file writes no undo), `SettingsTest` (health kinds default ON, nags
  OFF; the streak floors at 0), `UploaderQueueTest` (**new `mockwebserver` dep**, version-locked
  to the okhttp already in use — it drives a real `flush()`, which is the only route into the
  verify-before-delete + atomic-rewrite code: acked lines leave and no `queue.tmp` is left; a
  500 deletes nothing; a fix enqueued *while the POST is in flight* is sent, not dropped).
  `resetQueueCacheForTest` now also clears the process-global backoff, or a failing-flush test
  parks every later test inside a backoff window where `flush()` returns early.

*Phone checks owed:* a real SAF folder backup (write + rename + retention on a real provider —
the extension-append behaviour above is provider-specific), and a Drive backup while offline
(defers, doesn't fail).

## Phase 3 — security hardening — **DONE, committed on `hardening-phase-3`, unshipped**

No prefs key, on-disk file, or `DrivetimeNative` method was removed (contracts #2/#3/#4 intact).
Contract #5 holds too: with the default **blank** control token every routine verb stays exactly
as open as before.

- **3.1 Self-updater deleted** — ✅ `Updater.kt`, `UpdaterTest.kt`, `src/github/AndroidManifest.xml`
  (its only content was `REQUEST_INSTALL_PACKAGES`), the `UPDATER_ENABLED` field on both flavors,
  the `onResume` auto-check, and the FileProvider `updates/` root. The flavors now differ only by
  their Drive OAuth client. **Two things deliberately kept**, against the letter of the plan:
  - `getStatus` still reports **`updates_supported`, hardcoded `false`**. The SPA tests
    `updates_supported !== false`, so *dropping* the key means "supported" — it would have put
    the dead button back. This is also why the SPA needed no change and Phase 3 stayed a
    one-repo job (no `sync-web-to-android.sh`).
  - **`checkForUpdate()` stays on the bridge** as an honest no-op toast. A WebView on an older
    cached snapshot still has the button and calls it by name (contract #4).
- **3.2 Control token now covers STOP/TOGGLE** — ✅ and **not where this doc said**. The plan put
  the check at `Control.kt:116`, inside `apply()` — but the **in-app Tracking switch**
  (`WebViewActivity.kt:953`) and the activity-recognition receiver stop logging through that same
  function, so a check there would have meant *a user who sets a token can no longer press their
  own Off switch*. The trust boundary is the **exported** surface, so that is where the gate went:
  - `Control.applyExternal()` — the one gated entry point. `ControlActivity` and `ControlReceiver`
    (the only exported components; any installed app can reach both, no permission needed) call it.
    `Control.apply()` is now explicitly the *trusted* in-app path with no token check — this app
    does not need the user's secret to obey the user.
  - `Control.externalAllowed()` is pure/Context-free, so the whole policy is unit-tested on the
    JVM (`ControlTokenTest`) — the same idiom Phase 1.2 used for `clampSetting`.
  - Gated: `SET`/`QUERY`/`MARK` (as before) **+ `STOP`/`TOGGLE`** (new). `START` and the `MODE_*`
    verbs stay open on purpose — they cannot stop logging, so a routine can always *recover*
    tracking without the secret, which is what the old always-open STOP was really protecting.
  - **The cost of opting in, now documented:** the built-in **Off** App Shortcut is static XML and
    cannot carry a runtime secret, so it stops working once a token is set (Auto/Driving/Eco are
    unaffected). There is no way around this short of making the token not a secret.
- **3.3 WebView navigation confined to `appassets`** — ✅ `isInAppUrl(url)` lost its `serverUrl`
  parameter entirely — with the server external there is no host-dependent case left. The bridge
  is attached to the *WebView*, not a page, so every document it loads gets `DrivetimeNative`;
  the privacy-policy link on the server's own host was enough to trip this by accident, never mind
  a MITM'd self-hosted server on plain HTTP. The `addJavascriptInterface` comment already claimed
  "only our own SPA is ever loaded here" — this commit made that true and named what enforces it.
- **3.4 Credentials excluded from auto-backup** — ✅ **Android cannot exclude individual prefs
  keys, only whole files.** The first cut of this therefore excluded all of `drivetime.xml`, which
  bought credential safety at the price of "a cloud restore loses every setting you have". That
  was a false choice: nothing forced the secrets to share a file with the settings.
  - **The five secrets now live in `drivetime_secrets.xml`** (`Settings.SECRET_KEYS`: device
    token, control token, legacy username/password, Drive refresh + access token), and *only that
    file* is excluded. The key **names** are unchanged, so `SettingsExport` — which round-trips
    them by name, and still carries them deliberately — and every other caller are untouched.
    Only the file underneath moved. `backup_drive_client_id` (public), `…_account` (an email) and
    the cached folder ids are **not** secrets and stay put.
  - **Cloud backup and device transfer are treated differently, on purpose.** Cloud backup is an
    unrequested copy at rest on Google's servers → secrets excluded. A device transfer is a
    user-initiated phone-to-phone move during setup → **everything goes, credentials included**,
    so a new phone just works. `<device-transfer/>` is declared and deliberately empty rather than
    omitted: an absent section probably means the same, and "probably" is not good enough for the
    difference between a phone that transfers cleanly and one that silently arrives unpaired.
  - **The migration is the only part that can hurt an installed phone** (`Settings.migrateSecrets`,
    run from `DrivetimeApp.onCreate` — single process, so it lands before anything reads a
    `Settings`). It copies → *verifies the copy landed* → only then clears the originals, and any
    step failing leaves them exactly where they are for the next launch to retry. It is idempotent
    with no flag to fall out of sync, and a key already in the secrets file is **never** overwritten
    from the old one — otherwise a migration that copied but failed to clear would let a later
    launch resurrect a stale token over a freshly-paired one. `SecretsMigrationTest` covers all of
    that, plus the "declared secret but accessor still writes the backed-up file" half-job.
  - **Both** rule files are required and must agree: `fullBackupContent` (`backup_rules.xml`) is
    read on API ≤ 30, `dataExtractionRules` on 31+, and minSdk is 26. Shipping only the latter
    would have left every Android 11 phone still backing the credentials up. API ≤ 30 has no
    cloud-vs-transfer distinction, so there the conservative half wins.
  - Net: cloud restore → settings + drives back, re-pair and reconnect Drive. Device transfer →
    everything, zero friction. The app's own backup → everything, always. Google never holds a token.
- **Deferred (needs device):** origin-scoped bridge via `addWebMessageListener` and not exposing
  the raw token through `authHeader()`. Higher regression risk to the native↔web contract — do
  it in Phase 5 with device verification.

*Phone checks owed:* none are strictly blocking, but worth a look on the phone — an external link
(privacy policy, Play link) now opens in the browser rather than in-app (3.3), and the About card
should read "Updates arrive through Google Play" with no update button (3.1).

## Phase 4 — UI/UX (SPA — sibling `drivetime/frontend` repo) — **DONE, in the working tree, unshipped**

These live in `drivetime/frontend/src/` (not `views/App.svelte` — `App.svelte` is at the root of
`src/`; the line numbers below were off by that one directory). Done in `drivetime`, synced into
this repo's `assets/web/` with `./sync-web-to-android.sh`, so **reaching testers is the two-repo
ship**. No bridge method, prefs key or on-disk format touched (contracts #2/#3/#4 intact).

- **4.1 Header liveness** — ✅ `pollNative()` now also reads `getStatus()` (`nstat`), and the
  header's tracking light goes **amber + corner dot** when `killWarning` is set, with the
  tracker's own reason as the tooltip/`aria-label`. The green light was a claim about the
  *switch*, not the logger — the one lie this app must not tell. Tapping it still opens the
  turn-off sheet: the *explanation* already lives on the bell's attention feed (`notify.js`
  reads the same `killWarning`) and in Settings, so the header only had to stop saying "fine".
- **4.2 First-run copy** — ✅ The Drives empty state no longer says "Connect your phone (More →
  API token)" — a menu that hasn't existed since the Settings tab rebuild, for a server the app
  doesn't need. It now branches on `hasNative()`: on the phone, "turn tracking on and drivetime
  logs your drives by itself"; on the website (which genuinely is waiting on a phone), "drives
  logged on your phone appear here". `Login.svelte`'s stale "Tracking → Sync" path is now
  "Settings → Sync", and says the phone logs drives with or without the server.
- **4.3 Reduced-motion** — ✅ and **factored, not copy-pasted**: `ActiveDriveBar` and
  `NotificationCenter` had each grown their own `matchMedia` line, and this item would have added
  three more. The rule now lives in `lib/motion.js` (`reducedMotion()` + `softly(fn, params)`),
  used by `Toasts`, `UndoBar` and `SelectionBar` (card + both panels). Degrades to a **cross-fade,
  never a hard cut** — reduced-motion asks for no *travel*, and a snackbar that simply blinks into
  existence reads as a rendering glitch. Read at call time, so an OS flip mid-session takes effect.
- **4.4 A11y** — ✅ `lib/focustrap.js`, used by `Modal` and `Sheet`: Tab stays inside the open
  dialog and focus returns to whatever opened it. Two deliberate details — it focuses the **dialog
  box, not the first control** (a sheet leading with a text field would otherwise throw the phone's
  keyboard up over itself), and the initial focus is **deferred a microtask** because `Sheet`
  portals itself into `<body>` on mount and re-parenting a node blurs it (focusing inline would
  have silently done nothing for every sheet in the app). `Toasts` is now a `role="status"`
  `aria-live="polite"` region — it is the app's only confirmation that an action happened.
- **4.5 "All time" 300-drive cap** — ✅ **the cap moved rather than being raised.** It was never
  buying anything in `recentTripsLocal`: that function reads *every* drive out of the replica
  before it slices, so the read costs the same either way — the slice only trimmed what it handed
  back, and the Drives list then presented 300 rows as the whole of All time while the totals strip
  above it was (correctly) computed over all 800. `limit: null` now means "no cap" (`limit: 0` too
  — `?? Infinity` would have turned a falsy limit into a *blank page*), Drives asks for the whole
  period, and the **view** caps its own rendering at 300 with an honest "Showing 300 of 812" +
  **Show all**. The cap runs forward to the end of the day it lands in (a half-day would print a
  partial day roll-up as the day's total), outages before the cutoff aren't folded in, the cap
  re-arms on a new period/search, and `SelectionHost`'s `total` is now what's *on screen* — "All"
  can't select rows the user can't see. New test: `frontend/tests/tripscap.test.mjs`.
- **4.6 In-car touch targets** — ✅ `ActiveDriveBar`'s **Mark** 34px → 44px (the one control
  pressed while the car is moving; the band measures itself into `--header-h`, so everything below
  follows) and the header's tracking/mode buttons 38px → **44px on mobile only** (a media query —
  38px is the right size for a desktop header, and the in-car case is the phone).

*Phone checks owed:* the header light going amber after a real kill (force-stop the app, drive,
reopen), Mark still one-handed at 44px, and a sheet opened from a Modal (Mileage → Tags → New tag)
still behaving with the focus trap in place.

## Phase 5 — structural refactor (highest care; verify on phone before testers)

Ordered so each is a safe standalone step (CI is the only compiler — no giant single commit).
**Done so far: 5.6 and 5.3.** They were taken out of order deliberately: 5.6 is the thing that makes
every other item here cheaper to attempt, so it went first.

1. **`TierReconciler`** — the keystone. One serial dispatcher every trigger posts events to
   (`Fix`, `CarBt`, `Obd`, `Command`, `Motion`); nothing mutates tier fields directly. Fixes the
   two-thread H2 race (`LocationService.kt:491-555` vs `147/279/623/674/757`) and three
   non-volatile findings at once. **The change most able to introduce a silent-stop regression** —
   lands with unit tests for the race and a phone check (drive, red-light hold, park, BT
   connect/disconnect, OBD drive) before it reaches testers.
2. **`ObdSession`** — extract the loop + socket lifecycle (1.1 shipped surgically; this makes it
   testable).
3. **`JsonlRing`** — ✅ done. The four `Web*Buffer` objects are now thin wrappers over one
   `JsonlRing(fileName, maxLines, inclusive, trimEvery, label)`; each keeps its filename, its cap,
   its trim cadence, and its own lock (contract #2 intact — no on-disk change at all). The
   `WebBatteryBuffer` → `WebVehicleBuffer` cross-reference is gone: battery had no trim/select of
   its own and was calling the vehicle buffer's, which is not a design, it is the last stage before
   four copies drift.
   - **The `>` vs `>=` split is a parameter, not an inconsistency to be tidied away**, and it is
     the one thing this refactor could have silently broken. Fixes drain **strictly-after** (one
     per second — re-delivering the boundary is a duplicate); markers/vehicles/battery drain
     **at-or-after** (two can share a second, so a strictly-after cursor on the boundary drops one
     *forever*, while re-delivery is free because all three are written idempotently by the SPA).
     The failure modes are asymmetric and both are silent, so `JsonlRingTest` pins which side each
     of the four is wired to — not merely that the two modes exist.
   - `selectSince(lines, sinceTs, inclusive)` takes no default for `inclusive`, on purpose: a
     caller that hasn't decided whether its events can share a second is a caller for whom either
     answer is silently wrong.
4. **`Clock` seam + the five missing safety-net tests** — `Watchdog.doWork`, the `onStartCommand`
   OFF path, `markDriveStart` resume, `Health` kill-classification, the `startForeground`
   degrade. This is the net built to prevent silent-stop and is currently almost untested.
5. **`BridgeSerializer` / `DriveEndProcessor` / `DriveNotification`** — pure-logic extractions,
   low risk once the above are in. `DriveEndProcessor` finally tests the gas-stop heuristic.
6. **Fast Kotlin-compile CI job** — ✅ done, and done **first**, because it is what makes the rest
   of this phase affordable. There is no JDK on the dev host, so CI is the only Kotlin compiler in
   existence for this repo — and it only ran on `main`, which meant a work branch had *no compiler
   at all* and a syntax error was discovered by shipping. The new `compile` job
   (`compileGithubDebugKotlin` + `compileGithubDebugUnitTestKotlin`, ~2 min) runs on **every**
   branch; the full `build` still runs on main, PRs and dispatch, exactly as before. Test sources
   are compiled too — a test that doesn't compile is indistinguishable from one that doesn't exist.

## Phase 6 — targetSdk 36 + edge-to-edge (hard deadline 2026-08-31, needs device)

Small code job (~1–2 hrs: drop `windowOptOutEdgeToEdgeEnforcement` from themes.xml, add
`ViewCompat.setOnApplyWindowInsetsListener` padding to the ~2 activity roots, bump
`targetSdk=36`), but the only real gate is device verification. Do it on the phone, before the
bump, and keep it off the testers' build until it's been seen to render.

---

## Guardrails — do NOT

- Change the signing key, `applicationId`, or reset `versionCode`.
- Rename any on-disk file, prefs key, backup zip entry, or `DrivetimeNative` method.
- Merge the two OBD→JSON projections (different wire formats) or collapse the four buffer files.
- Turn on R8/minify without first adding `@JavascriptInterface` keep rules (minify-off is
  currently load-bearing for the bridge).
- Ship `TierReconciler` (5.1) or the edge-to-edge change (Phase 6) to testers without a phone
  check first.

## Follow-ups this raised (decisions, not chores)

- **Retire the sideload update plumbing.** With the updater deleted, `version.json`, the server
  `/dl` route, `drivetime/publish-apk.sh` and `ship.sh`'s publish gate have no consumer — CI
  still produces and publishes them, and `ship.sh` still blocks on them. Left in place on
  purpose: unpicking it touches the sibling repo and the ship script. Decide whether the GitHub
  APK release stays as a hand-install artifact (it is harmless) or goes entirely.
- **`ActivityTransitionReceiver` can stop logging and is not token-gated** — correctly, since it
  is `exported="false"` and therefore unreachable by other apps. Noted only so the next reader
  doesn't "fix" it: it is inside the trust boundary, and `auto_trip` is opt-in and off by default.

## Verification model

No JDK/SDK/device on the dev host — CI compiles, the phone verifies. Keep commits small and
CI-green, lean on the unit tests (especially the new safety-net ones in 5.4), and gate the two
behavior-changing structural items (5.1, Phase 6) on a device check before they reach the
internal track. Phases 1–4 are safe to let CI + tests carry.
