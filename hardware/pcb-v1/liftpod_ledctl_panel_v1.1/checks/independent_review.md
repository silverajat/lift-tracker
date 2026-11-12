# Gym pod v1.1: independent review, round 3 (pre-JLC order)

Scope: the v1.1 changes. These are the 38 × 27 mm outline, the DW01A + FS8205A battery protection, J102 as a JST-SH, J104 on the bottom, the reroute, the panel package and the current paths.

Tools:
- KiCad 9.0.9 (`kicad-cli`, pcbnew API), run on scratch copies; nothing in the project was modified.
- LCSC, JLCPCB and flux.ai part pages.

Refs are board refs (design.py ref + 100). Coordinates are KiCad board mm, with the board spanning x 100–138 and y 100–127.

## Verdict
**No blockers found.** The protection circuit is the standard DW01A/8205A topology, and the pin numbering is consistent end to end. Schematic, copper, CPL and BOM all agree. Three SHOULD FIX items are worth doing before ordering; S1 is a 10-minute layout fix.

## BLOCKERS
None.

## SHOULD FIX

**S1. VBAT_SENSE runs along the bottom edge, through the x=22.5 break-off tab.** (Confidence: medium-high.)
- The track is F.Cu, 0.2 mm wide, at y=126.43 from x 110.4 to 120.7, then runs diagonally to (122.2, 124.9). Its copper edge is about 0.47 mm from the board edge.
- The bottom tab's mouse-bite holes span gym x 120.75–124.25 (panel 123.85–127.35, y=44.5). The nearest hole edge is 0.23 mm from this track. That passes DRC, but it is right on the fracture line.
- This is a 1 MΩ/1 MΩ high-impedance net. A cracked or nicked trace means no battery reading.
- Fix (either one):
  - Reroute VBAT_SENSE at least 1.5 mm in from the bottom edge.
  - Move the bottom-left tab to an x where only GND pour is near the edge.

**S2. Check the JLC placement preview for the three newly assembled parts.** (Confidence: high that this check is needed; the actual rotations are unknown.)
- The CPL carries raw KiCad rotations: J102 rot 0, Q103 rot 0, U106 rot 90. JLC's library zero orientation often differs from KiCad's for SOT-23-6 and JST connectors.
- A 180° error is destructive:
  - Q103 would swap S1↔G2 and S2↔G1, so the cell would see gates on the power path.
  - U106 would swap OD↔TD, VM↔VCC and OC↔GND.
  - J102 would face the opening inward and miss its pads.
- In the DFM preview, confirm:
  - Q103 and U106 pin 1 is at the silk triangle: Q103 at top-left (109.86, 119.45); U106 at bottom-left (114.05, 121.14).
  - J102's two small contacts face up (into the board) and the large MP tabs face the bottom edge.
- The round-2 items still apply (U105, U103/U104, Q101/Q102, U102, the diodes).

**S3. The release package is partly stale.** (Confidence: high.)
- `README_ORDER.md` (root and release copy, dated Nov 10) still describes v1:
  - It says "38 × 24 mm".
  - It lists J102 as solder-wire pads in the DNP list, and the motor on "1.27 mm pads".
  - It omits the DW01A/FS8205A, and its "no reverse-polarity protection, red to +" text is now about a JST plug.
- `checks/independent_review.md` is the round-2 (v1) review. Replace it with this file.
- The Gerber zip is named `panel_v1_JLCPCB_Gerbers.zip`, but `make_release.sh` writes `panel_v1.1_…`. An old `release/liftpod_ledctl_panel_v1.zip` sits next to the folder, so the wrong zip could be uploaded.
- The zip contents are current: every layer and the drill file are identical to a fresh export of `panel.kicad_pcb`.

## NOTES

**N1. Protection topology: correct.** (Confidence: high.)
- Discharge FET: Q103 S1 (pin 1) = BATT_N, G1 (pin 6) = OD (U106 pin 1). Discharge current flows GND→S2→D→S1→BATT_N, so FET2's body diode conducts and FET1 blocks.
- Charge FET: S2 (pin 3) = GND (pack−), G2 (pin 4) = OC (U106 pin 3). FET1's body diode conducts and FET2 blocks charge current.
- U106 connections:
  - GND (pin 6) = BATT_N.
  - VCC (pin 5): R111 100 Ω from VBAT, with C111 100 nF to BATT_N.
  - VM (pin 2): R112 1k to GND.
  - TD (pin 4) is not connected.
