# Pod v2: order status and shopping list (Nov 13, 2026)

## JLC order: placed, awaiting payment
- PCB: 5 boards, 4 layers, 0.8 mm, black, ENIG. Standard PCBA (X-ray), top side, 2 boards.
  Checked in the cart: U102 = LSM6DSV16XTR (C5267406), placement preview OK.
  JLC shows 71 x 70 mm because it adds edge rails for Standard PCBA (snap off).
- Case (JLC3DP, Black Resin, qty 2): the original `lift_pod_v2_shell.stl` (43.6 x 20.6 x 7.75 mm),
  kept as ordered. It fits cells up to ~20.5 mm long including the protection board.
  If the cells arrive longer, reprint with `lift_pod_v2_shell_long.stl` (2 mm longer, takes 22 mm).

## Battery
RS, DigiKey, Shopee and Lazada had nothing small enough (DigiKey's SparkFun
40 mAh cells are discontinued; SparkFun won't ship them internationally).
AliExpress: XINJ 301020 3.7 V "80 mAh", 5 pcs (S$6.08, 36 sold). Real capacity
is ~30-40 mAh. Confirm with the seller that it has a protection board (PCM).
Fits the ordered case if <= 4.1 mm thick, <= 12 mm wide, <= ~20.5 mm long (incl. protection board).
If too long: 301015 (3 x 10 x 15 mm, ~25-30 mAh) fits easily, or reprint with the long case.
The old 302030 140 mAh cell is fine for bench testing (doesn't fit the case).

## Shopping list (buy Nov 14; AliExpress unless noted). Tick as you go.

### 1. For the pod (must have)
- [ ] XINJ 301020 3.7 V lipo, 5 pcs (seller confirms protection board); fits <= ~20.5 mm long
- [ ] 0820 coin vibration motor, 3 V, wire leads (8 x 2 mm), pack of 5-10
- [ ] Raspberry Pi Debug Probe (or RS 219-258)
- [ ] M2 washers, zinc-plated steel (NOT stainless: test with a magnet)
- [ ] Kapton tape 10 mm
- [ ] Double-sided foam tape ~1 mm
- [ ] B-7000 glue
- [ ] 30 AWG silicone wire (a few colours)

### 2. Soldering bench (core)
Skip the all-in-one "iron + multimeter + 20 tools" kits: the iron has no real
temperature control and the meter is the weakest part. Buy these separately.
- [ ] Pinecil V2 + a fine conical tip (TS-I / TS-ILS) + silicone stand. Needs a USB-C PD charger 45 W+ (65 W is best)
- [ ] Hot air rework station (e.g. 858D, or a 2-in-1 "8786D"): reflows QFN/LGA chips like the IMU, removes parts
- [ ] Solder 63/37, 0.5 mm and 0.8 mm
- [ ] No-clean flux paste (syringe) + flux pen
- [ ] Solder wick 1.5 mm and 2.5 mm
- [ ] Desoldering pump
- [ ] ESD tweezers set (straight + curved fine tips)
- [ ] Brass tip cleaner (often with the stand)
- [ ] Silicone soldering mat (heat-proof, with part trays)
- [ ] PCB holder / helping hands (magnetic base)
- [ ] Small fume extractor fan (carbon filter)
- [ ] ESD wrist strap
- [ ] Flush cutters (Engineer NN-46 or Hakko CHP-170) and a fine wire stripper (Engineer PA-14 or a 30-20 AWG stripper)
- [ ] Precision screwdriver set
- [ ] Heat-shrink tubing assortment
- [ ] Isopropyl alcohol 99% + ESD brush + lint-free swabs (IPA: buy locally)

### 3. Measuring and debugging
- [ ] Multimeter: UNI-T UT61E+ (best value) or ANENG AN8008 (budget). Needs continuity, mA range, diode test
- [ ] Digital calipers (measure batteries, parts, case fits)
- [ ] USB microscope or magnifier lamp (inspect 0.5 mm-pitch joints)
- [ ] Bench power supply with current limit (e.g. FNIRSI DPS-150 or Riden RD6006): first power-up of a new board at 50 mA
- [ ] 8-channel 24 MHz logic analyzer (works with PulseView): watch SPI between the nRF and the IMU
- [ ] USB-C power meter (FNIRSI FNB58): check charging current and power draw
- [ ] Optional: small oscilloscope (school labs have proper ones)

### 4. Stock to keep around
- [ ] Breadboard + Dupont jumper wires (M-M, M-F, F-F)
- [ ] Pin headers (2.54 and 1.27 mm)
- [ ] 0402 and 0603 resistor + capacitor sample books (for fixes and mods)
- [ ] Spare LEDs (0603), a few AO3400 MOSFETs, 1N4148 diodes
- [ ] Pogo pins / test hook clips (for SWD pads)
- [ ] LiPo-safe charging bag

## Buy on AliExpress
- 0820 coin vibration motor, 3 V, wire leads (8 x 2 mm), pack of 5-10
- Raspberry Pi Debug Probe (or a Pico with the debugprobe firmware)
- Pinecil V2 (needs a USB-C PD charger, 45 W+)
- 63/37 solder 0.5 mm, no-clean flux pen, ESD tweezers, solder wick 2 mm, cheap multimeter
- 30 AWG silicone or kynar wire, M2 zinc-plated steel washers (not stainless),
  Kapton tape 10 mm, double-sided foam tape ~1 mm, B-7000 glue
- Isopropyl alcohol: buy locally (won't ship)

RS Singapore alternatives if needed: Debug Probe 219-258 (S$27.40), motor
PUI HD-EM0802-LW15-R 276-753 (S$3.36, 8 x 2 mm). Not 276-754 (3 mm thick).
