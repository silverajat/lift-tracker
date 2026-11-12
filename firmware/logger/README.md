# D1 logger firmware

Records the XIAO nRF52840 Sense's IMU to its 2 MB flash during sets, then hands the recordings to the laptop as CSV files.

## How it behaves

| Situation | What happens | LED |
| --- | --- | --- |
| On battery, still | Waits, keeping the last 2 s of motion in RAM | Green blip every 3 s |
| On battery, you start moving | Starts a recording (including the 2 s before you moved) | Fast green blink |
| On battery, still for 12 s | Closes the recording: one set = one recording | Back to blips |
| USB cable plugged in | Never records by itself; laptop can list, dump, erase | Blue |
| Flash full or an error | Stops recording until you erase | Red |

- **Sample rate:** 100 Hz (IMU itself runs at 208 Hz). 100 Hz keeps ~24 min of recording in the 2 MB flash; 200 Hz would halve that. Rep motion has almost nothing above ~10 Hz, so 100 Hz loses nothing useful.
- **Ranges:** ±8 g and ±1000 °/s.
- **Units in the CSV:** accel in milli-g (`ax_mg`), gyro in 0.1 °/s (`gx_ddps`, divide by 10 for °/s). `t_ms` is the low 16 bits of uptime in ms, so gaps reveal any missed samples.
- **Walking between machines** may create short extra recordings. Mark them `junk` in `notes.csv`.

## First time (once the board arrives)

1. **Flash it.** Easiest: double-click `FLASH_XIAO.bat` in the repo root. It flashes, then runs the desk test and dumps the data. By hand: plug the XIAO in, double-tap its tiny reset button. A new USB drive appears. Drag `prebuilt/lift_logger_d1.uf2` onto it. The board reboots and the LED turns blue.
2. **Open its serial port** in VS Code: nRF Connect panel → Connected Devices, or the *nRF Terminal*. Press Enter and you get a `uart:~$` prompt.
3. **Erase the flash once:** type `rec erase` (takes up to ~30 s). New chips can ship with old data on the flash, and the firmware refuses to record until it's erased.
4. **Desk test:** `rec start`, leave the board still on the desk for 10 s, pick it up and do 5 slow curl motions, `rec stop`. Then `rec list`.
5. **Pull the data** with the script (below) and we look at it together: the still part shows the real noise, the curls show what a rep looks like.

## Shell commands

| Command | What it does |
| --- | --- |
| `rec status` | Power source, recording or not, flash used, minutes left |
| `rec list` | One line per recording: id, start time, length, samples |
| `rec dump <id>` | Prints one recording as CSV |
| `rec start` / `rec stop` | Manual recording (desk tests) |
| `rec erase` | Erases **all** recordings |
| `rec imu` | IMU start-up state, chip ID (should be `0x6a`) and the latest reading |
| `rec dfu` | Reboots into the UF2 bootloader (the XIAO-SENSE drive appears), so you can flash without double-tapping reset |

## Getting the CSVs onto the laptop

```
pip install pyserial
python python/tools/dump_logs.py            # finds the board by itself
python python/tools/dump_logs.py --erase    # also erases the board afterwards
```

Files land in `data/raw/<date-time>/`: one `rec_NNN.csv` per recording, `recordings.csv`, and a `notes.csv` to fill in (exercise, weight, warm-up or working).

## Building it yourself

The `.uf2` in `prebuilt/` was built with nRF Connect SDK v3.4.1, the same version installed on the laptop. To build it in VS Code:

1. nRF Connect panel → **Open an existing application** → pick this `firmware/logger` folder.
2. **Add build configuration** → board target `xiao_ble/nrf52840/sense` → **Build**.
3. The result is `build/logger/zephyr/zephyr.uf2`. Flash it the same way.

## Files

| File | What's in it |
| --- | --- |
| `src/main.c` | Sampling thread, motion detection (start/stop), shell commands, LED |
| `src/storage.c/.h` | Append-only recording log on the QSPI flash, with power-loss recovery |
| `tests/host/` | Tests `storage.c` on the laptop against a simulated flash chip (`make && ./test_storage`) |
| `prj.conf` | IMU rate and ranges, flash, shell |

## Known limits (to revisit)

- First run on the real board (Nov 11, 2026): 100 Hz with no dropped samples; at rest the accel noise is about 2 mg and the gyro bias is under 3 °/s.
- Fixed on that first run: the XIAO powers its IMU through a switch, and Zephyr probed the chip before it had booted, so the IMU stayed disabled (the board showed "battery" on USB and recorded nothing). `app.overlay` now defers the IMU's start, and `main.c` starts it after 100 ms with retries.
- Always sampling on battery, so it draws a few mA continuously. Fine for the prototype; low-power work comes in D5.
- No battery voltage reading yet.
