# Pod v2: order status and shopping list (updated Nov 14, 2026)

## JLC order W2026111321500069: PAID Nov 14 (PCB + 2 assembled + 2 resin cases, US$164.09)
The case passed the thin-wall review with the fixed lugs. Next: wait for both parcels, then assemble (see the walkthrough below).
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

## AliExpress order: BOUGHT (Nov 14, 2026)
19 items, S$129.48 + 9% GST (~S$141). Arrives in several parcels over ~1-2 weeks; the
batteries likely last (lithium goes by a slower route). Track each parcel in the AliExpress app.

### For the pod
- [x] XINJ 301020 3.7 V lipo, 5 pcs: check each reads 3.7-4.2 V on the multimeter before soldering
- [x] 0820 coin vibration motors (8 x 2 mm), 5 pcs
- [x] Clone Pico RP2040 (USB-C), 2 pcs: SWD probe via debugprobe firmware (the spare is a backup)
- [x] M2 washers, carbon steel (magnetic)
- [x] Kapton tape 10 mm, double-sided foam tape 0.5 mm, B-7000 glue
- [x] 30 AWG silicone wire, 5 colours

### Tools and bench basics
- [x] FNIRSI HS-02 iron (no charger in the box: use a USB-C PD laptop charger, 65 W+)
- [x] Mechanic 63/37 solder 0.5 mm, KEK 559 flux paste, solder wick 2 mm
- [x] ESD tweezers, flush cutters, ESPLB silicone mat
- [x] ANENG DM850 multimeter
- [x] 830-hole breadboard kit, female-female jumper wires (20 cm, 40), 40-pin male headers (10)

### Still to get locally
- [ ] Isopropyl alcohol 99% + cotton swabs
- Wire stripper: borrow from the lab

Later, only if needed: hot air station, bench power supply with current limit,
logic analyzer, USB microscope.

RS Singapore alternatives if needed: Debug Probe 219-258 (S$27.40), motor
PUI HD-EM0802-LW15-R 276-753 (S$3.36, 8 x 2 mm). Not 276-754 (3 mm thick).

## Firmware port to-do (when the boards arrive)
- Motor buzzes from the app: **1 buzz = rest done**, **2 buzzes = set starting**
  (new OP_BUZZ [op][count] command; the app sends it at rest end and when the countdown finishes).
- Port the logger from the XIAO to the E73 + LSM6DSV16X (SPI), W25Q16 flash, LEDs, motor, charge status.
- No more laptop dumps: sets already reach the phone live over Bluetooth, so the new firmware should
  (a) send any set the phone missed over BLE when it reconnects, then (b) free that space on the
  pod's flash automatically. Add an erase-over-BLE command for the app.
