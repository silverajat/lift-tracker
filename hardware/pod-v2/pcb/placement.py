"""Part placement for lift pod v2, Fitbit Air charger version (mm from the
board's top-left corner, y down).

Board 34 x 18 mm, 0.8 mm, 4 layers. The board is the pod's underside:
everything is assembled on top, and the bottom face only has the charger
contacts and the SWD test pads, under solder mask everywhere else.

STACK (bottom -> top): board 0.8 | parts (module 2.2, others <= 1.75) |
coin motor on the module, LiPo cell on the right-hand parts | lid.

TOP
  x 0-12.1   nRF52840 module, antenna in the top-left corner (y 0-5)
             coin motor (8 mm) sits on the module, centred at (6.3, 12.5)
  x 12.1-15.8  IMU right next to the SPI pins, decoupling below it
  (18.5, 9)  5 mm steel disc for the charger magnet (no parts, no vias)
  above it   2 MB flash        below it   IMU caps, motor wire pads
  x 21-34    charging, LDO, motor driver; the 20.5 x 11 mm cell lies on
             top of these (x 12.8-33.3, y 3.5-14.5); LEDs along the top
             edge, outside the cell, so their light can get out
BOTTOM
  charger contacts at (9.5, 9) and (27.5, 9), 18 mm apart, either way round
  SWD pads under the module, between the antenna corner and contact A
  (cover with kapton after flashing)
"""
BOARD_W, BOARD_H, CORNER_R = 34.0, 18.0, 2.0

STEEL = (18.5, 9.0)          # charger magnet centre
CELL = (12.8, 3.5, 33.3, 14.5)   # cell outline on top (for drawings + checks)
MOTOR = (6.3, 12.5, 4.0)     # centre x, y, radius
ANTENNA = (0.0, 0.0, 12.5, 5.0)  # nothing else in here, any layer

PLACE = {
    "U1": (6.3, 9.0, 0),          # module: courtyard x 0.5-12.1, y 0.7-17.3

    # strip beside the module's SPI pins (x 12.1-15.8)
    "U2": (14.1, 12.75, 90),      # BMI270, SPI pins facing the module
    "C4": (16.9, 13.3, 90),       # IMU VDD
    "C5": (18.1, 13.3, 90),       # IMU VDDIO

    # above the steel disc
    "U7": (16.6, 3.0, 0),         # SOIC-8 flash: x 12.85-20.35, y 0.25-5.75
    "C14": (21.2, 1.4, 90),       # flash decoupling (VCC pin 8 at 19.1, 1.1)

    # bottom strip under the module's power pins (VDD 7.9/8.7, VBUS 10.3, y 16.15)
    "C2": (13.2, 15.3, 0),        # 3V3 100 nF
    "C1": (13.2, 16.6, 0),        # 3V3 10 uF
    "C3": (15.3, 16.6, 0),        # nRF VBUS (VCHG)
    "R5": (15.3, 15.3, 0),        # VBAT divider top
    "R6": (17.6, 14.85, 0),       # VBAT divider bottom
    "C8": (19.6, 14.85, 0),       # VBAT sense hold
    "J4": (18.7, 16.5, 0),        # motor wire pads (motor sits on the module)

    # top edge, outside the cell: LEDs + charger programming
    "D2": (24.2, 1.2, 0),         # status LED (green)
    "R9": (24.2, 2.7, 0),
    "D3": (27.6, 1.2, 0),         # charge LED (red)
    "R4": (27.6, 2.7, 0),
    "R3": (30.6, 1.3, 0),         # PROG 47k
    "C6": (30.6, 2.7, 0),         # charger in

    # under the cell: bridge, charger, LDO, cell pads
    "D8": (23.4, 4.55, 0),        # bridge, pad B -> VCHG
    "D9": (23.4, 6.6, 0),         # bridge, GND -> pad B
    "D5": (26.4, 5.0, 90),         # TVS on VCHG
    "U3": (29.4, 5.0, 0),         # MCP73831
    "C7": (32.6, 4.6, 90),        # charger out (VBAT)
    "J2": (32.4, 9.5, 90),        # cell wire pads (cell's protection board end)
    "D6": (23.4, 11.45, 0),       # bridge, pad A -> VCHG
    "D7": (23.4, 13.45, 0),       # bridge, GND -> pad A
    "U4": (28.6, 12.8, 0),        # XC6206 3.3 V
    "C9": (31.6, 13.3, 90),       # LDO in
    "C10": (32.8, 13.3, 90),      # LDO out

    # bottom strip right: motor driver
    "Q1": (23.2, 16.2, 0),
    "R7": (26.4, 15.4, 0),        # gate 100R
    "R8": (26.4, 16.7, 0),        # gate pull-down 100k
    "D1": (29.5, 16.2, 0),        # motor flyback

    # ---- underside ("B") ----
    "J1": (STEEL[0], STEEL[1], 0, "B"),   # charger contacts at x 9.5 and 27.5
    "J3": (7.0, 6.3, 180, "B"),   # SWD pads under the module, above contact A (SWDIO at x 8.5)
}

# board-only mechanical footprints (not in the schematic)
MECH = {
    "MK1": ("lift", "Steel_Target_D5mm", STEEL[0], STEEL[1], 0),
}
