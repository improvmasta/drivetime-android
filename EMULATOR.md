# Running drivetime in an emulator, with live UI reload

The goal: see a UI change **in the app** without building or publishing anything.

The shape of it — **the compiler lives on the LXC, the emulator lives on Windows.** Windows has
the hardware acceleration and the screen; the LXC has the source and the toolchain. They meet
over ADB across the LAN. You never open Android Studio to build, never clone the repo on
Windows, and never build over the Samba share (Gradle over SMB is miserable — every file stat
is a network round-trip and file-watching doesn't work).

There are **two loops**, and confusing them is what makes this feel harder than it is:

| Change | What it takes | How long |
|---|---|---|
| **SPA / UI** (Svelte, CSS — nearly all visible work) | nothing. Vite hot-reloads it into the running emulator | instant |
| **Kotlin / native** (logger, bridge, manifest) | a real compile → `./dev.sh` | ~1 min |

## One-time setup

### On the Windows PC

1. Install **Android Studio**. That's the only install — it brings the emulator, the AVD
   manager, `adb` and a system image. You do **not** need a JDK, Gradle, or a copy of the repo.
2. **Device Manager → Create Device** → a Pixel, with a **Google APIs x86_64** system image
   (API 35 or 36 — the app targets 35). Launch it once.
3. If it complains about hardware acceleration: Windows Features → tick **Windows Hypervisor
   Platform** and **Virtual Machine Platform**, reboot.

### On Windows — forward the emulator's adb port to the LAN (once, persists across reboots)

**Do not** try to share the adb *server* over the LAN (`adb -a`). Android Studio runs its own
managed adb server and will kill any server you start on 5037 — you'll see "adb server killed by
remote request" on a loop. Instead, forward the emulator's own adb port and let the LXC's adb
`connect` to it as a network device; only one server (the LXC's) is ever involved.

An emulator shown as `emulator-5554` has its adb port on **5555**. In an **Administrator**
PowerShell/cmd:

```
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=5585 connectaddress=127.0.0.1 connectport=5555
netsh advfirewall firewall add rule name="adb-emulator-lxc" dir=in action=allow protocol=TCP localport=5585 remoteip=10.1.1.15
```

- The port-proxy listens on `5585` (a spare port, so it never collides with the emulator's own
  `5555`) and forwards to `127.0.0.1:5555`. Because it connects *from* localhost, the emulator
  treats it as a local, pre-authorized adb connection.
- The firewall rule is scoped to the LXC (`10.1.1.15`) only — nothing else on the network can
  reach it.
- Both survive reboots, so this is genuinely one-time. If a later emulator comes up on a
  different port (e.g. `emulator-5556` → adb 5557), change `connectport` to match.

To undo: `netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=5585`.

### On the LXC

Already done, but for the record — this host now has its own Android toolchain, so CI is no
longer the only Kotlin compiler:

```
~/.local/lib/jdk-17           # Temurin JDK 17 (no root needed)
~/.local/lib/gradle-8.11.1    # AGP 8.10's floor — keep in step with build.gradle.kts
~/Android/sdk                 # platform-tools, platforms;android-36, build-tools;36.0.0
local.properties              # sdk.dir → the above (gitignored)
```

Point `dev.sh` at the forwarded emulator (already set for this network):

```bash
echo 'ADB_SERIAL=10.1.1.10:5585' >> ~/.config/drivetime-dev.env   # <windows-ip>:<forwarded-port>
```

## Every session

**1. Windows — just have the emulator running.** That's it. No adb command, no window to keep
open: the port-forward and firewall rule from setup are already in place, and Android Studio can
stay open. (Once the emulator is launched you can even quit Android Studio; the emulator keeps
running.)

**2. LXC — start the dev server and install the shell (once):**

```bash
cd ~/drivetime          && npm --prefix frontend run dev     # Vite on 0.0.0.0:5173
cd ~/drivetime-android  && ./dev.sh --dev                    # build + install, pointed at Vite
```

**3. Edit `drivetime/frontend/**` and watch the emulator.** That's the whole loop. No rebuild,
no `sync-web-to-android.sh`, no APK, no push. Kotlin changes need `./dev.sh --dev` again (or
`./dev.sh --dev --watch` to rebuild on save).

Other things worth knowing:

- **`./dev.sh` with no flags** installs the *normal* build — the SPA snapshot bundled in the
  APK — which is what a real user runs. Use it to check a change the way it will actually ship.
- **GPS:** the emulator's Extended Controls (`…`) → Location will inject a position or play
  back a route, which is how you exercise drive detection without driving.
- **`./dev.sh --logs`** tails logcat for the app.

## How the dev-server mode works, and why it's safe

`./dev.sh --dev` builds with `-PdevServer=http://<lxc-ip>:5173`, which lands in
`BuildConfig.DEV_SERVER_URL`. `Shell.startUrl` then loads the Vite dev server instead of the
bundled snapshot, and `WebAuth.isInAppUrl` lets that origin become the WebView's document so
the `DrivetimeNative` bridge keeps working. Vite pushes edits over HMR; the SPA repaints in
place, keeping scroll position and which tab you're on.

That last part widens a real security fence — the one `WebViewActivity` warns about in as many
words ("widen isInAppUrl and you hand DrivetimeNative to whatever you let in"), because the
WebView carrying the bridge carries settings writes, the device token, backup and tracking
control. **Two independent gates keep it out of anything a user can install:**

1. `DEV_SERVER_URL` is only set in the **`debug` build type**, and defaults to `""` everywhere
   else — including debug builds that didn't pass the flag.
2. `Shell.DEV_URL` re-checks `BuildConfig.DEBUG` at runtime. Play only ever receives
   `bundlePlayRelease`, where that is false, so the dev branch is dead code R8 folds away.

`WebAuthTest.thereIsNoDevOriginInAnOrdinaryBuild` pins both: a build nobody passed `-PdevServer`
to must have no dev origin at all. And the match is on the **parsed host**, never a prefix — a
`startsWith(DEV_URL)` test would accept `http://10.1.1.15:5173.evil.example.com/`.

Two consequences of the dev origin being a *different* origin from the bundled one:

- **Its IndexedDB is separate.** Dev-mode drives don't pollute the real replica — good — but a
  storage-migration bug is not something dev mode will show you. Check those on a normal build.
- **No service worker.** Vite disables it in dev (`devOptions.enabled: false`), which is why
  you never fight a stale cached shell here. (On a *real* build the SW is exactly what once made
  a new APK look unchanged — see the SW-update note in the repo history.)

## Why not the alternatives

- **Android Studio on Windows, off the Samba share.** Gradle over SMB stats every file across
  the network and can't use file-watching; the IDE indexes at a crawl. It works and it's awful.
- **Android Studio on Windows, off a git clone.** Fine, but it means a `git pull` in the loop
  and a second place for the build to drift. The LXC already has the source; give it the
  compiler instead.
- **Emulator on the LXC via `/dev/kvm`.** Removes Windows entirely, but needs Proxmox host
  changes to pass the device into the container, plus scrcpy/VNC to actually see it. More
  fragile than using the machine that already has a GPU and a monitor attached.
