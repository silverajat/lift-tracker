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
