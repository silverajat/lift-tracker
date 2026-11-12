# Lift pod v2 rev A: what to order

Everything here is generated from `hardware/pod-v2/pcb/` (`make_release.sh`).
Checks: DRC 0 errors, 0 unconnected, PCB matches the schematic (`checks/drc.json`; the one warning is two silkscreen outlines touching under the cell). ERC 0 errors (`checks/erc.json`).

**nRF module:** MDBT50Q-1MV2 (C5118826) was out of stock, so it's pre-ordered into your JLC parts library (2 pcs, Nov 12). JLC confirms the price within 48 h of payment; order the board + assembly once the parts show in My Inventory.

## 1. PCB (JLCPCB)

Upload `fabrication/liftpod_v2_revA_JLCPCB_Gerbers.zip`.

| Setting | Value | Why |
|---|---|---|
| Layers | 4 | GND and 3.3 V planes inside |
| Size | 34 × 18 mm (JLC reads it from the outline) | |
| Thickness | **0.8 mm** | the board is the pod's underside |
| Material | FR-4 (standard) | |
| Solder mask | **Black** | it's the visible underside |
| Silkscreen | White | |
| Surface finish | **ENIG** | the charger contacts are bare pads touching skin, sweat and the charger's pins: gold doesn't corrode, HASL would |
| Outer / inner copper | 1 oz / 0.5 oz | |
| Via covering | Tented (or plugged) | |
| Min track / spacing | 0.127 / 0.127 mm | JLC standard 4-layer |
| Min via | 0.3 mm drill / 0.6 mm pad | standard |
| Order number | **Specify a location** | it goes where `JLCJLCJLCJLC` is printed, on top, hidden under the cell |
| Quantity | 5 | |

## 2. Assembly (JLCPCB PCBA)

Upload `assembly/liftpod_v2_BOM_JLCPCB.csv` and `assembly/liftpod_v2_CPL_JLCPCB.csv`.

- **PCBA type: Standard** (the nRF module is "Standard only, X-ray inspection required" at JLC, so Economic won't take it).
- Top side only. 32 parts, 20 BOM lines. Not assembled: charger contacts, cell/motor/SWD pads, the steel-disc marking.
- Quantity: 2 (or all 5).
- In the placement preview, check each part's rotation, mainly the SOT-23s, SOD-323 diodes, the LEDs and the module. Watch the marked pin 1 on the BMI270 and the flash.
- If JLC asks for edge rails or tooling holes because the board is small, let JLC add them (small extra cost). Don't add holes inside the board.

## 3. Case (JLC3DP)

Upload `../lift_pod_v2_shell.stl` (one piece, 45.8 × 20.2 × 7.0 mm with the band lugs).

- Material: SLA black resin, with the sanded / "mineral oil" finish for a smooth, dry, Fitbit-like feel. Alternative: MJF PA12 nylon dyed black (tougher, slightly grainy).
- Quantity: 2 (one spare).
- Thinnest parts: the 0.3 mm lip the board presses against, and 1.0 mm walls. Both are fine for SLA.

## 4. Buy separately

- 301120 LiPo, 40 mAh, **with protection board** (wires, not a connector).
- 8 mm coin vibration motor, ≤ 2.5 mm thick, with wires.
- 5 mm steel disc for the charger magnet: a zinc-plated steel M2 washer works. Not stainless: stainless washers aren't magnetic.
- Kapton tape, thin double-sided foam tape, flexible glue (B-7000 or E6000).
- Raspberry Pi Debug Probe (SWD), for the first flash.

## 5. Before clicking Order

1. Module in stock (above).
2. Charger fit. The 18 mm contact spacing comes from photos. Optional: print a $1 flat test plate with two dots 18 mm apart and check the charger's pins land on them with the magnet centred.
3. Band width and thickness. The lugs take a 20 mm × 1.8 mm band; change `strap_w` / `band_t` in `lift_pod_v2.scad` if yours differs.
