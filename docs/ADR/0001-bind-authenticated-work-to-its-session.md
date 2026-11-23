# ADR 0001 · Bind authenticated work to its session

Implemented on 2026-11-23 as part of audit finding F-02.

## Context

An authenticated Twitch request can outlive sign-out or a new sign-in. Before F-02, a late refresh
could restore cleared credentials, and a request that received a late 401 could retry with the
replacement login or clear it. The same retry path handles GraphQL follow and unfollow mutations.

## Decision

Give each login an opaque, process-local `SessionId`. A successful sign-in creates a fresh identity,
including when the same account signs in again. Clearing a login ends that session, while refreshing
tokens keeps its identity. The store compares the expected identity and applies credential and profile
updates or clears under the same lock. It continues to store the access token, refresh token, and
user ID under the existing preference keys. [TokenStore](../../app/src/main/java/com/purpletv/auth/TokenStore.kt)

Capture the identity when the operation starts and pass it to each request, retry, and status check.
An authentication failure also carries the initiating identity so a screen can clear the login only
if that session is current. When Helix has already cleared the login, its error also identifies the
resulting signed-out state so the screen can request sign-in only while that state is current.
[DeviceAuth](../../app/src/main/java/com/purpletv/auth/DeviceAuth.kt) · [HelixClient](../../app/src/main/java/com/purpletv/twitch/HelixClient.kt)

For example, if the user signs out and signs back in while a channel unfollow is waiting for Twitch,
the earlier response is discarded. The app skips the status check if sign-out occurs before it
can submit the call. The pin and follow revision update only while the initiating
session is current. [FollowApi](../../app/src/main/java/com/purpletv/twitch/FollowApi.kt)

## Consequences

The user ID stays the same across repeated sign-ins, and the access token can change during a
single sign-in. A separate identity lets the app detect a new login and keep work valid across token
rotation. A per-session mutex lets requests that used the same credentials share a refresh, even
when Twitch returns the same access token. The app remembers a 400 or 401 from the refresh
endpoint for that credential pair. Live playback can still use an anonymous request when it starts
signed out or Twitch refuses authentication during the current session.
[DeviceAuth](../../app/src/main/java/com/purpletv/auth/DeviceAuth.kt) · [PlaybackApi](../../app/src/main/java/com/purpletv/twitch/PlaybackApi.kt)

Check the session and submit the HTTP call to OkHttp under the same store lock. Request preparation
and network I/O occur outside that lock. When a session ends, the store cancels its queued and
in-flight calls. The app discards responses from the ended session. Cancellation cannot recall
bytes already sent, so Twitch may still apply a mutation from a request the app started sending.
[DeviceAuth](../../app/src/main/java/com/purpletv/auth/DeviceAuth.kt) · [TokenStore](../../app/src/main/java/com/purpletv/auth/TokenStore.kt)

Reuse the captured identity when scoping cached account data in F-04 and when adding a read-only
unfollow verification retry in F-08.

## Verification

Controlled responses cover sign-out during refresh, a replacement login, a late 401 after signing
back in to the same account, and a session change during follow or unfollow. The tests also cover a
shared refresh for concurrent 401s, anonymous live playback, and an unfollow confirmation opened
before a new sign-in. The dispatch tests end the session during call construction and while a
submitted call waits in the HTTP queue. In both cases, the old call stays off the wire.
[AuthSessionRaceTest](../../app/src/test/java/com/purpletv/auth/AuthSessionRaceTest.kt) ·
[PlaybackApiTest](../../app/src/test/java/com/purpletv/twitch/PlaybackApiTest.kt) ·
[FollowButtonSessionTest](../../app/src/test/java/com/purpletv/ui/FollowButtonSessionTest.kt)
