# Pod v2: the final, Fitbit-sized lift tracker

**Status (Nov 13):** IMU is now the ST LSM6DSV16X (same footprint position as the BMI270 it replaced). **Earlier (Nov 12, late):** rev A uses the **Ebyte E73-2G4M08S1C** nRF52840 module, which JLC has in stock. The Raytac MDBT50Q we started with is out of stock. Board: 41 × 18 mm, 4 layers, 0.8 mm. Case: 43.6 × 20.6 × 7.75 mm. Release files are in `release/liftpod_v2_revA/`.

**Goal:** close to a Google Fitbit Air pebble (34.9 × 17 × 8.3 mm), with no USB port, no button, charging on the Fitbit Air charger, and everything over Bluetooth. Result: **0.55 mm thinner** than the Fitbit Air and about 9 mm longer. The E73 is bigger and taller than the Raytac module (13 × 18 × 3 mm vs 10 × 15.5 × 2.2), and we picked thin over short.

## How it's built

The board **is** the pod's underside. It sits flush in the bottom rim of a one-piece printed shell; its black solder mask and two gold contact pads are the face that goes on the charger and on your wrist. Everything else stacks on top:

```
 shell top 1.2 mm (logo, 2 LED windows)
 coin motor 2.5 on the E73 module 3.0   |   LiPo 3.0 + foam on the right-hand parts (<= 1.1)
 board 0.8 mm (flush with the rim)  ->  underside: 2 charger contacts + 3 SWD pads
 = 7.75 mm
```

Top view of the board (x 0–41, y 0–18 mm):

```
 +-------------------------------------------------------------------------+
 |ant.|                    |dec.| flash  |  red LED, charge programming      |
 |    |   Ebyte E73        |caps|--------|----------------------------------|
 |    |   (coin motor      |    |( steel)| bridge  charger  TVS   cell pads  |  <- LiPo lies on
 |    |    sits on it)     |    |( disc )|                                  |     x 19.9-40.4
 |    |                    |    |--------| bridge  LDO                      |
 |    |                    |    | IMU    | motor driver, flyback            |
 +-------------------------------------------------------------------------+
 underside: contact A at x 15.2, contact B at x 33.2 (18 mm apart, y 9),
            SWD pads (IO, CK, G) under the module's top edge
```

## Decisions

