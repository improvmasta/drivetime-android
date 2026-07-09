# drivetime-android — Claude Context

The native Android shell for **drivetime**: a two-tier GPS logger + OBD-II reader that
also hosts the drivetime SPA in a WebView. This repo is the **product** — the APK is what
the user actually runs. The website (`drivetime.jupiterns.org`) is a convenience view of an
optional support server.

Sibling repo: `/home/lindsay/drivetime` (SPA + FastAPI backend). Read its `NATIVE_APP.md`
(shell architecture), `STANDALONE.md` (why the app bundles its own web snapshot), and
`AUTH.md` (device-token pairing) before touching the bridge, auth, or sync.

## Behavior

- Be concise and make focused changes; prefer editing existing files.
- Keep secrets out of the repo.
- **Do not ship automatically — only on explicit instruction.**

## The web assets are generated — never hand-edit them

`app/src/main/assets/web/` is a **committed build artifact**, not source. It is the SPA
snapshot the app serves over `WebViewAssetLoader` at
`https://appassets.androidplatform.net/assets/web/` (a secure origin, so the service worker
and the IndexedDB replica keep working with no server).

Its source lives in `drivetime/frontend/`. To refresh it:

```bash
cd /home/lindsay/drivetime && ./sync-web-to-android.sh
```

Editing files under `assets/web/` directly is always wrong — the next sync silently
overwrites it, and the website and the phone drift apart.

## Phone first: update always, ship only when told

A change to `drivetime/frontend/` that is only committed in `drivetime` updates the
*website* and nothing else — the phone keeps running the old bundled snapshot. So the two
halves are deliberately separate:

**Update (always, unprompted).** Every frontend change ends with `drivetime`'s
`./sync-web-to-android.sh`, which refreshes `app/src/main/assets/web/` here. It leaves the
new snapshot in this repo's **working tree** and pushes/publishes nothing. A dirty
`assets/web/` is therefore the correct, expected state: it means the app carries the change
and is ready to ship. **Do not** run `ship.sh` just to clean it up.

**Ship (only on explicit instruction).** Shipping means **both repos, ending in a published
APK** — never one without the other:

```bash
cd /home/lindsay/drivetime
SHIP_TOOL=claude bash ship.sh "message"       # 1. drivetime: commit + push
./sync-web-to-android.sh                      # 2. re-sync if anything changed since
cd ../drivetime-android
SHIP_TOOL=claude bash ship.sh "message"       # 3. commit+push, await CI APK, publish to /dl
```

Step 3 blocks on the "Build APK" CI run then calls `drivetime/publish-apk.sh --watch <sha>`,
so a ship is not finished until the in-app updater is offering the new APK. Shipping
`drivetime` alone leaves the phone on the old snapshot — the single most common way a "fixed"
bug survives a ship.

The one exception: a commit here that changes **nothing the app runs** (docs, CI config) can
go up with `SHIP_SKIP_PUBLISH=1 bash ship.sh "…"` — pushed, no APK built for users. Anything
touching `app/` or `assets/web/` publishes.

Verify on the phone, not just in a browser: touch targets, the hardware BACK button (the
shell calls `window.__dtHandleBack()`), offline / no-server behavior, and the
`DrivetimeNative` bridge. Where phone and desktop pull apart, the phone wins.

## No local compiler

There is **no JDK or Android SDK on the dev host** — CI is the only Kotlin compiler, so a
syntax error costs a full CI round-trip. Read Kotlin edits carefully before pushing; two
that have bitten us: `*/` inside a comment closes the block early, and a bare `return` in a
function declared to return `Boolean` won't compile.

`ship.sh` here is intentionally leaner than the generic `/home/lindsay/scripts/ship.sh`
(no ship log to stamp, no local build to gate on); CI plus `--watch` are the pre-publish
gate. `SHIP_SKIP_PUBLISH=1` commits and pushes without waiting for the APK.

## Read also

- `README.md` — how the logger works (tiers, drive detection, durable upload).
- `ROADMAP.md` — robustness plan + the Modes & Routines control API.
- `AUTOMATION.md` — the control surface for routines/shortcuts.
- `AGENTS.md` — the same instructions for non-Claude agents (keep in sync with this file).
