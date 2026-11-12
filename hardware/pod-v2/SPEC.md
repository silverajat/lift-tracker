# Pod v2: the final, Fitbit-sized lift tracker

**Status (Nov 12, late):** board designed and fully routed (34 × 18 mm, 4 layers, 0.8 mm), DRC 0 errors / 0 unconnected / matches the schematic, ERC 0 errors. Case modelled (36.2 × 20.2 × 7.0 mm). Release files built in `release/liftpod_v2_revA/`. **One blocker:** the nRF52840 module (MDBT50Q-1MV2, C5118826) is out of stock at LCSC/JLC.

**Goal:** about the size of a Google Fitbit Air pebble (34.9 × 17 × 8.3 mm), with no USB port, no button, charging on the Fitbit Air charger, and everything over Bluetooth. Result: 36.6 × 20.6 × **7.3 mm**, 1.0 mm thinner than the Fitbit Air.

## How it's built

The board **is** the pod's underside. It sits flush in the bottom rim of a one-piece printed shell; its black solder mask and two gold contact pads are the face that goes on the charger and on your wrist. Everything else stacks on top:

```
 shell top 0.9 mm (logo, 2 LED windows)
 coin motor 2.5 on the nRF module 2.2   |   LiPo 3.0 + foam on the flash chip 1.75
 board 0.8 mm (flush with the rim)  ->  underside: 2 charger contacts + 3 SWD pads
 = 7.0 mm
```

Top view of the board (x 0–34, y 0–18 mm):

```
 +--------------------------------------------------------------+
 |antenna  | flash (2 MB)   | LEDs, charge prog.                |
 |corner   |                |-----------------------------------|
 |  nRF    |   ( steel )    | bridge  charger  LDO   cell pads  |  <- LiPo lies on
 |  module |   (  disc  )   |                                   |     this area
 | (motor  |----------------|                                   |
 |  on top)| IMU  caps      | bridge  motor driver              |
 +--------------------------------------------------------------+
 underside: contact A at x 9.5, contact B at x 27.5 (18 mm apart, y 9),
            SWD pads (IO, CK, G) under the module
```

## Decisions

| | Choice | Why |
|---|---|---|
| Size | **36.6 × 20.6 × 7.3 mm** body, 46.2 mm with band lugs | No floor (the board is the floor) and the tall parts are paired up: motor on the module, cell on the flash |
| Data | **Bluetooth only** | Recordings download over BLE, firmware updates over BLE (MCUboot + mcumgr) |
| Charging | **Google Fitbit Air charger** (5 V, 2 spring pins 18 mm apart, centre magnet) | Contacts on the underside 18 mm apart; a 5 mm steel disc on top of the board between them, for the charger's magnet. Full Schottky bridge, so the pod charges either way round |
| Battery | **301120 LiPo, 40 mAh, with protection board** | Datasheet max 3.0 × 11.0 × 20.5 mm. Has its own DW01 + 8205 protection, so none on our board |
| Button | **None** | Tap gestures from the IMU |
| Heart rate | **None** | HR comes from the Fitbit into the merged app |
| Features | Rep tracking, ROM/quality, buzz alerts, sleep alarm | Firmware on the same IMU + motor |

## Circuit

```
 contact A ─┐                       ┌─ TVS (ESD5Z) ── nRF VBUS (charger detect)
            ├─ 4x B5819WS bridge ── VCHG ── MCP73831 (20 mA) ── VBAT ── cell pads (cell has its own protection)
 contact B ─┘   (either polarity)    │                            │
                                     └─ red LED (charging)        ├─ XC6206 3.3 V LDO (~1 µA) ── +3V3
                                                                  ├─ 1M/1M divider -> nRF AIN2
                                                                  └─ coin motor ── AO3400A + flyback diode
 +3V3 ── nRF52840 module (Raytac MDBT50Q-1MV2, chip antenna in the board's corner)
           ├─ spi1: BMI270 IMU (+ INT1, INT2)
           ├─ spi2: W25Q16 2 MB flash (recordings)
           ├─ green status LED
           └─ SWD pads on the underside (no reset pad: nRF52 programs and recovers over SWD alone)
```

The bridge uses 1N5819-class Schottkys (~0.25 V each at 20 mA), so a 5 V charger gives ~4.5 V at the MCP73831 and the cell finishes at 4.2 V. A BAT54S pair was tried first: ~0.8 V total, which would leave the charger in dropout around 4.0 V (~85% full). The bridge diodes also steer ESD on the exposed contacts into the TVS.

### nRF52840 pins (for the v2 board file)

