# Development

Purple TV was built and measured on a Sony KD-55XE9005 (Android TV 9, MediaTek MT5891, 2 GB of RAM).

## Build and test

You need JDK 17 and the Android SDK with platform 34.

```bash
./gradlew testReleaseUnitTest    # unit tests
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

Release builds are signed with the release key when `purpletv.storeFile`, `purpletv.storePassword`,
`purpletv.keyAlias` and `purpletv.keyPassword` are set in `~/.gradle/gradle.properties`, and with the
debug key otherwise. Android won't install an APK signed with one key over an app signed with another,
so uninstall the published build before installing your own.

## Install on the TV

For this checkout's Sony TV connection, relay workaround, and signing instructions, see
[AGENTS.md](../AGENTS.md). Use that workflow if direct ADB reports `No route to host` while the TV's
ADB port accepts TCP connections.

`tools/install.sh` builds the app, installs it over the network and compiles it in one step. It uses
the ADB client of [CleanYourTV](https://github.com/Gathin23/cleanyourtv), expected in
`~/development/cleanyourtv` with its `tools/venv`. `CLEANYOURTV` changes that path and `TV_HOST` the
TV's address. `tools/install.sh --start <channel>` also opens a channel in the player, and
`tools/tv.sh '<command>'` runs a shell command on the TV.

An app installed from a computer runs uncompiled until Android compiles it in the background, usually
overnight, so `install.sh` compiles it right after installing.

## Measure

```bash
L='SurfaceView - com.purpletv/com.purpletv.player.PlayerActivity#0'
tools/tv.sh "for i in \$(seq 1 35); do dumpsys SurfaceFlinger --latency '$L'; sleep 2; done" > lat.txt
python3 tools/sf-latency.py lat.txt
tools/tv.sh 'logcat -d -s PurpleTV:*'               # decoder stats every 5 s, rebuffers, format, speed
tools/tv.sh 'dumpsys meminfo com.purpletv'          # memory
tools/tv.sh 'dumpsys gfxinfo com.purpletv reset'    # then press the keys, then run it without "reset"
```

### Player

A 1080p60 stream, about 70 seconds of `dumpsys SurfaceFlinger --latency` on the player's SurfaceView,
24 October 2026:

| | Official app | Purple TV |
|---|---|---|
| Frames per second on screen | 27.9 | 60.0 |
| Intervals longer than one refresh | 22.6% | 0% and 0.9% (two runs, chat open and closed) |
| Stutters over 80 ms | 38 | 0 |
| Frames dropped by the decoder | not measured | 0 |
| App CPU | 74% | 24-25% |

The late intervals in Purple TV's second run come paired with as many short ones, which points to
uneven delivery rather than dropped frames. Memory was about 136 MB for the app, with 403 MB free on
the TV out of 1.9 GB.

After a switch from the Channels strip (1080p60, 73 seconds, 25 October 2026): 60.0 fps, 1 long
interval out of 4,379, no dropped frames, app CPU 23.6%.

### Home

Memory with the rows loaded, then `dumpsys gfxinfo` during 20 presses of Down, 25 October 2026:

| | v1 | v2 |
|---|---|---|
| Memory (total PSS) | 70.7 MB | 42.6 MB |
| Frames over 16 ms | 100% | 79% |
| Median frame time | 34 ms | 25 ms |

On this TV the 1080p interface never goes below 16 ms a frame, v1 included. `gfxinfo` also counts the
frames that are only waiting in the compositor's queue, so a system trace (`atrace`) during the same
20 presses gives a truer picture. Before and after the scrolling fixes (card pool, no baseline
alignment in layouts with weights, images that keep loading off screen), with the app compiled both
times:

| | Before | After |
|---|---|---|
| Frames over 50 ms | 18 | 8-9 |
| Frames over 80 ms | 8 | 2-3 |
| Longest frame | 136 ms | about 100 ms |
| Time in long frames | 2,170 ms | about 1,100 ms |

What's left is about one frame of 70 to 100 ms each time a new row comes into view, the time this CPU
needs to fill six cards.

## Notes

### Following and recorded playback

Following reuses the main navigation rail, tabs, category cards and stream grids. Channel banner
cards open a separate channel activity with paged Videos and Clips rows. Each clip filter captures
one UTC time window for all of its pages. Replacing a filter cancels the previous pager, so a late
response cannot replace the current results. The complete followed-channel list is sorted before
display, with profiles loaded in batches as the grid scrolls. Lists refresh after five minutes when
Following is shown again, and account changes clear their state.

`FollowingApi` reads the internal `currentUser.followedGames` connection. It uses `nodes`, not
`edges`, has no cursor argument, and accepts at most 100 categories. `RecordedPlaybackApi` resolves signed VOD playlists and clip
renditions when playback starts. These authenticated GraphQL operations use the existing device
login, retry a 401 after one token refresh, and report missing or restricted content separately from
network failures. Tokens and signed playback URLs are not written to logs.

`FollowApi` reads channel follow status and all followed categories, including offline categories.
It adds and removes follows through Twitch GraphQL with the existing signed-in TV client. The
category header, channel page, and live player's heart button use the same controller. It asks for
confirmation before removing a follow, gives Cancel remote focus, and leaves the follow state as it
was after a failed write. A confirmed change refreshes Home, Following, and the category stream
order. The current stream keeps playing after an unfollow from the player.

`RecordedPlayerActivity` uses the same SurfaceView and decoder policy as live playback, with its own
controls. Focusing the timeline allows ten-second seeking; media transport keys also work. VOD
positions are saved every ten seconds and on leaving playback, keyed by account and video in the
`vod_progress` preferences. Start over and completed playback clear the saved position.

Run `./gradlew testDebugUnitTest assembleDebug lintDebug` for the local checks. Before accepting a
deployed build, use the 1280×720 virtual TV to check each Following tab, multiple pages of channels,
category navigation, Watch Live, every clip filter, VOD and clip seeking, quality changes, and VOD
resume after restarting the app. Check Back restores the selected card, and try empty results,
missing images, unavailable recordings, network failures, and signing in with another account.
On a followed channel page, a followed category page, and in the live player, check the name in the
unfollow dialog, Cancel focus, and focus restoration after Cancel or Back. Confirm a removal using
a test follow and check the Follow control, the affected lists, continued playback, and re-following.

### Debug mode

Open **Profile → Debug** with the remote. Enable debug mode to make **Disable networking**
available. A bug icon identifies debug mode on each screen, and the label below it lists the active
flag. The player keeps this indicator visible when its controls hide. Both switches start off when
the app process starts. Turning debug mode off clears the networking flag.

Use an unloaded Browse tab to check the offline error, then turn the networking flag off and select
the same tab to retry. The simulation affects Purple TV only and preserves the Twitch sign-in.
The unit tests use a local server to check blocked requests, interruption of media and chat, and a
failed Unfollow after the control has already loaded the Following state.

### Platform notes

- The launcher banner (`res/drawable-nodpi/banner.png`) is generated with
  `python3 tools/make-banner.py app/src/main/res/font/inter_bold.ttf app/src/main/res/drawable-nodpi/banner.png`.
- The player restarts the video decoder on every resolution change. On this TV the MediaTek decoder,
  when reused, kept 1920×1088 buffers with a 720p crop and showed the picture zoomed past the screen
  edges (see `player/DecoderPolicy.kt`).
- Every channel switch builds a new player. The same player, stopped and prepared again, showed the
  next channel at about 52 fps, with frames held for two or three refreshes.
- With the developer option "Don't keep activities" turned on, Android closes the home screen when a
  channel opens, and going back starts it again. Leave it off.
