# Phone app (Android)

Native Android app (Kotlin + Jetpack Compose) that runs the workout and controls the pod over Bluetooth.

**Now (v0.1):**
- Pick **Upper** or **Lower** day. Each exercise shows what you lifted last time.
- Pick an exercise. Weight, add-on plates and warm-up are prefilled from the same set last time.
- **Start** → lift → **Stop**. The pod records the set with its label, and the live rep count comes from the pod's motion stream.
- Confirm or fix the reps, then the **rest timer** starts. The phone buzzes when rest is over.
- Sessions are saved on the phone after every set and become "last time" for the next workout. The screen stays on during a workout.
- It works without the pod as well: timing and logging only.

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
