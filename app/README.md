# Phone app (Android)

Native Android app (Kotlin + Jetpack Compose) that runs the workout and controls the pod over Bluetooth.

**Now (v0.2):**
- Pick **Upper** or **Lower** day. The workout is a Hevy-style table per exercise: SET · PREVIOUS · KG · REPS. The numbers you lifted last time are prefilled. Tap a set number to mark it as a warm-up.
- **▶** starts a set and the pod records it with its label. The live rep count comes from the pod's motion stream. **STOP** ends the set.
- **Rep quality** after every set, with ROM first:
  - **Rotation lifts** (curls, raises, flyes, extensions): ROM per rep in degrees, from the gyro.
  - Each rep is compared with your **full ROM**: today's warm-up set, or your best logged set when there's no warm-up.
  - Reps under 90% are flagged as partials. Speed drop and tempo are shown alongside.
  - **Push/pull lifts:** ROM is relative to the set's biggest reps for now.
- Confirm the reps → the rest timer starts: **2:00** for main lifts, **1:30** for isolation. The phone buzzes when it's over.
- **Share** a workout as text, written like a hand-typed log, to ChatGPT or anywhere else. It's offered when you finish, and there's a share icon on recent workouts.
- Exercise page: progress charts (heaviest weight, est. 1RM, best set, volume, reps, full ROM), personal records and history.
- Sessions are saved on the phone after every set. The screen stays on during a workout, and the app works without the pod (timing and logging only).

**How rep quality will be checked:** the pod's rep counts are compared with the counts you confirm, and its ROM degrees are compared frame by frame with filmed sets (elbow and arm angle). See `docs/` once the first gym data is in.

**Vision:** the moment a set ends, the app shows the rep count and a quality score for each rep (speed, range, consistency). It flags when a set slowed too much and when it's time to add weight.

## Install on your phone

Every push to `main` builds the APK on GitHub Actions and publishes it as the **app-latest** release.

1. On the phone, open the repo's **Releases** → *Lift Tracker app (latest build)* → tap **lift-tracker.apk**.
2. Open the download. Android asks to allow installs from this source (Chrome or Files): allow it, go back, tap **Install**.
3. Open **Lift Tracker** → allow **Nearby devices** (that's Bluetooth). The pod connects by itself; shake it if it's asleep.

Later builds install over the old one and keep your history, because every build is signed with the same key (`debug.keystore`, debug-only).

## Code

| File | What's in it |
| --- | --- |
| `ble/PodProtocol.kt` | UUIDs and byte layouts. Mirror of `firmware/logger/src/ble.h` |
| `ble/PodClient.kt` | Scan, connect, reconnect, one-at-a-time GATT operations |
| `reps/RepCounter.kt` | Live rep counter v0 (picks the most active axis, counts swings) |
| `data/Routines.kt` | The two days, their exercises and starting numbers. Exercise ids are stored in pod recordings: never renumber |
| `data/SessionStore.kt` | Sessions as JSON files in the app's private storage |
| `AppViewModel.kt` | The set flow: start → stop → confirm → rest |
| `ui/` | Screens and theme |

Build it yourself: open `app/android` in Android Studio, or run `gradle assembleDebug` with Gradle 8.11 and JDK 17.