| Use | Module pin | GPIO |
|---|---|---|
| IMU SCK / MOSI / MISO / CS | 39 / 38 / 37 / 41 | P0.15 / P0.16 / P0.13 / P0.17 |
| IMU INT1 / INT2 | 36 / 43 | P0.14 / P0.21 |
| Flash SCK / MOSI / MISO / CS | 48 / 47 / 50 / 49 | P0.24 / P1.00 / P1.02 / P0.25 |
| Status LED (green) | 46 | P0.22 |
| Charging status (low = charging, use pull-up) | 45 | P0.23 |
| Battery voltage (1M/1M divider) | 20 | P0.04 = AIN2 |
| Motor | 26 | P1.09 |
| Charger present | 32 | VBUS (USBREGSTATUS / POWER event) |
| SWD | 51 / 53 | SWDIO / SWDCLK |

The two SPI buses are deliberate: the pins are in the same order as the pads they go to, so every line runs straight with no crossings.

## Parts (JLC/LCSC; check stock at order time)

| Ref | Part | LCSC | Notes |
|---|---|---|---|
| U101 | Raytac MDBT50Q-1MV2 (nRF52840) | C5118826 | **Out of stock** at LCSC (Nov 12). Options below |
| U102 | BMI270 IMU, LGA-14 | C2836813 | |
| U107 | W25Q16JVSNIQ 2 MB flash, SOIC-8 | C2456211 | |
| U103 | MCP73831-2-OT charger | C424093 | R103 47k → ~21 mA |
| U104 | XC6206P332MR 3.3 V LDO | C5446 | ~1 µA quiescent |
| D106–D109 | B5819WS Schottky (bridge) | C22624 | |
| D105 | ESD5Z5.0T1G TVS | C82044 | |
| Q101, D101 | AO3400A + 1N4148WS (motor) | C20917, C2128 | |
| D102, D103 | green / red 0603 LEDs (KT-0603G / KT-0603R) | C12624, C2286 | |
| passives | 0402 | C1525, C23733, C15525, C11702, C25741, C26083, C25076, C25104, C25792 | |
| J101 | charger contacts, 4 × 3 mm ENIG, underside | n/a | |
| J102, J104 | cell / motor wire pads, top | n/a | |
| J103 | SWD pads (IO, CK, G), underside | n/a | cover with kapton tape |
| MK1 | spot for the 5 mm steel disc | n/a | board-only marking, no copper or vias |

**Bought separately:** 301120 40 mAh LiPo *with protection board*; 8 mm coin vibration motor (≤ 2.5 mm thick); a 5 mm steel disc (a zinc-plated steel M2 washer works, not stainless: stainless washers aren't magnetic); kapton tape; thin double-sided foam tape; flexible glue (B-7000 / E6000); an SWD probe (Raspberry Pi Debug Probe).

## Power budget (estimated, to be measured on the board)

| State | Current | Per day |
|---|---|---|
| Gym session (IMU 100 Hz + BLE stream), 1.5 h | ~2 mA | ~3 mAh |
| Idle on wrist (IMU wake-on-motion, slow advertising) | ~20–40 µA | ~0.6 mAh |
| Night (sleep alarm: 1 Hz actigraphy) | ~30–50 µA | ~0.4 mAh |
| **Total with daily gym** | | **~4–5 mAh/day → ~5–6 days with margin** |

## Firmware changes for v2

- Board file with the pins above; BMI270 driver instead of the LSM6DS3TR-C (`read_imu()` and the overlay change; storage, BLE and the app don't).
- Flash on its own SPI bus (spi2), IMU on spi1.
- NFC pins aren't used, the reset pin is unconnected (internal pull-up).
- MCUboot + mcumgr over BLE for updates; recording dump over BLE.
- Low power: IMU any-motion wake; internal RC clock with the phone re-syncing the time on every connection.
- Tap gestures, buzz alerts, sleep alarm.

## Assembly

1. JLC assembles the board (top side only).
2. Flash the bootloader + app over the SWD pads on the underside.
3. Solder the cell (+ / − marked) and motor wires to their pads on top.
4. Stick the motor on the module and the cell on the right-hand parts with thin foam tape; glue the steel disc on its circle, with kapton under it.
5. Press the board into the shell's rim, then run a bead of flexible glue round the seam. Flexible glue can be cut to open the pod.
6. Cover the SWD pads with a square of kapton.

## Open before ordering

1. **nRF module stock.** Options: wait for LCSC restock, or use JLC Global Sourcing to buy MDBT50Q-1MV2 from Digi-Key/Mouser into the JLC parts library (takes about 1–2 weeks).
2. Confirm the charger contacts on the real charger with a printed test piece. The 18 mm spacing came from photos (±0.5–1 mm); the 4 × 3 mm pads allow for that.
3. Measure the band (width, thickness) for the lug slots.

## History

- Spec locked with our own magnetic pogo dock; dock design parked in `unused/`.
- Switched to the Fitbit Air charger: board flipped to the bottom, bridge rectifier, steel target, 3-pad SWD, split SPI buses, case rebuilt as a one-piece shell.
