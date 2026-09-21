# Activity Gen for Android

Activity Gen writes generated activity records directly to Health Connect on your Android phone, with no server, Google sign-in, or OAuth setup.

## Install on your phone

1. Use a phone running **Android 17 or newer**, with Google Play services, in its personal profile. Health Connect is built into Settings; search for **Health Connect** there. It does not support work profiles. See [Google's Health Connect guidance](https://developer.android.com/health-and-fitness/health-connect/availability).
2. Download the APK from the [latest GitHub release](https://github.com/calvinbui/health-connect-activity-generator/releases/latest), then open it in Files on your phone. If prompted, allow **Install unknown apps** for the app opening the APK, then install it. Published APKs are debug-signed builds for personal installation and share the same signing key, so you can install a newer version over an earlier one to retain settings and receipts. Each release includes its changes and a SHA-256 checksum. You can also [build from source](#build-from-source).
3. Open **Activity Gen** and grant its requested Health Connect write permissions. These cover exercise, distance, steps, and mindfulness when supported by your phone.
4. Select today or a date from the last 30 days and tap **Generate available sessions**. Use **Open Health Connect** to inspect the stored records and give your destination fitness app permission to read them.

The APK can also be installed over USB after enabling USB debugging and accepting your computer on the phone:

```sh
adb install -r health-connect-generator-1.4.1.apk
```

## Appearance

The launcher name is **Activity Gen**. The app uses Google's standard Material 3 components for buttons, switches, cards, text fields, dialogs and the date picker. It follows the phone's light/dark setting and uses wallpaper-derived colors where supported, with the default Material palette as the fallback. No custom button backgrounds or fixed color palette are used. Connection status includes clear ticks or warning symbols. Manual and automatic results show a status heading, timestamp, and aligned session counts; errors and catch-up details remain visible.

See [Material Components](https://github.com/material-components/material-components-android).

## Activities

The default time zone is `Australia/Sydney`. Each session lasts one hour; times below are in the configured time zone.

| Start | Preset | Health Connect records |
| --- | --- | --- |
| 01:00 | Swimming | Pool swimming session and 2,000 m distance |
| 02:00 | Meditation | Mindfulness session where supported; otherwise an other-workout meditation exercise |
| 03:00 | Yoga | Yoga exercise session |
| 04:00 | Running | Running exercise session and 20,000 steps |
| 05:00 | CrossFit | High-intensity interval training exercise titled CrossFit |
| 06:00 | Cycling | Cycling exercise session |

Distance and steps are separate Health Connect records covering the corresponding session. Swimming uses an assumed pace of 3 minutes per 100 metres, giving 2,000 metres in one hour. Cycling records duration only. All records use `RECORDING_METHOD_ACTIVELY_RECORDED` metadata with device type `PHONE`. Values remain generated presets, not sensor measurements, and session notes retain their generated-data disclosure.

Only sessions whose end time has passed are eligible. A run before 07:00 may therefore create only part of the day's set. Choose a past date for an immediate full run. A complete day produces eight records. Stable daily record identifiers and local receipts prevent repeated runs from creating another set for the same date. Changing the time zone does not move records already written.

After upgrading from 1.0.0 or 1.1.0, tap **Generate available sessions** for a date to update its existing records in place with the new metadata. Upgrades from 1.0.0 also receive the revised swimming distance and steps plus cycling. Automatic generation applies these updates to today on its next run. Earlier dates remain unchanged until you select and generate them. No deletion is needed before updating.

Sessions last 60 elapsed minutes. Daylight saving changes can shift a nonexistent start time forward or make two preset times overlap.

## Background generation

**Automatic generation** is off until enabled in the app. Use **Edit time zone & interval** to configure the schedule; the default interval is **120 minutes**, configurable from 15 to 1,440 minutes. Each run creates completed sessions, starting with the earliest unfinished date since enabling. If Android delays a run past midnight, the next run finishes the previous day before processing today. The app remembers progress across restarts and retries without duplicating records.

Catch-up is limited to the last **30 calendar days**, including today, and skips dates suppressed by deletion. Disabling automatic generation clears pending catch-up; re-enabling or changing time zone starts from that day's date. Dates while paused are not backfilled. Upgrading from a version without catch-up starts it on the first run or app opening after the upgrade; older missed days must be selected manually.

Scheduling uses Android WorkManager and needs no exact-alarm permission. Android may delay runs because of battery management, so the interval is approximate. Scheduled work persists across normal app closure and phone restarts. After force-stopping the app, open it again to let background work resume. Opening the app also restores missing scheduled work without resetting an existing job's timing. See [WorkManager scheduling](https://developer.android.com/develop/background-work/background-tasks/persistent) and [periodic timing](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work#schedule_periodic_work).

The automatic-generation card shows Android's actual job state, its earliest eligible run time, the oldest pending date, and a separate last automatic attempt/result. Manual runs do not overwrite the automatic result. An interrupted attempt remains visible until a subsequent run completes. The earliest time is not a guaranteed execution time.

When enabling, the app opens Android's native battery optimization exemption dialog if the app is not already exempt. You can allow or decline it. **Allow background activity** opens the same prompt later; once exempt, the button becomes **Review battery settings**. The app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` and uses `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with its own package URI, then reads the actual exemption state when you return. Declining leaves automatic generation enabled, subject to normal battery restrictions, without prompting again on every app opening. If the dialog is unavailable, it falls back to settings. This personal sideload build requests the exemption for its automatic-generation function; a future Play distribution would need to satisfy Google Play's exemption policy. See [Android exemption guidance](https://developer.android.com/training/monitoring-device-state/doze-standby).

An exemption can reduce delays, but WorkManager remains subject to Android scheduling and job quotas. No exact-alarm access is requested. See [Android power limits](https://developer.android.com/topic/performance/power/power-details).

### Verify on a phone

Enable automatic generation, review battery settings, then note the **Last automatic attempt** time. Close the app normally and let the phone remain screen-off past the configured interval. Reopen it to inspect the separate automatic result and check the records in Health Connect. Look for an attempt time while the app was closed; a run starting only after reopening does not establish background execution. Repeat after a reboot. Force-stopping intentionally prevents background execution until the app is reopened.

The local unit tests cover midnight rollover, once-daily runs before all sessions finish, interrupted retries, catch-up limits, suppression, settings changes, and daylight-saving dates. They do not prove screen-off scheduling or Health Connect writes on a physical phone; those still require the device check above.

## Privacy and verification

The app has no Internet permission, analytics, or cloud account. It requests Health Connect write access and keeps settings and write receipts locally. A success receipt means Health Connect accepted the write request; the app does not read records back after generation. Verify them using **Open Health Connect**. During deletion, it looks up only its own records to delete the matching entries and handle records already removed elsewhere; this needs no extra read permission.

Data appearing in Health Connect does not prove that another app will import or count it. Destination apps can inspect the recording metadata, source app and session notes; acceptance by a destination app has not been verified.

Use **Delete this day’s records** to remove this generator's records for the selected date. Deletion also pauses background generation and suppresses that date until you explicitly generate it again. If records are deleted directly in Health Connect, the app's local success receipts remain; delete the date in the app before generating it again. The app can delete records tracked by this installation; use Health Connect's app-data controls for records left by a previous installation.

Turning off background generation stops future scheduled runs. Clearing app storage removes its settings and receipts. Uninstalling this app does not necessarily delete its Health Connect records; use the app's delete action or Health Connect's data-management controls. Other apps may retain data they already imported. [Google explains Health Connect deletion](https://support.google.com/android/answer/13770320).

## Build from source

Open this repository in Android Studio, or use the included Gradle wrapper with **Java 17 or newer** and the **Android SDK Platform 37** installed. Configure the SDK using Android Studio, an `sdk.dir` entry in local `local.properties`, or `ANDROID_HOME`.

From this directory:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The built APK is `app/build/outputs/apk/debug/app-debug.apk`:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The application ID is `me.calvin.healthconnectgenerator`. Builds use Android Gradle Plugin 9.4.1 with built-in Kotlin, Gradle 9.7.1, Health Connect 1.1.0, minimum SDK 37, and target SDK 37. The wrapper verifies its distribution checksum. Keep the same signing key for future APK updates; Android will not replace an installed APK with one signed by a different key. A new machine's default debug key may differ.

## GitHub Actions

[Android CI](.github/workflows/ci.yml) runs on pushes to `master`, pull requests, and manual dispatch. It builds the debug APK, runs the unit tests and Android lint, and uploads the APK and reports as workflow artifacts for 14 days. Reports are also uploaded when a check fails. CI uses Ubuntu 26.04, Temurin Java 25 LTS, Android SDK Platform `platforms;android-37.0`, Build Tools `36.0.0`, and the checked-in Gradle wrapper. Gradle dependencies are cached and actions use major-version tags to receive compatible updates automatically.

CI APKs use a temporary runner debug key. Use GitHub release APKs for updates to an existing installation.

### Release signing setup

[Release APK](.github/workflows/release.yml) runs when a `v*` tag is pushed. Before the first automated release, add a repository Actions secret named `ANDROID_DEBUG_KEYSTORE_BASE64` containing the base64-encoded **existing** debug keystore used for published APKs. It must contain the `androiddebugkey` alias with Android's default debug passwords (`android`). On the machine holding that key, with GitHub CLI authenticated:

```sh
base64 < "$HOME/.android/debug.keystore" | tr -d '\n' | \
  gh secret set ANDROID_DEBUG_KEYSTORE_BASE64 --repo calvinbui/health-connect-activity-generator
```

The workflow checks the APK's signing certificate against the existing release certificate and fails if the secret is missing or a different key is supplied. The keystore is removed after the build and is never included in artifacts. The CI workflow does not use this secret.

### Publish a version

1. Increment `versionCode` and set `versionName` in `app/build.gradle.kts`, using a stable `major.minor.patch` version. Commit and push the change to `master`.
2. Push a matching tag, for example `v1.4.2` when `versionName` is `1.4.2`:

   ```sh
   git tag v1.4.2
   git push origin v1.4.2
   ```

The release workflow runs the build, tests, and lint, checks that the tag matches the built APK version, and verifies the APK signature and alignment. It then publishes `health-connect-generator-VERSION.apk` and its `.apk.sha256` checksum in a GitHub release with generated release notes. Only the publishing job has permission to write repository contents. Existing releases are not overwritten.
