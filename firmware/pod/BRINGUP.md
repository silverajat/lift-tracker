# Pod v2: first flash and bring-up

The firmware is built by CI on every push (`.github/workflows/pod-firmware.yml`) and published as the
**pod-fw-latest** release:

| File | What it's for |
|---|---|
| `liftpod-v2-full.hex` | First flash over SWD: MCUboot bootloader + app in one file |
| `liftpod-v2-update.zip` | Every later update, over Bluetooth from the phone |

## 1. First flash (once per board, laptop + Pico probe)

1. Put **debugprobe** firmware on one of the Pico clones: hold BOOTSEL, plug it in, drag
   `debugprobe_on_pico.uf2` (from github.com/raspberrypi/debugprobe releases) onto the drive.
2. Wire it to the SWD pads on the pod's underside: **GP2 → CK, GP3 → IO, GND → G**.
   The pod runs on its own cell, so there's no power wire.
3. On the laptop: `pip install pyocd`, then
   ```
   pyocd flash -t nrf52840 liftpod-v2-full.hex
   ```
   If pyOCD says the chip is locked, let it unlock it. That mass-erases the chip, which is normal for a new nRF52840.
4. The pod should blink once (long) and buzz once. That means it booted.

Logs while it's wired: `pyocd rtt -t nrf52840`. A good boot shows `N recordings, … bytes used` (the flash works) and no `no LSM6DSV16X` error. Opening a set shows `app connected` and `gym mode on`.

## 2. Check each part

| Check | How | Good |
|---|---|---|
| Boots | flash it | long blink + one buzz |
| Bluetooth | open the app | "Pod connected", fw 3 |
| Battery | the pod bar | 3.7–4.2 V shown as a % |
| Charger | put it on the Fitbit Air charger | "charging" in the pod bar, red LED on the charger |
| IMU | start a set | live reps count; no "IMU not started" |
| Flash | finish a workout | the pod bar shows "Saving sets 1/N", then "Export N recordings" |
| Motor | rest timer ends | one buzz (two when a set starts) |
| Double tap | knock the pod twice, quickly, mid-set | a short buzz and the set ends |

Then desolder the SWD wires and cover the pads with kapton.

## 3. Updates over Bluetooth (no laptop)

1. Download `liftpod-v2-update.zip` from the pod-fw-latest release on the phone.
2. Install Nordic's **nRF Device Manager** app. Connect to **LiftPod**, then go to Image → Firmware upgrade.
3. Pick the zip and choose "Test and confirm". The pod restarts into the new image. It confirms itself once it
   starts cleanly. If it doesn't start cleanly, MCUboot goes back to the old image on the next restart.

Close the Life app first: the pod takes one Bluetooth connection at a time.

The images are signed with MCUboot's public development key. That's fine for bring-up. Before the pod is
used day to day, switch to a private key (`SB_CONFIG_BOOT_SIGNATURE_KEY_FILE`, kept as a CI secret).
After the switch, the first update has to go over SWD again.

## How it behaves

- **Asleep** (almost always): accelerometer at 15 Hz in wake-on-motion, gyro and flash off, advertising once a
  second. After 20 min with no movement and no app it stops advertising; moving it brings that back.
- **Gym mode**: the app turns it on when you open a set (during the countdown) and off when you finish
  the workout. It also ends 5 min after the app goes away, or 45 min after the last set.
- **After a workout**: the app copies every recording (100 Hz, accel + gyro) to the phone, then erases the
  pod's flash. "Export N recordings" saves them as a zip in Downloads. Inside is a folder in the same layout
  `python/tools/dump_logs.py` writes for the prototype, so it drops straight into `data/raw/`.
- **LED**: one blink on connect and when gym mode starts, a short blink every 2 s while recording.
  A double blink every 5 s means a fault (IMU or flash didn't start).

## Training data carries over

- The live stream the app counts reps from, and saves for retraining, is the same as the prototype's:
  25 Hz, milli-g and 0.1 °/s. The LSM6DSV16X is set to the same ranges (±8 g, ±1000 dps).
- The rep counter works out the motion axis per set (PCA and gravity). So the sensor sitting at a different
  angle on the new board doesn't matter, and the corrections you've made so far keep training it.

## Lessons from the first build (Nov 27)

- Solder the battery last and keep its wires short and flat; fit the motor on two layers of Kapton over the E73's metal can.
- Never let a loose steel washer near a powered board: one bridged pins on the first board, which then booted and dragged the
  battery down (its protection cut out, pads read ~0.7 V). The second board was fine.
- Test after every step (motor, washer, debug wires off, in the case) and only glue once it still connects the next morning.
- The firmware now has a 60 s watchdog: a sealed pod has no reset button, so a hang restarts it instead of bricking it.
