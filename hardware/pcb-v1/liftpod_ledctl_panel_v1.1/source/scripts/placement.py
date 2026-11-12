"""Part placement for the gym pod v1 board (mm from the board's top-left).

  x = 0 ............................................. x = 38
  [antenna][ nRF52840 module ][ power + support parts ][ USB-C ]
  Top strip above the module: SWD pads, IMU, LDO. Bottom strip: battery
  and motor wire pads, motor driver. Charging end: ESD, charger, LEDs,
  battery sense, user button.
"""
BOARD_W, BOARD_H, CORNER_R = 38.0, 27.0, 4.0   # 27: +3 mm strip for battery protection

PLACE = {
    "U1": (8.3, 12.0, 90),        # module, antenna at the left edge

    # top strip
    "J3": (5.0, 2.6, 90),         # SWD 1x05 1.27 mm: 3V3 SWDIO SWDCLK nRESET GND
    "U2": (13.6, 3.0, 0),         # BMI270, right above the SPI pins
    "C4": (17.4, 2.0, 0),
    "C5": (17.4, 3.9, 0),
    "C9": (21.0, 3.0, 90),        # LDO in (VBAT)
    "U4": (24.0, 3.0, 0),         # ME6211 3.3 V LDO
    "C10": (26.8, 3.0, 90),       # LDO out (3V3)
    "SW1": (31.6, 3.4, 0),        # USER button

    # beside the module's supply pins
    "C3": (18.6, 7.0, 0),        # VBUS
    "C1": (18.6, 8.8, 0),        # 3V3 10 uF
    "C2": (18.6, 10.6, 0),       # 3V3 100 nF
    "C7": (18.6, 16.3, 0),       # charger out (VBAT)

    # USB-C, ESD, CC resistors, charger
    "J1": (34.3, 12.0, 90),
    "U5": (24.4, 9.0, 0),
    "R2": (28.2, 8.4, 90),
    "R1": (28.2, 15.6, 90),
    "U3": (22.9, 14.2, 0),        # MCP73831
    "C6": (26.5, 14.2, 90),       # charger in (VBUS)
    "R3": (22.9, 17.0, 0),        # PROG 15k
    # load sharing
    "Q2": (18.8, 13.4, 0),         # P-FET: battery -> SYS when unplugged
    "R10": (22.4, 11.6, 0),       # Q2 gate pull-down
    "D4": (33.4, 21.6, 0),        # Schottky: USB -> SYS

    # bottom strip
    "J2": (5.8, 23.6, 0),         # JST-SH battery socket, opening at the bottom edge
    # battery protection (placeholders, refined by search)
    "Q3": (11.0, 20.4, 0),       # FS8205A protection FETs
    "U6": (15.0, 20.0, 90),      # DW01A protection IC
    "C11": (17.6, 20.4, 90),     # DW01A VCC filter
    "R11": (18.4, 18.0, 0),      # DW01A VCC 100R
    "R12": (10.4, 23.8, 90),     # DW01A VM 1k
    "J4": (8.5, 20.5, 0, "B"),   # motor wire pads, BOTTOM, antenna end (battery goes under the USB half, away from the antenna)
    "Q1": (13.8, 24.0, 0),
    "D1": (17.2, 24.0, 90),       # flyback
    "R7": (20.0, 22.6, 0),        # gate series 100R
    "R8": (20.0, 24.4, 0),        # gate pull-down 100k

    # charging end, bottom: battery sense + LEDs
    "R5": (23.0, 22.2, 0),
    "R6": (23.0, 24.2, 0),
    "C8": (26.2, 22.2, 0),
    "R4": (26.2, 24.2, 0),        # charge LED resistor
    "D3": (29.4, 22.2, 0),        # charge LED (red)
    "R9": (29.4, 24.2, 0),        # user LED resistor
    "D2": (32.4, 24.2, 0),        # user LED (green)
}
