# Gym pod: independent review, round 2 (pre-JLC order)

Scope: the new load-sharing power path (Q102/D104/R110/SYS), netlist-to-copper parity, the In2 plane, the antenna keepout, USB, the panel tabs/mouse bites, and the gym-board rows in the panel CPL.
Tools: KiCad 9.0.9 (`kicad-cli` and the pcbnew Python API). All refs below are the board refs (+100).

## Verdict
**No blockers found.** The power path is wired correctly, and the copper, schematic, design.py, CPL and BOM all agree. Fix or consciously accept the SHOULD FIX items before ordering.

## BLOCKERS
None.

## SHOULD FIX
1. **The BMI270 and several 0603 MLCCs are close to the break-off tabs.** (Panel. Confidence: medium.)
   - The top-left tab (gym x 110.2-115.2, y=100) is about 1.2 mm from the U102 BMI270 courtyard. Snapping the tab bends the board next to a stress-sensitive LGA MEMS part, which risks offset shift or cracked joints.
   - Courtyard distance to the nearest tab for the other parts:
     - C110: 1.5 mm, rotated 90, so it sits perpendicular to the edge (worst case for flex cracking)
     - C109: 1.8 mm, also perpendicular
     - U104: 1.1 mm
     - SW101: 0.6 mm
     - Q101: 1.3 mm
     - C104: 1.4 mm
   - Suggested fix: move the top/bottom tabs away from U102 and C109/C110 (for example over J103/SW101 gaps), or ask JLC to depanel. At minimum, break the tabs with pliers rather than by flexing the board.
2. **JLC rotation conventions have not been verified.** (Confidence: medium.)
   - The CPL carries raw KiCad rotations. JLC's library zero orientation differs from KiCad's for some packages, often SOT-23, SOT-23-5/6 and LGA parts.
   - In the JLC placement preview, check pin 1 and polarity for:
     - Q101 and Q102 (SOT-23)
     - U103, U104 (SOT-23-5) and U105 (SOT-23-6)
     - U102 (LGA-14, pin-1 corner)
     - U101 (module)
     - J101, SW101
     - D101/D103/D102 cathodes, and the D104 cathode, which must face left toward SYS
   - Footprint origins are at body centre for U101 and J101 (checked against F.Fab), so the X/Y positions are fine.

## Verified OK (high confidence unless noted)
**Power-path topology**
- Q102 uses AO3401A, which extends TP0610T in `Transistor_FET`: pin 1 = G, 2 = S, 3 = D. The SOT-23 pads are G=VBUS (117.86,112.45), S=SYS (117.86,114.35), D=VBAT (119.74,113.4). The body diode points VBAT→SYS, as intended.
- D104 uses `D_Schottky` (pin 1 = K, 2 = A). On SOD-123, pad 1 (131.75,118.6) = SYS and pad 2 (135.05,118.6) = VBUS.
- R110 runs VBUS to GND.
- Plugged in: SYS ≈ VBUS − 0.4 V, Vgs(Q102) ≈ +0.4 V, so Q102 is off and its body diode is reverse-biased. The charger sees only the battery.
- Unplugged: the gate is pulled to 0 V, Vgs ≈ −VBAT, Q102 is on, and the battery feeds SYS.
- U104 pins 1/3, C109, D101 K and J104 pin 1 are all on SYS. The charger output, J102, R105 and C107 stay on VBAT.

**Part numbers** (web-checked)
- C15127 = AOS AO3401A, SOT-23, JLC Basic.
- C8598 = CJ B5819W, SOD-123.
- C23138 = UNI-ROYAL 0603WAF3300T5E (330 Ω 1%, 0603).

**Netlist and DRC**
- The netlist exported from the schematic matches design.py pin for pin, with every ref +100 (0 mismatches).
- `kicad-cli pcb drc --schematic-parity`:
  - 0 unconnected items and 0 parity issues.
  - 11 warnings, all silkscreen (overlaps, plus J101 silk clipped by the board edge).
- ERC: 4 `lib_symbol_mismatch` warnings only.
- The release copies of the .kicad_pcb, .kicad_sch and design.py are byte-identical to `final/`.
- The schematic PDF already shows Q102, D104 and R110.

**Panel**
- The gym-board copper in the panel matches `final/` exactly: same track and via counts and lengths per net. The zone fills differ by about 1 mm², only around the mouse bites.
- All 33 gym CPL rows match the panel footprint positions and rotations. The origin is the panel outline's bottom-left (0, 70.0), Y up, and every part has the same offset (+3.1, −81.0 mm).
- Every placed gym part is in the BOM with the right LCSC number. J102, J103 and J104 are DNP and correctly left out of both the BOM and the CPL.
- Panel DRC: warnings only. The 5 `lib_footprint_issues` belong to the LED-controller board.