- This matches the standard DW01A + 8205A application circuit.

**N2. Pin numbering: consistent end to end.** (Confidence: high.)
- The custom `gympod:FS8205A` symbol is 1 S1, 2 D, 3 S2, 4 G2, 5 D, 6 G1. That matches the TECH PUBLIC pinout, which flux.ai's LCSC-derived data for C2830320 corroborates. The datasheet PDF is a scanned image, so I could not read the pinout from it directly.
- KiCad `Battery_Management:DW01A`, both the library and the schematic copy, is 1 OD, 2 CS(=VM), 3 OC, 4 TD, 5 VCC, 6 GND. That is the standard DW01A pinout and matches the PUOLOP pinout as stated.
- `Package_TO_SOT_SMD:SOT-23-6` pads run 1-2-3 down the left side and 4-5-6 up the right, the standard numbering. Q103 pads: 1 BATT_N, 2 PROT_D, 3 GND, 4 PROT_OC, 5 PROT_D, 6 PROT_OD.

**N3. Only the protection parts reference the cell negative.** (Confidence: high.)
- BATT_N has exactly four pads: J102.2, U106.6, Q103.1 and C111.2.
- U103 (charger) pin 2, R106, C107, C108, U104 and everything else are on GND (pack−).
- U106 GND is a Kelvin tap: a separate trace leaves the Q103 pin-1 pad, so the J102→Q103 BATT_N trace drop is not in the overcurrent sense loop.
- R112's GND end shares one F.Cu pour island with Q103 pin 3, about 1.7 mm away (4 stitching vias).

