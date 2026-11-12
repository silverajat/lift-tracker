# Pod v2: the final, Fitbit-sized lift tracker

**Status:** spec locked (Nov 12, 2026). Schematic and layout come next.
**Goal:** about the size of a Google Fitbit Air pebble (34.9 × 17 × 8.3 mm), with no USB port, no button, magnetic charging, and everything over Bluetooth.

## Decisions

| | Choice | Why |
|---|---|---|
| Size target | **~36 × 20 × 8.5 mm** pod | Fitbit Air is 34.9 × 17 × 8.3 mm. Ours has a 2 MB recording flash and a motor, so a little bigger is fine |
| Data | **Bluetooth only** | Recordings download over BLE (bulk transfer), and firmware updates go over BLE (MCUboot + mcumgr/SMP). USB-C, the ESD chip and CC resistors are gone |
| Charging | **Our own magnetic pogo dock** | Pod: 2 gold (ENIG) pads on the bottom + 2 magnets. Dock: small JLC-assembled board with USB-C, 2 spring pogo pins and 2 magnets. The magnets' poles only let it snap on one way round (no reverse polarity), the same idea as Fitbit chargers |
| Battery | **~40 mAh LiPo, ~2.5 × 14 × 18 mm** | About 5 days with daily gym use + the sleep alarm (budget below). Sits under the board |
| Button | **None** | Tap gestures from the IMU: double tap = battery level, dismiss an alarm. Sealed case |
| Heart rate | **None** | IMU-only sleep alarm. HR comes from the Fitbit into the merged app |
| Features | Rep tracking, ROM/quality, buzz alerts (rest over, set slowed), **sleep alarm** (vibrates in a light-sleep window) | All firmware, on hardware v1.1 already has: IMU + motor |

## Block diagram

```
 pogo pads (VIN, GND) ── TVS ── Schottky ── MCP73831 charger ── DW01A + FS8205A protection ── LiPo 40 mAh
                                   │ VIN sense                   │ BAT
                                   ▼                             ▼
                                 nRF GPIO               low-Iq LDO 3.3 V ── nRF52840 module (MDBT50Q-1MV2, BLE)
                                                                   ├─ BMI270 IMU (SPI, INT1 wake-on-motion / tap)
                                                                   ├─ W25Q16 2 MB flash (SPI/QSPI, recordings)
                                                                   ├─ 32.768 kHz crystal (accurate time for the alarm)
                                                                   ├─ coin vibration motor via N-FET + flyback diode
                                                                   ├─ 1 status LED (light pipe through the lid)
                                                                   └─ SWD test pads (first flash with a probe; then BLE DFU)
```

## Parts (JLC/LCSC; stock to re-check at order time)

| Ref | Part | LCSC | Notes |
|---|---|---|---|
| U1 | Raytac MDBT50Q-1MV2 (nRF52840, 10 × 15.5 mm, chip antenna) | C5118826 | Same as v1.1. **Blocker:** JLC stock quote still pending |
| U2 | BMI270 IMU, LGA-14 2.5 × 3 mm | C2836813 | Same as v1.1. Any-motion + tap interrupts for wake and gestures |
| U3 | Winbond W25Q16JVUXIQ 2 MB NOR flash, USON-8 2 × 3 mm | C2843335 | **New:** recordings (v1.1 had no external flash) |
| U4 | MCP73831-2-OT charger | C424093 | Same as v1.1; charge current set to ~20 mA (1C for 40 mAh) |
| U5 | DW01A protection | C351410 | Same as v1.1 (pinout verified) |
| Q3 | FS8205A dual N-FET | C2830320 | Same as v1.1 (pinout verified) |
| U6 | Low-Iq 3.3 V LDO (e.g. RT9080-33, ~2 µA Iq) | to pick | **Change:** ME6211's ~40 µA would eat about 1 mAh/day, a lot for a 40 mAh cell |
| Y1 | 32.768 kHz crystal, 2.0 × 1.2 mm | to pick | Time-keeping for the sleep alarm at the lowest current |
| Q1, D1 | AO3400A + 1N4148WS motor driver | C20917, C2128 | Same as v1.1 |
| M1 | Coin ERM motor, 8 mm × 2–2.5 mm | to pick | Sits under the board, next to the battery |
| D2 | One small LED | to pick | Status: charging, low battery, tap feedback |
| D3, D4 | TVS + Schottky on the pogo input | to pick | The pads are exposed to sweat; never put the battery on them |
| J1 | 2 ENIG pads, 2.5 mm, 5 mm pitch, board bottom | n/a | The charging contacts |
| TP | 5 SWD pads (VDD, GND, SWDIO, SWCLK, RESET) | n/a | For a pogo jig or Tag-Connect |

Removed vs v1.1: USB-C (J1), USBLC6 (U5), CC resistors, button (SW1), JST battery socket (cell soldered to pads), load-sharing P-FET path (the pod is never used while on the dock).

## Mechanical stack (inside an MJF nylon case from JLC3DP)

```
 lid 1.0 mm
 parts 2.2 mm (module is the tallest)
 PCB 0.8 mm (thin board)
 battery 2.5 mm | motor 2.0–2.5 mm | pogo pads (beside the battery)
 floor 1.0 mm (2 holes for the pads, 2 pockets for the magnets)
 + 0.5 mm clearances
 ≈ 8.0–8.5 mm total
```

Board about 32 × 17 mm, 4 layers, 0.8 mm. The antenna end sits at a short edge, with no copper or battery under the antenna keep-out.

## Power budget (estimated, to be measured on the board)

| State | Current | Per day |
|---|---|---|
| Gym session (IMU 100 Hz + BLE stream), 1.5 h | ~2 mA | ~3 mAh |
| Idle on wrist (IMU wake-on-motion, slow advertising) | ~20–40 µA | ~0.6 mAh |
| Night (sleep alarm: 1 Hz actigraphy, RTC) | ~30–50 µA | ~0.4 mAh |
| **Total with daily gym** | | **~4–5 mAh/day → 40 mAh ≈ 8–10 days on paper, ~5–6 days with margin** |

## Battery: "custom sized"

Truly custom cells (your own dimensions) are made to order, usually with a minimum of hundreds to thousands of cells plus a tooling fee. That only makes sense for a product run. For one pod, we pick from the very large range of standard LiPo sizes. They come in 0.5 mm steps (e.g. 251418, 301420, 302020), so the board outline is drawn first and the cell picked to fit. Prefer a cell with UN38.3 / IEC 62133 certification, like the Jauch cell picked for v1.1.

## Firmware changes for v2

- Board file for the custom board (pins), with a BMI270 driver instead of the LSM6DS3TR-C. `read_imu()` and the overlay change; storage, BLE and the app don't.
- MCUboot + mcumgr over BLE for updates (from the app or nRF Connect Device Manager).
- Recording dump over BLE (bulk characteristic), replacing the USB shell.
- Low power: system OFF / idle with BMI270 any-motion wake, RTC on the 32 kHz crystal.
- Tap gestures, buzz alerts, sleep alarm.

## Before ordering

1. nRF52840 module stock at JLC (or an alternative module).
2. Pick the exact battery cell, motor, LDO, crystal and LED, checking JLC stock.
3. Schematic → layout → DRC → independent review, the same flow as v1.1.
4. Case + dock design (OpenSCAD) → JLC3DP MJF quote. Need: band width and thickness.
5. A SWD probe for the first flash (e.g. Raspberry Pi Debug Probe, ~S$15). After that, updates go over Bluetooth.
