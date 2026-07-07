#!/usr/bin/env bash
# Ship drivetime-android: commit + push, then wait for the CI APK build and publish it so
# the app's in-app updater offers the update.
#
# The APK is *built* by this repo's "Build APK" workflow but *published* from the drivetime
# repo (whose server serves /dl), so after pushing we hand off to drivetime/publish-apk.sh
# --watch <sha>: it blocks on this exact CI run and publishes the artifact on success (or
# exits non-zero, publishing nothing, if the build goes red). One command ships + delivers.
#
# Usage:
#   SHIP_TOOL=claude bash ship.sh "commit message"
#   SHIP_SKIP_PUBLISH=1 SHIP_TOOL=claude bash ship.sh "msg"   # commit+push only, no wait
#   DRIVETIME_PUBLISH=/path/to/publish-apk.sh ...             # override the publisher path
#
# This repo has no CLAUDE.md/codex.md ship log to stamp, and no locally-runnable build
# (no Android SDK on the dev host — CI is the compiler), so this is intentionally leaner
# than the generic /home/lindsay/scripts/ship.sh; CI + --watch are the pre-publish gate.
set -e
[ -z "${1:-}" ] && { echo "usage: bash ship.sh \"message\""; exit 1; }
cd "$(dirname "$0")"

case "${SHIP_TOOL:-codex}" in
  claude) DEFAULT_COAUTHOR="Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>" ;;
  *)      DEFAULT_COAUTHOR="Co-Authored-By: Codex <noreply@openai.com>" ;;
esac
COAUTHOR="${SHIP_COAUTHOR:-$DEFAULT_COAUTHOR}"

git add -A
git commit -m "$1

$COAUTHOR"

# Branch-aware: publish only what actually reached the default branch.
BRANCH="$(git rev-parse --abbrev-ref HEAD)"
MAIN="$(git symbolic-ref --quiet --short refs/remotes/origin/HEAD 2>/dev/null | sed 's@^origin/@@')"
MAIN="${MAIN:-main}"
if [ "$BRANCH" != "$MAIN" ]; then
  echo "[ship] Committed on '$BRANCH' (not '$MAIN'); nothing pushed or published." >&2
  echo "[ship] Merge + push: git checkout $MAIN && git merge --ff-only $BRANCH && git push origin $MAIN" >&2
  exit 0
fi

git push origin "$MAIN"
SHA="$(git rev-parse HEAD)"

if [ "${SHIP_SKIP_PUBLISH:-0}" = "1" ]; then
  echo "[ship] Pushed $SHA. SHIP_SKIP_PUBLISH=1 → not waiting for CI / publishing."
  exit 0
fi

PUBLISH="${DRIVETIME_PUBLISH:-$HOME/drivetime/publish-apk.sh}"
if [ ! -x "$PUBLISH" ]; then
  echo "[ship] Pushed $SHA, but the publisher isn't at $PUBLISH." >&2
  echo "[ship] Publish manually once CI is green:  $PUBLISH --watch $SHA" >&2
  exit 0
fi

echo "[ship] Pushed $SHA. Waiting for the CI APK build, then publishing to /dl…"
exec "$PUBLISH" --watch "$SHA"
