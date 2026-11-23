# ADR 0004 · Reconcile acknowledged channel unfollows with a status read

Implemented on 2026-11-23 as part of audit finding F-08.

## Context

Twitch can acknowledge a channel unfollow and then fail to return the follow status. The app
previously reported a failed unfollow and left the pin and cached lists unchanged. A viewer who
tried again could send a second unfollow for a channel Twitch had already removed.

## Decision

After Twitch acknowledges the mutation, keep the channel pending in memory for the initiating
session. Check its authenticated follow status and retry that read once if it fails. Each check
has a 15-second limit. While the result is unknown, the follow control offers a status check.
Reopening the channel in the same session can also resume verification. Each of these checks
sends a read request. [FollowApi](../../app/src/main/java/com/purpletv/twitch/FollowApi.kt) ·
[FollowButton](../../app/src/main/java/com/purpletv/ui/FollowButton.kt)

A response that confirms the channel is unfollowed removes its pin and advances the follow
revision once under the session lock. A response that confirms the channel is still followed
retains the pin and ends verification. The viewer can then confirm a new unfollow. A rejected
mutation reports failure immediately. Category unfollows continue to use the category returned
by the mutation as confirmation. [ADR 0001](0001-bind-authenticated-work-to-its-session.md)

## Consequences

A failed verification leaves the result uncertain until a read succeeds. Signing out, signing
in again, or restarting the app ends the pending operation. A new session reads the current
Twitch state and cannot publish pin cleanup or list changes on behalf of the previous session.

## Verification

Controlled responses check the automatic retry, the viewer's status check, and both confirmed
outcomes. They also check a same-account sign-in during a retry. The UI check verifies Cancel
focus, the control label, and pin retention or removal. Continued live playback remains a
manual check. [FollowApiTest](../../app/src/test/java/com/purpletv/twitch/FollowApiTest.kt) ·
[DebugOutageIntegrationTest](../../app/src/test/java/com/purpletv/net/DebugOutageIntegrationTest.kt) ·
[AuthSessionRaceTest](../../app/src/test/java/com/purpletv/auth/AuthSessionRaceTest.kt)
