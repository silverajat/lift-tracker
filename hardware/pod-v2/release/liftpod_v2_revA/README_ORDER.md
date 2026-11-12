# Lift pod v2 rev A: what to order

Everything here is generated from `hardware/pod-v2/pcb/` (`make_release.sh`).
Checks: see `checks/drc.json` (DRC, with schematic parity) and `checks/erc.json`.

**nRF module:** Ebyte E73-2G4M08S1C (C356849), in stock at JLC (77 on Nov 12). JLC lists it as "Standard PCBA only, X-ray inspection required".

## 1. PCB (JLCPCB)

Upload `fabrication/liftpod_v2_revA_JLCPCB_Gerbers.zip`.

| Setting | Value | Why |
|---|---|---|
| Layers | 4 | GND plane + 3.3 V layer inside |
| Size | 41 × 18 mm (JLC reads it from the outline) | |
| Thickness | **0.8 mm** | the board is the pod's underside |
| Material | FR-4 (standard) | |
| Solder mask | **Black** | it's the visible underside |
| Silkscreen | White | |
| Surface finish | **ENIG** | the charger contacts are bare pads touching skin, sweat and the charger's pins: gold doesn't corrode, HASL would |
| Outer / inner copper | 1 oz / 0.5 oz | |
| Via covering | Tented (JLC upgrades to plugged for free) | |
| Min track / spacing | 0.127 / 0.127 mm | JLC standard 4-layer |
| Min via | 0.3 mm drill / 0.6 mm pad | standard |
| Mark on PCB | Remove Mark (default) | |
| Quantity | 5 | |

## 2. Assembly (JLCPCB PCBA)

Upload `assembly/liftpod_v2_BOM_JLCPCB.csv` and `assembly/liftpod_v2_CPL_JLCPCB.csv`.

- **PCBA type: Standard** (the E73 needs Standard + X-ray).
- Top side only. Not assembled: charger contacts, cell/motor/SWD pads, the steel-disc marking.
- Quantity: 2 (or all 5).
- JLC adds edge rails for Standard PCBA on small boards; snap them off (or tick "Depanel boards & edge rail before delivery").
- In the placement preview, check each part's rotation, mainly the E73 (antenna at the board's left edge), SOT-23s, SOD-323 diodes, the LEDs, the BMI270 and the USON flash (pin 1 dots).

## 3. Case (JLC3DP)

Upload `../lift_pod_v2_shell.stl` (one piece, 53.2 × 23.8 × 7.75 mm with the band lugs; body 43.6 × 20.6).

- Material: **JLC Black Resin** (toughest of the black resins, 65 °C heat deflection), general sanding. For a silkier feel, wipe with a drop of mineral oil.
- Product description: Plastic Enclosure. Quantity: 2 (one spare).
- Meets JLC3DP's limits (no thin-wall warning): 1.2 mm walls and top, rounded inside edge, 1.6 mm lug sides, 0.8 mm corner stops.

## 4. Buy separately

- 301120 LiPo, 40 mAh, **with protection board** (wires, not a connector).
- 8 mm coin vibration motor, ≤ 2.5 mm thick, with wires.
- 5 mm steel disc for the charger magnet: a zinc-plated steel M2 washer works. Not stainless: stainless washers aren't magnetic.
- Kapton tape, thin double-sided foam tape, flexible glue (B-7000 or E6000), 30 AWG wire.
- Raspberry Pi Debug Probe (SWD), for the first flash.

## 5. Before clicking Order

1. Charger fit. The 18 mm contact spacing comes from photos. Optional: print a flat test plate with two dots 18 mm apart and check the charger's pins land on them with the magnet centred.
2. Band: the lugs take a 20 mm × 1.8 mm band (same as the earlier prints).