| | Choice | Why |
|---|---|---|
| Module | **Ebyte E73-2G4M08S1C** (nRF52840, ceramic antenna) | In stock at JLC (MDBT50Q-1MV2 wasn't). Same chip, so firmware, BLE and app are unchanged apart from pins. Its pads are castellated, so it can be hand-soldered too |
| Size | **45.6 × 20.6 × 7.75 mm** body, 55.2 mm with band lugs (2 mm pocket past the board end for a 22 mm cell) | Motor on the module, cell beside it: thinner than stacking the cell on the 3 mm module |
| Data | **Bluetooth only** | Recordings download over BLE, firmware updates over BLE (MCUboot + mcumgr) |
| Charging | **Google Fitbit Air charger** (5 V, 2 spring pins 18 mm apart, centre magnet) | Contacts on the underside 18 mm apart; a 5 mm steel disc on top of the board between them, for the charger's magnet. Full Schottky bridge, so the pod charges either way round |
| Battery | **301120 LiPo, 40 mAh, with protection board** | Datasheet max 3.0 × 11.0 × 20.5 mm. Has its own DW01 + 8205 protection, so none on our board |
| Button | **None** | Tap gestures from the IMU |
| Heart rate | **None** | HR comes from the Fitbit into the merged app |
| Features | Rep tracking, ROM/quality, buzz alerts (rest over, next set) | Worn only for gym sessions; sleep, steps and alarms stay on the Fitbit |

## Circuit

```
 contact A ─┐                       ┌─ TVS (ESD5Z)
            ├─ 4x B5819WS bridge ── VCHG ── MCP73831 (20 mA) ── VBAT ── cell pads (cell has its own protection)
 contact B ─┘   (either polarity)    │        └─ STAT ── nRF (charging)   │
                                     └─ red LED (charging)                ├─ XC6206 3.3 V LDO (~1 µA) ── +3V3
                                                                          ├─ 1M/1M divider -> nRF AIN5
                                                                          └─ coin motor ── AO3400A + flyback diode
 +3V3 ── Ebyte E73 (nRF52840), antenna at the board's left edge
           ├─ one SPI bus: LSM6DSV16X IMU (CS + INT1) and W25Q16 2 MB flash (CS), 100k CS pull-ups
           ├─ green status LED
           └─ SWD pads on the underside (no reset pad: nRF52 programs and recovers over SWD alone)
```

The bridge uses 1N5819-class Schottkys (~0.25 V each at 20 mA), so a 5 V charger gives ~4.5 V at the MCP73831 and the cell finishes at 4.2 V. A BAT54S pair was tried first: ~0.8 V total, which would leave the charger in dropout around 4.0 V (~85% full). The bridge diodes also steer ESD on the exposed contacts into the TVS.

### nRF52840 pins (for the v2 board file)

Only the E73's outer edge pads are used. Ebyte asks for no tracks under the module, and its inner pad row can't be reached without them.

| Use | E73 pad | GPIO |
|---|---|---|
| SPI SCK / MOSI / MISO (IMU + flash) | 15 / 13 / 11 | P0.05 / P0.01 / P0.00 |
| IMU CS / INT1 | 10 / 9 | P0.30 / P0.31 |
| Flash CS | 33 | P0.13 |
| Status LED (green) | 7 | P0.02 |
| Charging status (low = charging; pull-up, wakes the nRF) | 35 | P0.24 |
| Battery voltage (1M/1M divider) | 8 | P0.29 = AIN5 |
| Motor | 17 | P1.09 |
| SWD | 37 / 39 | SWDIO / SWDCLK |
| Power | 19 VDD + 23 VDDH = 3.3 V; 5, 21 GND | DCCH, VBUS, reset open |

P0.00/P0.01 are the 32 kHz crystal pins, used here as GPIO, so the firmware must use the internal RC low-frequency clock (LFRC). The E73's inner GND pad (24) is left open; pads 5 and 21 ground the module.

## Parts (JLC/LCSC; check stock at order time)

| Ref | Part | LCSC | Notes |
|---|---|---|---|
| U101 | Ebyte E73-2G4M08S1C (nRF52840) | C356849 | 77 in stock at JLC (Nov 12). Standard PCBA, X-ray |
| U102 | ST LSM6DSV16X IMU, LGA-14L 2.5 × 3 | C5267406 | 8,099 in stock (Nov 13). Lower noise than the BMI270, on-chip sensor fusion (SFLP) and machine-learning core. SDx/SCx tied to GND, aux SPI pins open |
| U107 | W25Q16JVUXIQ 2 MB flash, USON-8 2 × 3 | C2843335 | |
| U103 | MCP73831-2-OT charger | C424093 | R103 47k → ~21 mA |
| U104 | XC6206P332MR 3.3 V LDO | C5446 | ~1 µA quiescent |
| D106–D109 | B5819WS Schottky (bridge) | C22624 | |
| D105 | ESD5Z5.0T1G TVS | C82044 | |
| Q101, D101 | AO3400A + 1N4148WS (motor) | C20917, C2128 | |
| D102, D103 | green / red 0603 LEDs (KT-0603G / KT-0603R) | C12624, C2286 | |
| passives | 0402 | C1525, C23733, C15525, C11702, C25741, C26083, C25076, C25104, C25792 | |
| J101 | charger contacts, 4 × 3 mm ENIG, underside | n/a | |
| J102, J104 | cell / motor wire pads, top | n/a | motor pads: either way round |
| J103 | SWD pads (IO, CK, G), underside | n/a | cover with kapton tape |
| MK1 | spot for the 5 mm steel disc | n/a | board-only marking, no copper or vias |

**Bought separately:** 301120 40 mAh LiPo *with protection board*; 8 mm coin vibration motor (≤ 2.5 mm thick); a 5 mm steel disc (a zinc-plated steel M2 washer works, not stainless: stainless washers aren't magnetic); kapton tape; thin double-sided foam tape; flexible glue (B-7000 / E6000); 30 AWG wire; an SWD probe (Raspberry Pi Debug Probe).

## Power budget (estimated, to be measured on the board)

| State | Current | Per day |
|---|---|---|
| Gym session (IMU 100 Hz + BLE stream), 1.5 h | ~2 mA | ~3 mAh |
| Off the wrist the rest of the day (IMU wake-on-motion, no advertising) | ~10–15 µA | ~0.3 mAh |
| **Total with daily gym** | | **~3.5 mAh/day → ~10 days with margin** |

Worn only at the gym: no sleep tracking or alarm (the Fitbit does those). After ~20 min without motion the pod stops advertising and waits for the IMU's wake-on-motion; picking it up wakes it.

## Firmware changes for v2

- Board file with the pins above; Zephyr's `st,lsm6dsv16x` driver instead of the LSM6DS3TR-C one (`read_imu()` and the overlay change; storage, BLE and the app don't). Later: use the chip's sensor fusion (game rotation vector) and machine-learning core for low-power wake and taps.
- One SPI bus, two chip selects (IMU, flash). Set both CS high first thing at boot (the 100k pull-ups hold them high until then).
- LFRC clock (P0.00/P0.01 are GPIO); DC/DC off until the E73's inductor is confirmed.
- Charger detection from CHG_STAT (GPIO sense) instead of VBUS.
- MCUboot + mcumgr over BLE for updates; recording dump over BLE.
- Low power: IMU any-motion wake; the phone re-syncs the time on every connection.
- Tap gestures and buzz alerts. No sleep alarm: the pod is worn only for gym sessions.

## Assembly

1. JLC assembles the board (top side only).
2. Solder the cell (+ / − marked) to its pads on top. The pod runs on it.
3. Flash the bootloader + app over the SWD pads on the underside (3 thin wires, removed afterwards).
4. Solder the motor wires (either way round). Stick the motor on the module and the cell on the right-hand parts with thin foam tape. Glue the steel disc on its circle, with kapton under it.
5. Press the board into the shell's rim, then run a bead of flexible glue round the seam. Flexible glue can be cut to open the pod.
6. Cover the SWD pads with a square of kapton.

## Open points

1. Confirm the charger contacts on the real charger. The 18 mm spacing came from photos (±0.5–1 mm); the 4 × 3 mm pads allow for that.
2. The E73's height is taken from its 3D model (3.0 mm). If the real module is lower, the case can drop by the difference.

## History

- Spec locked with our own magnetic pogo dock; dock design parked in `unused/`.
- Switched to the Fitbit Air charger: board flipped to the bottom, bridge rectifier, steel target, 3-pad SWD, case rebuilt as a one-piece shell.
- Raytac MDBT50Q out of stock: switched to the Ebyte E73 (in stock), USON flash, one shared SPI bus, board 34 → 41 mm.
- IMU: BMI270 → ST LSM6DSV16X (lower noise, on-chip fusion). Same LGA-14 pad positions, so the routing stayed; MISO's via moved to make room for the GND ties on pins 2/3 (`pcb/swap_imu.py`, `pcb/tie_gnd.py`).
