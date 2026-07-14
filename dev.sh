#!/usr/bin/env bash
# Build drivetime here on the LXC and install it onto the emulator running on the Windows PC.
#
# The split: Windows runs the emulator (GPU + screen), this box runs the compiler. They meet
# over TCP — Windows forwards the emulator's adb port to the LAN, and the LXC's own adb server
# `connect`s to it as a network device. Only ONE adb server is involved (this box's), which is
# why this doesn't fight Android Studio's bundled adb. One-time Windows setup: EMULATOR.md.
#
# Two loops, and only one needs this script:
#
#   SPA / UI work   → NO build. Start the Vite dev server (in ../drivetime), install once with
#                     --dev, and every Svelte edit hot-reloads in the emulator. Nearly all UI
#                     iteration lives here; you should rarely re-run this.
#   Kotlin work     → needs a real compile. That's this.
#
# Usage:
#   ./dev.sh                       # build + install a normal debug APK (bundled SPA snapshot)
#   ./dev.sh --dev                 # build + install pointed at the Vite dev server (live reload)
#   ./dev.sh --dev --watch         # …and rebuild+reinstall on every Kotlin/XML change
#   ./dev.sh --logs                # tail the running app's logcat
#
# Config in ~/.config/drivetime-dev.env:
#   ADB_SERIAL   the emulator as this box dials it, "<windows-ip>:<port>"  (required)
#                e.g. ADB_SERIAL=10.1.1.20:5585  (5585 = the forwarded port, see EMULATOR.md)
#   DEV_HOST     this box's LAN IP, as the emulator sees it   (default: autodetected)
#   DEV_PORT     Vite dev server port                         (default: 5173)
set -euo pipefail
cd "$(dirname "$0")"

# shellcheck source=/dev/null
[ -f "$HOME/.config/drivetime-dev.env" ] && . "$HOME/.config/drivetime-dev.env"

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/lib/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
GRADLE="${GRADLE:-$HOME/.local/lib/gradle-8.11.1/bin/gradle}"
ADB="$ANDROID_HOME/platform-tools/adb"
PKG=org.jupiterns.drivetime
APK=app/build/outputs/apk/github/debug/app-github-debug.apk

DEV_PORT="${DEV_PORT:-5173}"
# The address the EMULATOR must dial to reach this box. NOT 127.0.0.1, and NOT the emulator's
# 10.0.2.2 alias (that means "the machine the emulator runs on" = Windows). The dev server is
# on this box, so it needs this box's real LAN IP.
DEV_HOST="${DEV_HOST:-$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{print $7; exit}')}"

if [ -z "${ADB_SERIAL:-}" ]; then
  echo "ADB_SERIAL is not set — I don't know where your emulator is." >&2
  echo "  echo 'ADB_SERIAL=<windows-ip>:5585' >> ~/.config/drivetime-dev.env" >&2
  echo "See EMULATOR.md for the Windows-side port-forward (one-time, persists across reboots)." >&2
  exit 1
fi

# Bring the emulator up as a network device on THIS box's adb server. Idempotent — a repeated
# connect just says "already connected". `adb -s $ADB_SERIAL <cmd>` then targets it explicitly.
"$ADB" connect "$ADB_SERIAL" >/dev/null 2>&1 || true
DEV=( "$ADB" -s "$ADB_SERIAL" )

for a in "$@"; do
  case "$a" in
    --dev|--watch) ;;
    --logs) exec "${DEV[@]}" logcat --pid="$("${DEV[@]}" shell pidof "$PKG")" ;;
    *) echo "unknown flag: $a" >&2; exit 1 ;;
  esac
done
BUILD_DEV=0; WATCH=0
[[ " $* " == *" --dev "* ]] && BUILD_DEV=1
[[ " $* " == *" --watch "* ]] && WATCH=1

if ! "${DEV[@]}" get-state >/dev/null 2>&1; then
  echo "Can't reach the emulator at $ADB_SERIAL." >&2
  echo "Is it running, and is the Windows port-forward + firewall rule in place? (EMULATOR.md)" >&2
  echo "  On Windows, check:  netsh interface portproxy show v4tov4" >&2
  exit 1
fi

ARGS=(assembleGithubDebug)
if [ "$BUILD_DEV" = "1" ]; then
  DEV_URL="http://$DEV_HOST:$DEV_PORT"
  ARGS+=("-PdevServer=$DEV_URL")
  echo "[dev] shell → $DEV_URL  (live SPA)"
  echo "[dev] start it if it isn't up:  cd ../drivetime && npm --prefix frontend run dev"
else
  echo "[dev] shell → bundled SPA snapshot (app/src/main/assets/web)"
fi

cycle() {
  "$GRADLE" "${ARGS[@]}"
  # -r keeps app data across reinstalls; the stable signing key makes it an in-place update.
  "${DEV[@]}" install -r "$APK"
  "${DEV[@]}" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  echo "[dev] installed + launched on the emulator."
}

cycle

if [ "$WATCH" = "1" ]; then
  command -v inotifywait >/dev/null || { echo "--watch needs inotify-tools (not installed)" >&2; exit 1; }
  echo "[dev] watching app/src for .kt/.xml changes — Ctrl-C to stop."
  while inotifywait -qq -r -e modify,create,delete --include '\.(kt|xml)$' app/src; do
    echo "[dev] change detected — rebuilding…"
    cycle || echo "[dev] build failed; still watching."
  done
fi