**N4. The sense divider measures VBAT relative to pack−.** (Confidence: high that it doesn't matter.)
- The ADC reads Vcell − I·R. R is about 115 mΩ: 2 FETs (about 50 mΩ), about 21 mm of 0.2 mm VBAT trace to R105's tap (about 52 mΩ), and the BATT_N trace (about 11 mΩ).
- That is about 1 mV at 10 mA and about 35 mV at 0.3 A with the motor running. While charging at 67 mA it reads about 8 mV high.
- Take battery readings with the motor off and it is negligible.
- The charger regulates at its own VBAT pin, so the cell ends a few mV below 4.20 V, which is harmless. DW01A overcharge is 4.3 V, above the MCP73831-2's 4.2 V.

**N5. FET suitability.** (Confidence: medium; specs from the LCSC/JLC listings, not a datasheet table.)
- TECH PUBLIC FS8205A C2830320: 20 V, 6 A, Vgs(th) 1.2 V, Rds(on) 31.5 mΩ at 2.5 V. flux.ai lists 22.5 mΩ at 4.5 V. Vgs is ±10 V per LCSC.
- At Vgs 2.5–4.2 V the pair is about 45–63 mΩ. With the DW01A's roughly 150 mV overcurrent threshold, the trip is about 2.4–3.3 A, matching the intended ~3 A.
- At the 0.3 A load: about 15–19 mV drop and about 6 mW. That suits a 1-cell pack.

**N6. Part numbers.** (Confidence: high.)
- C351410 = PUOLOP DW01A, SOT-23-6.
- C2830320 = TECH PUBLIC FS8205A, SOT-23-6.
- C160402 = JST SM02B-SRSS-TB(LF)(SN), 1 A rated.
- All three are JLC Extended, so expect three extended-part fees. Check stock at order time; the LCSC pages showed about 93k and 186k.
- All 39 gym BOM rows carry the LCSC number from design.py, with matching footprints. J103 and J104 are DNP and absent from the BOM and CPL. J102 is now assembled and present in both.

**N7. Current-path widths.** (Confidence: medium.)
- All VBAT, BATT_N, PROT_D and SYS copper is 0.2 mm.
- VBAT from J102 to Q102: about 26 mm (F 11 mm, B 10 mm, F 5 mm, 2 vias), about 65 mΩ at 1 oz.
- BATT_N from J102 to Q103: 4.5 mm.
- Q103 pin 3 GND connects solidly to the F.Cu pour (FULL zone connection), and that island has 4 stitching vias.
- SYS to the LDO detours about 19 mm on In2 (0.5 oz, about 95 mΩ).
- This is adequate for under 0.3 A, and a 3 A overcurrent event (OC delay about 10 ms) won't hurt the traces. A sustained 1–2.5 A fault below the trip point would overheat both the 0.2 mm traces and the 1 A JST-SH. Widen VBAT/BATT_N to 0.4–0.5 mm on the next spin.

**N8. J102 orientation is correct.** (Confidence: medium-high.)
- The F.Fab body runs from y −1.675, the contact side with the pin-1 mark (contacts at y −2), to +2.575, the 4.25 mm housing depth with the MP fitting nails at y +1.875.
- At rot 0, the mating face is at y=126.18, about 0.8 mm inside the y=127 edge, and the opening faces the edge.
- The MP pads have no net (verified).
- The mated SHR plug will overhang the edge by a couple of mm. Allow for that, plus the wire bend, in the enclosure.
- JST doesn't standardise cell-lead polarity on pre-wired SH cells. Verify that the purchased cell has + on pin 1; the DW01A gives no reverse-polarity protection.

**N9. Antenna.** (Confidence: low-medium.)
- The footprint keepout (x 100.55–104.3, y 105.8–118.2, all 4 layers) is clean: zero fill, track, via or pad intersection.
- The 3 mm growth puts GND/3V3 copper on all layers from y 118.2 to 127, directly below the antenna end. That is about 75% of that strip, now 8.8 mm deep, plus J102 (2 mm from the keepout corner), its cable and the J104 motor leads.
- Raytac's spec (§2.3) asks for the no-ground area to be "as wide as you can". Consider extending the keepout to the top and bottom edges in the x 100–104.3 column on a later spin, and route the battery/motor leads away from the antenna.

**N10. The In2 +3V3 plane is one region.** (Confidence: high.)
- It is a single 796.6 mm² region, and all 10 +3V3 vias land in it.
- It is slotted by In2 tracks: SYS at x=121.3 (y 104.8→119.8), VBAT, CHG_STAT, PROT_VM, IMU_CS, nRESET and SWDIO. It stays connected around the ends of each slot. The LDO (x≈126) reaches the module (x≈114) via a continuous band at y≈101–104.

**N11. DRC and ERC.** (Confidence: high.)
- `pcb drc --severity-all --schematic-parity`: 0 errors, 0 unconnected, 0 parity issues. The 15 warnings are all silkscreen.
- Netlist versus design.py, pin for pin, on both the schematic and the PCB pads: 0 mismatches across 154 pins.
- ERC: 5 `lib_symbol_mismatch` warnings only. The DW01A embedded copy differs from the library, but its pin numbers and names are identical.

**N12. The panel matches final/.** (Confidence: high.)
- The gym copper is identical to final/ per net (track count, length and vias).
- All 39 gym CPL rows match the panel footprints in position, rotation and layer. The origin is (0, 70), Y up, and the board offset is (+3.1, −82.5).
- Panel DRC: warnings only.

**N13. Remaining tab clearances.**
- Top tab (gym x 6–10): J103 (DNP) 0.8 mm, U102 2.0 mm. That is better than v1's 1.2 mm.
- Bottom tab at x=31: D102 and R109 1.8 mm.
- Bottom tab at x=22.5: R108 1.6 mm, R106 and R104 1.8–1.9 mm.
- These resistors lie parallel to the break line. Break the tabs with pliers rather than by flexing the board.

**N14. First cell insertion may leave the board "dead".** (Confidence: medium.)
- With the FETs off at connect, the load through Q102's body diode can hold VM high, and the DW01A may latch off.
- Plugging USB in once (charger detection) restores it. Mention this in the README.

**N15. Bottom side.**
- The battery under the USB half will press on J101's through-hole shell legs and solder. Put Kapton or foam between them to avoid puncturing a LiPo pouch.
- B.Paste contains the two DNP J104 pads. Select a top-only stencil, or remove the paste from J104.
- J104 sits under Q103 and J102. Use a quick iron on the motor wires and add strain relief, because the SMD pads have little peel strength. The J104 silk polarity is correct ("+" = pad 1 = SYS).

**N16. Silkscreen (cosmetic).**
- The U106 reference sits on Q101's outline and pads (clipped by the mask).
- The D102 label is on the D104 outline (unchanged from round 2).
- The Q101 label overlaps D101. The C101 label is on the U101 outline.
