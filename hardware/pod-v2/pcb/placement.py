"""Part placement for lift pod v2 (mm from the board's top-left corner).

Board 34 x 18 mm, 0.8 mm thick, 4 layers. Antenna at the left edge.

TOP (all assembled parts)
  x = 0 ........ 16.6 ........................................ 33
  [antenna][ nRF52840 module, lower part of the board ][ power, charger,
  strip above the module: IMU right above the SPI pins    protection, flash,
                                                          LDO, motor driver, LEDs ]
BOTTOM (nothing assembled, only pads; the cell and motor sit under the board)
  x 0-4.5 antenna (nothing under it)
  x 4.5-12.5 coin motor (8 mm), SWD pads below it
  x 12.5-33, y 0-11.5 the 301120 cell (11 x 20.5 mm max)
  y 11.5-17 strip: cell wire pads, dock charging pads
"""
BOARD_W, BOARD_H, CORNER_R = 34.0, 18.0, 3.0

PLACE = {
    "U1": (8.3, 11.4, 90),        # module: x 0-16.6, y 4.2-16.6, antenna at x = 0

    # strip above the module (y 0-4.2, x 4.5-16.6): IMU over the SPI pins
    "U2": (12.75, 2.5, 180),         # BMI270 (SPI pins at x 9.7-13.3, y 5.8-6.7)
    "C4": (15.9, 1.4, 0),         # IMU VDD
    "C5": (15.9, 3.1, 0),         # IMU VDDIO

    # beside the module's supply / crystal pins (x 15.45)
    "C14": (23.4, 5.3, 90),        # flash decoupling
    "U7": (19.9, 5.2, 90),        # SOIC-8 flash: x 16.9-22.4, y 0.9-8.4
    "C2": (18.0, 9.8, 0),         # 3V3 100 nF (VDD pin y 8.8)
    "C1": (18.0, 11.1, 0),        # 3V3 10 uF
    "C3": (20.8, 11.1, 0),        # VBUS pin (VCHG)
    "R13": (20.3, 17.2, 0),       # flash CS pull-up

    # charging input + charger (top right)
    "D5": (24.7, 1.1, 0),         # TVS on POGO_IN
    "D4": (24.9, 3.1, 0),         # Schottky POGO_IN -> VCHG
    "U3": (29.3, 2.3, 0),         # MCP73831
    "C6": (32.9, 4.9, 90),        # charger in
    "R3": (25.1, 6.2, 0),         # PROG 47k
    "C7": (25.1, 7.5, 0),         # charger out (VBAT)
    "D3": (28.9, 5.5, 0),         # charge LED (red)
    "D2": (28.9, 7.3, 0),         # status LED (green)
    "R4": (31.8, 7.4, 90),        # charge LED resistor

    # motor driver (next to MOTOR_EN, module pin 26; motor pads on the bottom)
    "Q1": (24.7, 10.7, 0),
    "R7": (27.7, 9.7, 0),         # gate 100R
    "R8": (27.7, 11.2, 0),        # gate pull-down 100k
    "R9": (30.7, 13.1, 0),        # status LED resistor
    "D1": (30.6, 10.5, 90),       # motor flyback

    # 3.3 V LDO + battery sense
    "U4": (25.5, 15.9, 0),        # XC6206
    "C9": (29.3, 14.9, 0),        # LDO in
    "C10": (29.3, 16.4, 0),       # LDO out
    "R5": (31.7, 14.9, 0),
    "R6": (31.7, 16.4, 0),
    "C8": (33.3, 9.5, 90),

    # ---- bottom-side pads ("B") ----
    "J4": (8.5, 11.4, 0, "B"),    # motor wires (motor under x 4.5-12.5, y 1-9)
    "J3": (11.7, 11.4, 90, "B"),  # SWD pads, a column under the module centre (clear of its pin rows)
    "J2": (15.4, 15.0, 0, "B"),   # cell wires (+ at x 13.9, - at x 16.9)
    "J1": (25.5, 15.0, 0, "B"),   # dock pads: VIN at x 22.5, GND at x 28.5
}
