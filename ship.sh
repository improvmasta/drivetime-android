#!/usr/bin/env bash
# Ship drivetime-android: gate on a local build + tests, then commit and push.
#
# What a push to `main` actually delivers: CI builds the Play AAB and uploads it to the
# **internal testing track**, and that is the only channel — every install comes from Play.
# There is no APK to publish and nothing to wait for afterwards. (This script used to block on
# the CI APK and hand off to drivetime/publish-apk.sh, which pushed the APK to the server's
# /dl for the in-app updater. The updater is deleted — Play forbids an app updating itself —
# so the publisher, the /dl route and that whole gate are gone.)
#
# The gate is now LOCAL. There is a JDK + Android SDK on this host (see EMULATOR.md), so the
# compiler is no longer CI's alone: a syntax error or a red test is caught here in a couple of
# minutes instead of costing a full CI round-trip. `SHIP_SKIP_GATE=1` skips it for a docs-only
# commit.
#
# Usage:
#   SHIP_TOOL=claude bash ship.sh "commit message"
#   SHIP_SKIP_GATE=1 SHIP_TOOL=claude bash ship.sh "docs: …"   # no local build (docs/CI only)
set -e
[ -z "${1:-}" ] && { echo "usage: bash ship.sh \"message\""; exit 1; }
cd "$(dirname "$0")"

case "${SHIP_TOOL:-codex}" in
  claude) DEFAULT_COAUTHOR="Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" ;;
  *)      DEFAULT_COAUTHOR="Co-Authored-By: Codex <noreply@openai.com>" ;;
esac
COAUTHOR="${SHIP_COAUTHOR:-$DEFAULT_COAUTHOR}"

# --- the gate: build + test before anything leaves this box ---------------------------------
if [ "${SHIP_SKIP_GATE:-0}" != "1" ]; then
  export JAVA_HOME="${JAVA_HOME:-$HOME/.local/lib/jdk-17}"
  export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/sdk}"
  GRADLE="${GRADLE:-$HOME/.local/lib/gradle-8.11.1/bin/gradle}"
  if [ -x "$GRADLE" ] && [ -d "$ANDROID_HOME" ]; then
    echo "[ship] Gate: assembling + running unit tests locally…"
    "$GRADLE" assembleGithubDebug testGithubDebugUnitTest
    echo "[ship] Gate passed."
  else
    # Don't silently ship unverified Kotlin just because a toolchain moved.
    echo "[ship] No local toolchain ($GRADLE / $ANDROID_HOME) — refusing to ship unverified." >&2
    echo "[ship] Install it (EMULATOR.md) or re-run with SHIP_SKIP_GATE=1 if this is docs-only." >&2
    exit 1
  fi
fi

git add -A
git commit -m "$1

$COAUTHOR"

# Branch-aware: only main reaches CI's Play upload.
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
MAIN="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's@^origin/@@')"
MAIN="${MAIN:-main}"
if [ "$BRANCH" != "$MAIN" ]; then
  echo "[ship] Committed on '$BRANCH' (not '$MAIN'); nothing pushed." >&2
  echo "[ship] Merge + push: git checkout $MAIN && git merge --ff-only $BRANCH && git push origin $MAIN" >&2
  exit 0
fi

git push origin "$MAIN"
SHA="$(git rev-parse HEAD)"
echo "[ship] Pushed $SHA. CI builds the AAB and uploads it to Play's internal track."
echo "[ship] Watch it:  gh run watch --repo improvmasta/drivetime-android"
