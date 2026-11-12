# JLCPCB order: panel v1.1 (LED controller + gym pod)

This file has everything needed to place the order, check it, and bring the boards up.
- I prepared it on Nov 10–12, 2026. v1.1 (Nov 12) adds battery protection and a keyed battery socket to the gym pod; see "What changed in v1.1" below.
- The order itself (account, payment, clicking Order) is yours to do.

---

## 1. What is being ordered

One panel containing two boards, joined by mouse-bite tabs:

| | LED controller (ledctl v1) | Gym pod v1 |
|---|---|---|
| Size | 100 × 62 mm | 38 × 27 mm, rounded corners R4 |
| Layers | 4 | 4 (F.Cu parts + signals, In1 GND, In2 +3V3 + a few signals, B.Cu signals + GND) |
| Assembled parts | 109 | 39 (plus 2 DNP pad sets: SWD and motor) |
| Refs | 1–99 (C1, U5, …) | 101+ (C101, U101, …) so the two never clash |

**Panel:**
- Size: 141.2 × 78.1 mm. 5 mm rails top and bottom with tooling holes and 3 fiducials.
- The LED board sits at the left and is held by 2 tabs on its top edge and 2 on its bottom edge. They are clear of its ESP32 antenna area (top-left).
- The gym pod sits at the right, between two solid blocks that join the rails.
  - It is held by 3 tabs: 1 on the top edge (next to the SWD pads), 2 on the bottom (next to resistors and an LED).
  - None are near the BMI270, the small capacitors or the battery socket.
  - Its USB-C faces the open right edge of the panel.
- Mouse bites: 0.5 mm holes at 0.8 mm spacing, centred on the board edge.

### What changed in v1.1 (Nov 12)
- **Battery protection on the board** (U106 DW01A + Q103 FS8205A, the same circuit as inside a protected cell). It cuts the battery off at 4.3 V (overcharge), 2.5 V (over-discharge) and about 3 A (overcurrent or short), so the board is safe with any cell.
- **Keyed battery socket:** J102 is now a JST-SH 2-pin socket, assembled by JLC, instead of solder pads. The cell can't go in backwards.
- **Motor pads** moved to the bottom side at the antenna end. They're SMD, so they don't block routing.
- **Board size:** 38 × 27 mm (was 38 × 24) to fit the new parts. The battery still fits underneath.
- **Battery:** buy a certified cell instead of the Shopee one, e.g. the **Jauch LP402025JU** (150 mAh, 4.5 × 20.5 × 27 mm, UN38.3 / UL1642 / IEC 62133-2, from Farnell / element14 SG). Fit or buy a JST-SH 2-pin plug for it.
- Rerouted from scratch; all checks re-run (section 7).

**If you already set up the draft on JLC:** replace all three files (Gerbers, BOM, CPL) with the v1.1 ones. The panel size is unchanged. The nRF module part request isn't affected.

**Why a panel:**
- JLCPCB charges assembly setup and stencil once per design.
- One panel = one assembly job for both boards.
- In return, you declare 2 different designs on the PCB order, which may add a small fee.

---

