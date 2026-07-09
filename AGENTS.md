# drivetime-android — Agent Instructions

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that also
hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what the
user actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an
optional support server.

`CLAUDE.md` carries the same instructions; keep the two in sync.

## Read first

- Sibling repo `/home/lindsay/drivetime`: `NATIVE_APP.md` (shell architecture),
  `STANDALONE.md` (why the app bundles its own web snapshot), `AUTH.md` (device-token
  pairing) — before touching the bridge, auth, or sync.
- `README.md` (how the logger works), `ROADMAP.md`, `AUTOMATION.md`.

## Working style

- Be concise; make focused changes; keep secrets out of the repo.
- **Do not ship automatically — only on explicit instruction.**

## The web assets are generated — never hand-edit them

`app/src/main/assets/web/` is a **committed build artifact**, not source: the SPA snapshot
served over `WebViewAssetLoader` at `https://appassets.androidplatform.net/assets/web/` (a
secure origin, so the service worker and IndexedDB replica keep working with no server).

Its source is `drivetime/frontend/`. Refresh it with
`cd /home/lindsay/drivetime && ./sync-web-to-android.sh`. Editing `assets/web/` directly is
always wrong — the next sync overwrites it and the website and phone drift apart.

## Phone first — every frontend change must land here

A change to `drivetime/frontend/` that is only committed in `drivetime` updates the
*website* and nothing else; the phone keeps running the old bundled snapshot. The full
sequence for any frontend change:

```bash
cd /home/lindsay/drivetime
bash ship.sh "message"                  # 1. commit the SPA change
./sync-web-to-android.sh                # 2. rebuild (base=/assets/web/) → this repo's assets
cd ../drivetime-android
bash ship.sh "message"                  # 3. commit+push, await CI APK, publish to /dl
```

Step 3 blocks on the "Build APK" CI run then calls `drivetime/publish-apk.sh --watch <sha>`,
so on return the in-app updater already offers the build. Skipping step 2 or 3 leaves the
phone on stale web assets — the single most common way a "fixed" bug survives a ship.

Verify on the phone, not just in a browser: touch targets, the hardware BACK button (the
shell calls `window.__dtHandleBack()`), offline / no-server behavior, and the
`DrivetimeNative` bridge. Where phone and desktop pull apart, the phone wins.

## No local compiler

There is **no JDK or Android SDK on the dev host** — CI is the only Kotlin compiler, so a
syntax error costs a full CI round-trip. Read Kotlin edits carefully before pushing; two
that have bitten us: `*/` inside a comment closes the block early, and a bare `return` in a
function declared to return `Boolean` won't compile.

`ship.sh` here is intentionally leaner than the generic `/home/lindsay/scripts/ship.sh` (no
ship log to stamp, no local build to gate on); CI plus `--watch` are the pre-publish gate.
`SHIP_SKIP_PUBLISH=1` commits and pushes without waiting for the APK.
