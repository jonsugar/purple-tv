#!/usr/bin/env bash
# CleanYourTV's tools/tv.py pointed at the TV: one shell command, screenshots, logs, remote keys.
#
# Usage: tools/tv.sh '<shell command>'
#        tools/tv.sh --screenshot shots/home.png [DPAD_DOWN ...] [--wait SECONDS]
set -euo pipefail
CLEANYOURTV="${CLEANYOURTV:-/home/vito/development/cleanyourtv}"
export TV_HOST="${TV_HOST:-192.168.1.193}"
exec "$CLEANYOURTV/tools/venv/bin/python" -I "$CLEANYOURTV/tools/tv.py" "$@"
