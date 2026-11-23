# Purple TV agent notes

This is a native Android TV app that uses Kotlin and Android views. Preserve the existing fonts,
colours, layouts, and remote focus behaviour. Read `docs/development.md` for the development workflow.
Check the worktree before editing. Preserve unrelated changes.

## Build and deploy to the virtual TV

Use the existing `Television_720p` Android Virtual Device (AVD). Check the ADB device list first.

```bash
"$HOME/Library/Android/sdk/platform-tools/adb" devices -l
```

If the AVD is already running and the display works, use that instance. If its screen is black,
close that instance before restarting it. The command below starts a visible window, skips Quick
Boot snapshots, and uses software graphics. It preserves the AVD's data.

```bash
"$HOME/Library/Android/sdk/emulator/emulator" \
  -avd Television_720p -port 5554 -no-snapshot -gpu swiftshader
```

Keep the emulator running. In a second Terminal or agent session, verify that `emulator-5554` is in
state `device`, `emu avd name` reports `Television_720p`, and `sys.boot_completed` returns `1`.
If ADB lists a different serial for this AVD, use that verified emulator serial throughout.

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" devices -l
"$ADB" -s emulator-5554 emu avd name
"$ADB" -s emulator-5554 shell getprop sys.boot_completed
```

From the project folder, run these commands in order. Check that Gradle succeeds and the install
reports `Success` before opening the app. `install -r` keeps the emulator's app data. If Android
reports a signing mismatch, follow [Signing and app data](#signing-and-app-data).

```bash
./gradlew testReleaseUnitTest assembleRelease
"$ADB" -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
"$ADB" -s emulator-5554 shell am start -W -n com.purpletv/.home.MainActivity
```

Check that the app screen renders. If the emulator window is black after boot, press Home on the
emulator to check whether the Android TV launcher renders. Use `adb exec-out screencap -p` with the
verified serial to check what Android draws when the window is blank. For player changes, check
that the shortcut renders live video. Use the login of a channel that is live at the time of the
check.

```bash
"$ADB" -s emulator-5554 shell am start -n com.purpletv/.player.PlayerShortcutActivity \
  --es channel twitchplayspokemon
```

After verification, use the `MainActivity` launch command again to leave the emulator running with
Purple TV Home in the foreground. If the agent sandbox blocks ADB or access to the Gradle cache,
use the permitted elevated execution path.

## TV connection

The physical TV was a Sony BRAVIA at `192.168.1.129:5555` on 17 November 2026. Check its current IP
before connecting. The virtual TV is a separate device, usually `emulator-5554`.

On this Mac, direct ADB connections returned `No route to host` although this TCP check succeeded:

```bash
/usr/bin/nc -vz -G 3 -w 3 192.168.1.129 5555
```

Enabling Android Studio under macOS Privacy & Security → Local Network and restarting ADB did not
resolve this case. Both Homebrew Python and system Python also failed to reach the TV directly.
The working connection used a relay on `127.0.0.1:15555`.

### Start the relay

First, check whether a relay already listens on port `15555`. Use one relay at a time.

The user previously used this CleanYourTV relay from a normal Terminal, outside the agent shell:

```bash
cd /Users/jonsugar/Projects/TV/cleanyourtv
tools/venv/bin/python -I tools/relay.py 192.168.1.129 --tv-port 5555 --port 15555
```

Ask the user to leave that Terminal open during device work. The relay must print its listening
address before ADB connects.

If the Python relay also reports `No route to host`, use this project's helper from the project folder:

```bash
python3 tools/adb-relay.py 192.168.1.129 --tv-port 5555 --port 15555
```

This helper uses `/usr/bin/nc` for the TV connection. That method succeeded during this session.
The helper listens only on `127.0.0.1`. Keep it running until device work finishes.
The helper replaces the temporary `/private/tmp/purpletv-adb-relay.py` used during troubleshooting.

### Connect and select the TV

```bash
adb connect 127.0.0.1:15555
adb devices -l
```

Check that `127.0.0.1:15555` identifies the BRAVIA and has state `device`.
If authorization is required, ask the user to accept the debugging prompt on the TV.
Always select the physical TV with `-s 127.0.0.1:15555` during deployment.
Use `-s emulator-5554` only for an emulator task after checking its serial.

If `adb` is absent from PATH, the SDK copy is at:
`/Users/jonsugar/Library/Android/sdk/platform-tools/adb`.

## Build and install

Build the current release APK from the project folder:

```bash
./gradlew testReleaseUnitTest assembleRelease
```

Gradle writes `app/build/outputs/apk/release/app-release.apk`.
The previous delivery copy was `purple-tv-0.1.apk` in the same folder.
Gradle does not refresh that delivery copy. Copy the new `app-release.apk` after each build if a named copy is needed.

Use the user's current deployment authorization. For an authorized TV update, run these commands in order:

```bash
adb -s 127.0.0.1:15555 install -r app/build/outputs/apk/release/app-release.apk
adb -s 127.0.0.1:15555 shell cmd package compile -m speed -f com.purpletv
adb -s 127.0.0.1:15555 shell am start -n com.purpletv/.home.MainActivity
```

Check that installation and compilation each report `Success`. Check that the app launches.
If Gradle cannot access its cache because of the agent sandbox, use the permitted elevated execution path.

## Signing and app data

The release configuration uses a configured release key, or the local debug key when no release key is configured.
The build installed on the TV on 17 November 2026 used the local debug key.
Its signing certificate SHA-256 was:

```text
892a3e664373e303b2f310ee7d374bdfe06466bf7cb2d6b5521b380fd6cfef96
```

Preserve `~/.android/debug.keystore`. Future builds with the same key can update this installation with `install -r`.
Check signing certificates if Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

The original published APK used another key. The user authorized its removal and signed in again after this replacement.
That authorization covered the replacement in this session. It does not authorize future app removal.
Ask for explicit permission before an uninstall that clears app data, unless the user already authorized that action.
Explain that an uninstall removes the local login, settings, and saved VOD positions.

## Finish device work

The user can stop a foreground relay with Ctrl-C. Stop an agent-owned relay after device work finishes.
Stopping the relay ends its ADB connection. The installed app continues to work independently.

The user confirmed that the new app works on the physical TV, including Following → Live and Watch Live.
Treat this as a dated result. Check future changes on the device when requested.
