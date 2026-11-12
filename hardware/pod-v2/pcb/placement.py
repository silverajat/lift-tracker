"""Part placement for lift pod v2, Ebyte E73 version (mm from the board's
top-left corner, y down).

Board 41 x 18 mm, 0.8 mm, 4 layers. The board is the pod's underside:
everything is assembled on top; the bottom face only has the charger
contacts and the SWD pads, under solder mask everywhere else.

STACK (bottom -> top): board 0.8 | E73 module 3.0 + coin motor 2.5 on it  |
right-hand parts (<= 1.1 mm) + LiPo 3.0 on them | shell top.

TOP
  x 0.3-19.3   Ebyte E73 (rotated 270: antenna at the left edge x 0.3-3.7,
               pad rows along the top and bottom edges, power + SPI pads
               facing right). Coin motor sits on it at (11.3, 9).
  x 19.5-21.5  module decoupling
  (24.2, 9)    5 mm steel disc for the charger magnet (no parts, no vias)
  above it     2 MB flash (USON 2x3)    below it   IMU, caps, motor pads
  x 28.5-41    charging, LDO, motor driver, LEDs, cell pads
  The 20.5 x 11 mm cell lies on x 19.9-40.4, y 3.5-14.5; LEDs sit in the
  strip above it so their light can get out.
BOTTOM
  charger contacts at (15.2, 9) and (33.2, 9), 18 mm apart
  SWD pads under the module's top edge (cover with kapton after flashing)
"""
BOARD_W, BOARD_H, CORNER_R = 41.0, 18.0, 2.0

STEEL = (24.2, 9.0)              # charger magnet centre
CELL = (19.9, 3.5, 40.4, 14.5)   # cell outline on top
MOTOR = (11.3, 9.0, 4.0)         # on the module: centre x, y, radius
ANTENNA = (0.0, 0.0, 4.0, 18.0)  # nothing else here, any layer

UX, UY = 11.3, 9.0               # module centre (footprint origin), rotation 270
# module body and the area inside its pad ring, board coords
MODULE_BODY = (UX - 11.0, UY - 6.5, UX + 7.0, UY + 6.5)
MODULE_INSIDE = (UX - 7.6, UY - 3.8, UX + 4.3, UY + 3.8)   # no F.Cu tracks here

PLACE = {
    "U1": (UX, UY, 270),           # E73: antenna x 0.3-3.7, pads to x 19.3, y 1.5-16.5

    # column beside the module's right-hand pad row (VDD y 8.4, VDDH y 5.8)
    "C2": (20.2, 8.0, 90),         # 3V3 100 nF
    "C1": (20.2, 5.9, 90),         # 3V3 10 uF

    # above the steel disc: flash
    "U7": (23.5, 3.2, 90),         # W25Q16 USON 2x3: CLK/DI face the module side, CS/DO the right
    "C14": (22.6, 5.25, 0),        # flash decoupling (VCC pin bottom-left)
    "R13": (25.9, 5.5, 0),         # flash CS pull-up

    # below the steel disc: IMU, its caps, motor wire pads, battery sense
    "U2": (23.2, 13.6, 0),         # BMI270: MOSI/SCK/CS along the top, MISO/INT1 on the left
    "C4": (25.6, 12.8, 90),        # IMU VDD
    "C5": (26.7, 12.8, 90),        # IMU VDDIO
    "R14": (26.7, 14.75, 90),      # IMU CS pull-up
    "J4": (35.0, 8.7, 0),          # motor wire pads, open spot under the cell (wires run under it)
    "R5": (32.9, 11.0, 90),        # VBAT divider top, beside the other VBAT parts
    "R6": (32.9, 13.2, 90),        # VBAT divider bottom
    "C8": (20.3, 15.6, 90),        # VBAT sense hold, at the module's ADC pin

    # top strip, outside the cell: LEDs + charger programming
    "D3": (34.9, 1.2, 0),          # charge LED (red)
    "R4": (37.4, 1.2, 180),        # charge LED resistor, right beside D3's anode
    "R3": (37.4, 2.6, 0),          # PROG 47k
    "C6": (38.9, 1.6, 90),         # charger in

    # green status LED in the bottom strip (outside the cell), near the module's LED pin
    "D2": (27.0, 16.9, 180),       # status LED (green), anode faces R9
    "R9": (24.9, 16.7, 90),

    # under the cell: bridge, charger, LDO, cell pads
    "D8": (30.7, 4.55, 0),         # bridge, pad B -> VCHG
    "D9": (30.7, 6.6, 0),          # bridge, GND -> pad B
    "D5": (33.7, 5.0, 90),         # TVS on VCHG
    "U3": (36.7, 5.0, 0),          # MCP73831
    "C7": (39.9, 4.6, 90),         # charger out (VBAT)
    "J2": (39.3, 9.5, 90),         # cell wire pads (cell's protection board end)
    "D6": (30.7, 11.45, 0),        # bridge, pad A -> VCHG
    "D7": (30.7, 13.45, 0),        # bridge, GND -> pad A
    "U4": (35.6, 12.8, 0),         # XC6206 3.3 V
    "C9": (38.9, 13.3, 90),        # LDO in
    "C10": (40.1, 13.3, 90),       # LDO out

    # bottom strip right: motor driver
    "Q1": (31.2, 16.2, 180),       # drain faces the motor pads, gate faces R7/R8
    "R7": (34.3, 15.4, 0),         # gate 100R
    "R8": (34.3, 16.7, 0),         # gate pull-down 100k
    "D1": (37.3, 16.2, 0),         # motor flyback

    # ---- underside ("B") ----
    "J1": (STEEL[0], STEEL[1], 0, "B"),   # charger contacts at x 15.2 and 33.2
    "J3": (7.5, 4.6, 180, "B"),    # SWD pads under the module's top edge (SWDIO at x 9)
}

# board-only mechanical footprints (not in the schematic)
MECH = {
    "MK1": ("lift", "Steel_Target_D5mm", STEEL[0], STEEL[1], 0),
}
