#!/usr/bin/env bash
# Build Purple TV, install it on the TV and, with --start, open a channel in the player.
#
# Usage: tools/install.sh [--start <channel>]
# The TV is reached with CleanYourTV's tools/tv.py: CLEANYOURTV points at that repo,
# TV_HOST at the TV (defaults below).
set -euo pipefail
cd "$(dirname "$0")/.."
CLEANYOURTV="${CLEANYOURTV:-/home/vito/development/cleanyourtv}"
export TV_HOST="${TV_HOST:-192.168.1.193}"
PY="$CLEANYOURTV/tools/venv/bin/python"

CHANNEL=""
if [ "${1:-}" = "--start" ]; then
  CHANNEL="${2:?usage: tools/install.sh [--start <channel>]}"
  # A Twitch login, and nothing that could reach the TV's shell as anything else.
  [[ "$CHANNEL" =~ ^[A-Za-z0-9_]{1,25}$ ]] || { echo "not a Twitch login: $CHANNEL" >&2; exit 2; }
fi

export JAVA_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
./gradlew assembleRelease -q --console=plain

APK=app/build/outputs/apk/release/app-release.apk
"$PY" -I "$CLEANYOURTV/tools/b64push.py" "$APK" /data/local/tmp/purpletv.apk > /dev/null
OUT=$("$PY" -I "$CLEANYOURTV/tools/tv.py" 'pm install -r /data/local/tmp/purpletv.apk; rm -f /data/local/tmp/purpletv.apk')
echo "$OUT"
case "$OUT" in
  *Success*) ;;
  *) echo "pm install failed" >&2; exit 1 ;;
esac
# A fresh install runs interpreted until the TV's overnight compile; compiled now, Home scrolls
# with about half the long frames.
"$PY" -I "$CLEANYOURTV/tools/tv.py" 'cmd package compile -m speed -f com.purpletv' > /dev/null

if [ -n "$CHANNEL" ]; then
  "$PY" -I "$CLEANYOURTV/tools/tv.py" "am start -n com.purpletv/.player.PlayerShortcutActivity --es channel $CHANNEL"
fi