**In2 (+3V3) plane**
- The plane fills as a single connected region (709 mm², versus 766 mm² for the In1 GND plane).
- The In2 SYS track (x≈119.1-119.2, y 104.7→121.2) cuts a long slot, and the IMU_CS, IMU_MOSI and nRESET stubs (x 108.8-112.6, y 101.4-109.5) cut short ones. The plane stays connected above and below the slot.
- Three B.Cu tracks cross that slot: SYS, MOTOR_G and BUTTON. All are DC or slow signals.

**Antenna keepout**
- The keepout is the U101 footprint's own rule area on F, In1, In2 and B (x 100.55-104.3, y 105.8-118.2), sitting at the board edge.
- No tracks, vias, pads or zone fill fall inside it on any layer (fill-area intersection = 0 on every layer).

**USB**
- D+ and D− run entirely on F.Cu over the In1 GND plane, 0.2 mm wide with about 0.16 mm gap.
- Total length is D+ ≈ 27.2 mm and D− ≈ 28.7 mm, which is fine for Full Speed.
- A6/B6 and A7/B7 are joined at the connector, and both go through the USBLC6 before the nRF.
- CC1 and CC2 each have their own 5.1k pull-down.

**Mouse bites**
- They use 0.5 mm NPTH, 6 per tab, in 4 tabs on the top and bottom edges only.
- The nearest copper is GND pour, 0.2 mm from the hole edge (meets JLC's minimum, tight).
- Nothing is near the antenna keepout. The right edge (USB-C) is the open panel edge, so the receptacle (F.Fab front at x=138.0, flush with the edge) is unobstructed.

## NOTES
1. **Brief SYS sag on unplug.** (Confidence: medium.)
   - After unplug, VBUS decays through R110 and C103/C106 (about 100k × 9.4 µF ≈ 1 s).
   - Until VBUS drops more than |Vth| below SYS, SYS runs through Q102's body diode at about VBAT − 0.6 V. With a low battery, that is roughly 2.8-3.1 V, and +3V3 dips to about 2.7-3.0 V.
   - The nRF and BMI270 tolerate this, but don't take ADC or brown-out readings during that window.
   - A weak USB source (VBUS < VBAT + 0.4 V) gives the same body-diode condition.
2. **Reverse leakage through D104 drains the battery.** (Confidence: medium.)
   - When unplugged, the B5819W leaks from SYS into VBUS. With 100k on R110, a few µA puts some 0.1-1 V on VBUS and costs µA of battery current, rising with temperature.
   - This is small next to the ME6211 quiescent current (about 40 µA) and the R105/R106 divider (2 µA), but it is permanent.
   - A lower-leakage Schottky (for example a 0.5 A / 20-30 V part) would reduce it.
3. **The motor runs at about 4.6 V on USB** versus 3.0-4.2 V on battery. If the motor is a 3 V coin motor, limit the PWM duty while VBUS is present.
4. **Tracks run under the module.** F.Cu traces pass under the U101 body between the module's bottom pads: VBAT (12 mm, e.g. 108.5→112.8 at y 114.8), VBAT_SENSE, nRESET, IMU_MOSI, IMU_CS and BUTTON. They are DRC-clean and covered by solder mask. Acceptable, but check Raytac's layout guide, and avoid it on the next spin if possible. (Confidence: low.)
5. **Power traces are 0.2 mm.** All VBUS, SYS and VBAT traces are 0.2 mm, and SYS uses 4 vias (0.3 mm drill). This is adequate for 67 mA charge plus about 100 mA motor. Widen to 0.3-0.4 mm if there is room.
6. **Silkscreen is ambiguous around D104/D102** (the D102 label sits on the D104 outline and the D104 label sits on the J101 outline). Also, C108's label overlaps its body, and U105's polygon overlaps the U105/C103 labels. This is cosmetic and doesn't affect assembly, but tidy it to avoid mistakes during rework.
7. **Battery and motor wire routing.** The J102 battery and J104 motor pads (y=121.2) are about 3 mm below the antenna keepout corner. Route the battery and motor leads away from the antenna end.
8. **Plug-in capacitance.** VBUS bulk is C103 + C106 = 9.4 µF nominal, plus C109 behind D104, which is at about the USB 10 µF guideline. Effective capacitance after DC bias derating is lower, so this is fine.