## 2. Files to upload
All paths are inside `liftpod_ledctl_panel_v1.1\`.

| Step on jlcpcb.com | File |
|---|---|
| PCB → Add Gerber file | `fabrication\panel_v1.1_JLCPCB_Gerbers.zip` |
| PCB Assembly → BOM | `assembly\panel_BOM_JLCPCB.csv` (59 lines) |
| PCB Assembly → CPL / pick and place | `assembly\panel_CPL_JLCPCB.csv` (148 parts) |

**What's in the Gerber zip:**
- Copper: F.Cu, In1.Cu, In2.Cu, B.Cu
- Paste, silkscreen and mask, top and bottom
- Edge cuts
- One drill file (plated and mouse-bite holes together)
- The gbrjob file

---

## 3. PCB settings

| Setting | Choose |
|---|---|
| Base material | FR-4 |
| Layers | **4** |
| Dimensions | 141.2 × 78.1 mm (auto-filled from the Gerbers) |
| PCB qty | **5** |
| Product type | Industrial/Consumer electronics |
| Different design | **2** |
| Delivery format | **Panel by Customer** |
| Panel format | 1 × 1 (the panel is already built) |
| PCB thickness | **1.6 mm** |
| Impedance control / stackup | No / JLC04161H-7628 (default) |
| PCB colour | Green (any is fine) |
| Silkscreen | White |
| Surface finish | **ENIG** (flat pads for the 0.4 mm FPGA, the BMI270 LGA and the nRF module) |
| Outer copper weight | 1 oz |
| Inner copper weight | 0.5 oz |
| Via covering | Tented |
| Min via hole size / diameter | **0.25 mm / (0.4/0.45 mm)**. Do NOT leave it at 0.3 mm: the LED board uses 0.25 mm vias |
| Board outline tolerance | ±0.2 mm (default) |
| Confirm production file | Yes (optional, free) |
| Mark on PCB | "Remove Mark", or "Specify a location" |
| Castellated holes / edge plating / press-fit | No |
| Gold fingers | No |

---

## 4. Assembly settings

| Setting | Choose |
|---|---|
| PCB Assembly | **On** |
| PCBA type | **Standard**. The LED board has through-hole parts (J2, J4) and a 0.4 mm-pitch QFN |
| Assembly side | **Top side** |
| PCBA qty | **2** panels = 2 LED boards + 2 gym pods. The other 3 panels arrive bare |
| Edge rails / fiducials | Added by customer |
| Tooling holes | Added by customer |
| Confirm parts placement | **Yes** |
| Stencil | Top side only (nothing is assembled on the bottom) |
| Photo confirmation | Optional |
| Depanel / remove edge rails | No. Break them yourself (see section 9) |

### Parts NOT fitted (DNP: in the files but deliberately left out of the BOM/CPL)
| Board | Ref | What | Why |
|---|---|---|---|
| Gym pod | J103 | SWD pads (3V3, DIO, CLK, RST, GND) | Clip or solder the debug probe |
| Gym pod | J104 | Motor wire pads (SMD, on the **bottom**, antenna end) | Solder the vibration motor's wires |
| LED | J3, J5 | Expansion headers | Fit later if wanted |
| LED | R31, R32 | Optional analog bias | Not needed |
| LED | Test pads, mounting holes | — | — |

---

## 5. Parts step: stock check

On the BOM page every line should show a matched part with stock. You need 2 of each part per assembled board. For the three below, **stop and message me if any is short. Don't accept a substitute.**

| LCSC | Part | Board | Note |
|---|---|---|---|
| **C5118826** | Raytac MDBT50Q-1MV2 (nRF52840 module) | Gym pod U101 | Extended part, most expensive line |
| **C2836813** | Bosch BMI270 IMU | Gym pod U102 | Tiny LGA |
| **C380550** | Gowin GW2AR-LV18QN88C8/I7 FPGA | LED U5 | About 90 were in stock when checked |

Extended parts each add a one-time loading fee. The quote shows the total.

### Gym pod parts (per board)
| Refs | Part | LCSC | Qty |
|---|---|---|---|
| U101 | MDBT50Q-1MV2 nRF52840 module | C5118826 | 1 |
| U102 | BMI270 IMU (LGA-14 3 × 2.5 mm) | C2836813 | 1 |
| U103 | MCP73831-2-OT Li-ion charger (SOT-23-5) | C424093 | 1 |
| U104 | ME6211C33M5G 3.3 V LDO (SOT-23-5) | C82942 | 1 |
| U105 | USBLC6-2SC6 USB ESD (SOT-23-6) | C7519 | 1 |
| Q101 | AO3400A N-MOSFET, motor driver (SOT-23) | C20917 | 1 |
| Q102 | AO3401A P-MOSFET, battery → system switch (SOT-23) | C15127 | 1 |
| D101 | 1N4148WS motor flyback (SOD-323) | C2128 | 1 |
| D104 | B5819W Schottky, USB → system (SOD-123) | C8598 | 1 |
| D102 | Green LED 0603 (user LED) | C72043 | 1 |
| D103 | Red LED 0603 (charging) | C2286 | 1 |
| J101 | HRO TYPE-C-31-M-12 USB-C | C165948 | 1 |
| SW101 | TS-1187A tact switch | C318884 | 1 |
| C101 | 10 µF 0603 | C19702 | 1 |
| C102, C104, C105, C108 | 100 nF 0603 | C14663 | 4 |
| C103, C106, C107, C109, C110 | 4.7 µF 0603 | C19666 | 5 |
| R101, R102 | 5.1 kΩ 0603 (USB-C CC) | C23186 | 2 |
| R103 | 15 kΩ 0603 (charge current about 67 mA) | C22809 | 1 |
| R104 | 1 kΩ 0603 (red LED) | C21190 | 1 |
| R105, R106 | 1 MΩ 0603 (battery sense divider) | C22935 | 2 |
| R107 | 100 Ω 0603 (motor gate) | C22775 | 1 |
| R108, R110 | 100 kΩ 0603 (gate pull-downs) | C25803 | 2 |
| R109 | 330 Ω 0603 (green LED) | C23138 | 1 |
| J102 | JST-SH 2-pin battery socket SM02B-SRSS-TB (keyed) | C160402 | 1 |
| U106 | DW01A battery protection IC (SOT-23-6, PUOLOP) | C351410 | 1 |
| Q103 | FS8205A dual N-MOSFET, protection switch (SOT-23-6, TECH PUBLIC) | C2830320 | 1 |
| R111 | 100 Ω 0603 (DW01A supply filter) | C22775 | 1 |
| R112 | 1 kΩ 0603 (DW01A current sense) | C21190 | 1 |
| C111 | 100 nF 0603 (DW01A supply filter) | C14663 | 1 |

Some LCSC numbers are shared with the LED board, so they appear as one combined line in the BOM. That's normal. For the LED board's parts, see `projects\led-sync\README_ORDER.md` and its BOM.

---

## 6. Placement preview (the most important check)

KiCad and JLCPCB sometimes disagree on a footprint's zero rotation, so a part can show up turned 90° or 180°. Before paying, open the 3D/placement preview and compare each part below with the silkscreen. If one is wrong, rotate it in the preview, then screenshot it and send it to me.

### Gym pod (right side of the panel)
**Board orientation in the preview:** USB-C at the right edge, rounded antenna end at the left.

| Ref | What to check | CPL rotation |
|---|---|---|
| U101 nRF module | Antenna end (no pads) at the **left** board edge, pads toward the USB-C side | 90 |
| U102 BMI270 | Pin-1 dot on the part matches the pin-1 mark on the silkscreen | 0 |
| U103 charger (SOT-23-5) | Pin 1 matches the silkscreen dot | 0 |
| U104 LDO (SOT-23-5) | Pin 1 matches the silkscreen dot | 0 |
| U105 ESD (SOT-23-6) | Pin 1 matches the silkscreen dot | 0 |
| Q101, Q102 (SOT-23) | The lone pad (drain) lines up with the lone pad on the board | 0 |
| D101 (SOD-323) | Cathode stripe on the silkscreen bar side | 90 |
| D104 (SOD-123) | Cathode stripe on the **left** (toward the board centre) | 0 |
| D102, D103 (LEDs) | Cathode mark matches the silkscreen | 0 |
| J101 USB-C | Opening faces **out** past the right edge | 90 |
| SW101 | Sits square on its 4 pads | 0 |
| J102 battery socket (JST-SH) | Opening faces **out** past the bottom-left edge; the 2 large mounting pads are at the edge | 0 |
| U106 DW01A (SOT-23-6) | Pin 1 matches the silkscreen dot | 90 |
| Q103 FS8205A (SOT-23-6) | Pin 1 matches the silkscreen dot | 0 |

Full coordinates are in the CPL. All gym parts are between X 108–138 mm, Y 28–51 mm, measured from the panel's bottom-left corner.

### LED board (left side), from its own README
Check the pin-1 marks on:
- U5 (FPGA), U6, U2, U1, U3/U4 and X1
- the ESP32
- both microphones
- the USB-C connector
- both Hirose connectors (J6/J7)
- the LEDs

JLCPCB engineers also check placement and email if something looks off. Reply quickly, because the order waits for you.

---

## 7. Checks already done

| Check | Gym pod | LED board | Panel |
|---|---|---|---|
| ERC (schematic) | 0 errors (4 harmless library-version warnings) | 0 errors | — |
| DRC errors | 0 | 0 | 0, at JLC's limits (0.1 mm clearance, 0.127 mm track, 0.45/0.25 mm via) |
| Unconnected nets | 0 | 0 | 0 |
| Schematic vs board | 0 differences | 0 differences | — |
| DRC warnings | 15, silkscreen only | 37, silkscreen only | Silkscreen / library-path only |
| Independent review | No blockers (round 3, v1.1) | No blockers | Gym CPL rows match board positions; BOM LCSCs verified |
| Gerbers re-rendered independently | Copper and planes look right | — | Yes |

The reports are in the `checks\` folder: `gympod_erc.json`, `gympod_drc.json`, `panel_drc.json`, `independent_review.md`.

### Design decisions worth knowing (gym pod)
- **Load sharing:**
  - With USB plugged in, the board runs from USB through D104, so the charger only sees the battery and can finish charging properly.
  - When USB is unplugged, Q102 connects the battery to the system rail.
  - Added after the first review found the charger would otherwise never terminate.
- **Charging:** MCP73831 at about 67 mA (R103 = 15 kΩ), sized for the 302030 140 mAh cell. The red LED is on while charging.
- **Green LED resistor** is 330 Ω. 1 kΩ would make it too dim.
- **No 32 kHz crystal.** The firmware uses the nRF's internal RC clock. BLE works fine with it.
- **Antenna keepout** is clean on all 4 layers; nothing metal goes near the left end.
- **Supply tracks** are 0.2 mm, plenty for under 0.2 A. GND and 3V3 use 0.3 mm plus planes.
- **Pin map:**
  - IMU SPI on P0.13 SCK, P0.14 MOSI, P0.15 MISO, P0.16 CS; INT1 on P0.17, INT2 on P0.20
  - Battery sense on P0.02/AIN0 (1M/1M divider)
  - Motor on P0.29, button on P0.28, green LED on P0.30
  - Reset on P0.18, plus USB D+/D−

### Accepted minor items from the review (no change needed)
- When USB is unplugged, the 3.3 V rail dips for under a second while switching. Don't take battery readings in that window.
- D104 leaks a few µA from the battery. That's small next to the regulator's own ~40 µA.
- The motor sees about 4.6 V on USB. If it's a 3 V coin motor, limit its PWM duty while plugged in.
- A few traces run under the nRF module (covered by solder mask, DRC-clean).
- Some silkscreen labels sit close together (D102/D104). A labelled map is in `previews\gympod_net_map.png`.

---

## 8. Also buy
- **Debug probe for the first flash of the gym pod:** Raspberry Pi Debug Probe, or a Raspberry Pi Pico flashed with debugprobe. After the first flash, updates go over Bluetooth.
- **USB-JTAG adapter for the LED board's FPGA** (if you don't have one): one that openFPGALoader or Gowin Programmer supports.
- **Fine wire and flux** for the battery, motor and SWD pads (1.27 mm pitch).

---

## 9. When it arrives

### Depanel
- Break each tab with **pliers, one tab at a time**. Grip the tab, not the board, and don't snap it by bending the board. The BMI270 and small capacitors on the gym pod are near its edges.
- File the little nubs smooth.

### Gym pod bring-up
1. Inspect under a light: U101 and U102 seated, no bridges on the USB-C.
2. Plug in USB-C only, with no battery yet. Check 3.3 V on the SWD 3V3 pad.
3. Connect the probe: **DIO, CLK, GND**. Do NOT power the board from the probe's 3V3; use USB.
4. Flash MCUboot plus the firmware once over SWD. After that, update over Bluetooth (nRF Connect Device Manager).
5. Firmware settings this board needs:
   - `CONFIG_CLOCK_CONTROL_NRF_K32SRC_RC=y` (no 32 kHz crystal)
   - `CONFIG_GPIO_AS_PINRESET=y`, or run `nrfjprog --pinreset` once, so the RST pad works
   - BMI270: toggle CS low then high once after power-up to put it in SPI mode, then upload the Bosch config file
6. **Battery:** plug the cell into **J102** (JST-SH, keyed, so it only goes in one way).
   - The cell needs a JST-SH 1.0 mm 2-pin plug with **pin 1 = +** (red) and **pin 2 = −** (black). It's printed on the bottom.
   - Check the plug's wire order with a meter before the first plug-in: plugs are keyed, but cable makers don't all wire them the same way.
   - Run the cable round the bottom edge. The cell sits under the **USB half** of the board, away from the antenna end.
7. **Motor:** solder red to **+** and the other wire to **−** on the MOTOR pads on the **bottom** (antenna end, marked).
8. **If the board seems dead after you first plug the battery in, plug in USB once.** The protection chip can latch off when a cell is first connected, and USB charging resets it. This is normal for DW01A circuits.
9. **Put a piece of Kapton tape or thin foam** on the bottom of the board where the battery sits, under the USB half. The USB-C connector's shell legs poke through there and could puncture the battery pouch.
8. Charging check: plug in USB and the red LED comes on. It goes off when the battery is full.

### Measuring the pod's current draw (battery life)
The battery plugs into J102, so the plug is the measurement point. No board change is needed.
1. Get a short **JST-SH 2-pin extension lead** (socket on one end, plug on the other).
2. Cut its **red (+) wire** and strip both ends.
3. Connect the two ends to your meter on the **mA / µA range**, or to the in/out of a power profiler (e.g. Nordic PPK2 in ammeter mode). The black wire stays connected.
4. Plug the battery into the lead and the lead into J102.
5. Read the current in each state: idle, recording, Bluetooth connected, buzzing. Battery life ≈ capacity (150 mAh) ÷ average current.

Unplug USB while measuring, otherwise USB powers the board and the battery current reads near zero.

### LED board bring-up (from its README)
- **Power:** USB-A to USB-C cable into the 2.4 A socket. The green PWR LED should light.
- **ESP32:** shows up as a USB serial/JTAG device. Hold BOOT and tap RESET for download mode.
- **FPGA:** program it through J2 (3V3, GND, TCK, TDI, TDO, TMS). Don't power the board from the programmer's 3V3. Use `ledctl_v1.cst` as the pin-constraints file.
- **Strip:** plugs into J4 with the **black wire on the "+5V" pin** (marked).

---

## 10. Folder map

```
liftpod_ledctl_panel_v1.1\
  JLCPCB_ORDER.md              this file
  README_ORDER.md              shorter version
  fabrication\panel_v1.1_JLCPCB_Gerbers.zip   ← upload
  assembly\panel_BOM_JLCPCB.csv             ← upload
  assembly\panel_CPL_JLCPCB.csv             ← upload
  checks\                      ERC, DRC, independent review
  previews\                    panel + gym pod renders, schematic PDF, net map
  panel.kicad_pcb / .kicad_pro the panel itself (open in KiCad 9)
  source\gympod\               gym pod schematic + board
  source\scripts\              scripts that generate everything:
                               design.py (circuit) → gen_schematic.py → build_pcb.py (placement.py)
                               → fanout.py → export_dsn.py → Freerouting → import_ses.py
                               → stitch.py → silk.py → make_panel.py → make_panel_fab.py
                               (make_release.sh runs the panel/fab part)
```

## 11. Next after this order
- D10, on this v1 board: sleep tracking and smart wake (firmware only).
- D11: v2 slim pod, once the firmware works. 0.8 mm board, charging pads instead of USB-C, battery beside the board, about 8–9 mm thick.
