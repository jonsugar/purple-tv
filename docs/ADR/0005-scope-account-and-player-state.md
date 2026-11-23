# ADR 0005 · Scope Home data to a login and Channels rows to a player launch

Implemented on 2026-11-23 as part of audit finding F-04.

## Context

Search and the live player reuse followed streams and recommendations from Home. The navigation
rails reuse the profile avatar. A replacement login can finish before its Home load, while an
earlier Home request or image load is still pending. The player's last card row also used to
remain available to later launches.

## Decision

Tag each Home snapshot with the process-local session identity from [ADR 0001](0001-bind-authenticated-work-to-its-session.md).
Publish the followed streams, their avatars, and recommended categories together under the
session check in the token store. Publish the profile separately so the avatar can appear while
the rows load. Each read checks the current identity and returns empty content if it differs.
A session change drops the previous snapshot and clears the views that used it, including any
pending profile image loads. Search can still fetch public top categories, and the player can
show followed streams from the active snapshot when it has them.
[HomeSnapshot](../../app/src/main/java/com/purpletv/home/HomeSnapshot.kt)

Give each in-app Channels row a single-use key in the explicit player intent. The app keeps the
row in memory until the matching player takes it. The receiving player checks the row's session
and owns the row for the rest of that launch. A later session change drops both pending and
displayed rows from the ended session. The shortcut from [ADR 0003](0003-restrict-external-player-launches.md)
continues to forward the channel login and its shortcut flag. It uses the current followed row
when available. [Nav](../../app/src/main/java/com/purpletv/ui/Nav.kt) ·
[PlayerLaunchRows](../../app/src/main/java/com/purpletv/player/PlayerLaunchRows.kt) ·
[PlayerActivity](../../app/src/main/java/com/purpletv/player/PlayerActivity.kt)

## Consequences

Category rows can contain many pages of streams. The in-memory handoff keeps those lists out of
activity intents, which carry only a small key. The app bounds the number of pending handoffs.
If a row is unavailable after process recreation or eviction, the player can still open the
channel and use the active session's followed streams.

## Verification

The account-state test holds one account's Home response while a new login waits for its profile
and then receives a load error. It checks Search, playback, and the avatar views before and after
the late response. Player tests cover independent launches, out-of-order launches from cards,
and replacing a login for the same account.
[AccountStateTest](../../app/src/test/java/com/purpletv/home/AccountStateTest.kt) ·
[PlayerShortcutTest](../../app/src/test/java/com/purpletv/player/PlayerShortcutTest.kt)
