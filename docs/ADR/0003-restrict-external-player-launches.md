# ADR 0003 · Restrict external player launches to a channel login

Implemented on 2026-11-23 as part of audit finding F-03.

## Context

The install script opens a live channel by starting an activity from the ADB shell. The player
also takes tune-in details from in-app stream cards, including image sources that it passes to
Coil. Exporting the player let external callers supply those same values.

## Decision

Export `PlayerShortcutActivity` for the install shortcut and keep `PlayerActivity` internal.
The shortcut accepts the `channel` string extra. A login must contain 1–25 ASCII letters,
digits, or underscores. The activity finishes immediately for invalid or missing input. For
a valid login it builds a new explicit intent with the lowercase login and an app-set shortcut
flag. [Manifest](../../app/src/main/AndroidManifest.xml) ·
[PlayerShortcutActivity](../../app/src/main/java/com/purpletv/player/PlayerShortcutActivity.kt)

The player can start playback with the login and use its existing Helix lookup to fill the tune-in
screen while it waits for video. If metadata is unavailable, the login remains the initial label.
The shortcut uses the default followed-channel row. Stream cards continue to supply their
tune-in details and Channels row through `Nav.openStream`.
[PlayerActivity](../../app/src/main/java/com/purpletv/player/PlayerActivity.kt) ·
[Nav](../../app/src/main/java/com/purpletv/ui/Nav.kt)

## Consequences

The public launch contract is limited to a login. Keeping card details behind the internal
activity boundary lets the app show warmed images immediately when a user opens a stream card.
The script and development guide use the new component, so direct ADB shortcuts must use that
component too. [Install script](../../tools/install.sh) · [Development guide](../development.md#install-on-the-tv)

## Verification

Component tests check the manifest, the accepted login format, and the extras on the new player
intent. A local test server verifies that the player fills in tune-in text from app-resolved
metadata. The in-app test checks that a stream card still supplies both images and its Channels row.
[PlayerShortcutTest](../../app/src/test/java/com/purpletv/player/PlayerShortcutTest.kt)

On a temporary Android TV emulator, Android denied a direct shell launch of the player. Missing
and invalid channels left the player closed, and a valid channel opened the player and reached
video decoding. The physical TV was outside this check.
