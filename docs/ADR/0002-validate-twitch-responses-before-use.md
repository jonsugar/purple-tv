# ADR 0002 · Validate Twitch responses before use

Implemented on 2026-11-23 as part of audit finding F-01 in commit `1c2d68a`.

## Context

Twitch can return a successful HTTP status with an empty body, unreadable JSON, or missing
fields. Device sign-in, the Home profile load, and the live player already handle
`IOException`. In these paths, a JSON exception could escape the error handler. An empty
Helix profile could open the sign-in screen, and a missing GraphQL stream field could make the
player report a channel as offline.

## Decision

Decode successful responses at the API boundary. Use a shared decoder for a single JSON object,
and check the response structure and required values in the API that uses them. A parse or
validation failure raises `TwitchResponseException`, an `IOException` with a fixed message.
Discard the original JSON exception because its message can include the body. This lets the
existing screens handle the failure without exposing credentials or signed playback data in
the error diagnostics. [TwitchResponse](../../app/src/main/java/com/purpletv/net/TwitchResponse.kt)

Validate the device code, activation URL, and positive expiry and poll intervals before showing
the sign-in code. Require both tokens on initial sign-in and validate the response before
writing either value. If a successful refresh omits the refresh token, reuse the current one.
If Twitch supplies a replacement, require a nonblank string. Keep the expired-code path so the
sign-in screen can request a new code. [DeviceAuth](../../app/src/main/java/com/purpletv/auth/DeviceAuth.kt) ·
[LoginActivity](../../app/src/main/java/com/purpletv/auth/LoginActivity.kt)

Require a `data` array in Helix and one profile with a nonblank account ID and login for the
signed-in profile request. Continue to accept missing optional details such as profile images.
For example, HTTP 200 with `{"data":[]}` for the signed-in profile shows the Home loading error
and preserves the login. A Helix 401 still follows the refresh path and can prompt for sign-in
if Twitch refuses the credentials. [HelixParser](../../app/src/main/java/com/purpletv/twitch/HelixParser.kt) ·
[HelixClient](../../app/src/main/java/com/purpletv/twitch/HelixClient.kt) ·
[MainActivity](../../app/src/main/java/com/purpletv/home/MainActivity.kt)

Require a nonblank playback token and signature before requesting a playlist. An explicit
`stream: null` in the signed-in GraphQL response with no GraphQL errors, or a playlist HTTP 404,
counts as offline. An absent stream field or incomplete token uses the player's retry path.
Keep anonymous playback available when Twitch refuses the signed-in request and the app cannot
recover it through refresh. [PlaybackApi](../../app/src/main/java/com/purpletv/twitch/PlaybackApi.kt) ·
[PlayerActivity](../../app/src/main/java/com/purpletv/player/PlayerActivity.kt)

## Consequences

Response errors use the existing screen recovery paths. Each API defines its required fields,
which lets profile details remain optional and lets list endpoints return an empty array.
The decoder retains the syntax rules of `org.json`, which accepts some nonstandard input such
as unquoted object keys. Validation applies to the parsed object and its required fields.

Coroutine cancellation continues to propagate separately from response errors. For authenticated
requests, the session also has to be current before the app can write credentials or use responses.
[ADR 0001](0001-bind-authenticated-work-to-its-session.md)

## Verification

Mock responses cover empty bodies, unreadable JSON, wrong response shapes, and incomplete tokens.
They verify that failed token validation preserves credentials, that invalid profiles use the
loading error path, and that invalid playback responses stop before the playlist request. The
tests also cover code expiry, refresh-token reuse, authentication failure, anonymous playback,
and both offline responses. [DeviceAuthTest](../../app/src/test/java/com/purpletv/auth/DeviceAuthTest.kt) ·
[HelixClientTest](../../app/src/test/java/com/purpletv/twitch/HelixClientTest.kt) ·
[PlaybackApiTest](../../app/src/test/java/com/purpletv/twitch/PlaybackApiTest.kt)
