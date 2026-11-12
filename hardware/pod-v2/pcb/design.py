"""
Lift pod v2 (the final, Fitbit-sized one): the whole circuit as data.

Same flow as v1.1: each part is ref, (library, symbol), value, footprint,
LCSC number, and a map of pin number -> net. gen_schematic.py turns this
into a KiCad schematic; build_pcb.py places it from placement.py.

What changed from v1.1 (see hardware/pod-v2/SPEC.md)
  - No USB-C, no USB ESD, no CC resistors: data and updates go over BLE.
  - Charging through 2 bottom pads (POGO_IN, GND) from our magnetic dock.
    TVS + Schottky on the input; the input also goes to the nRF's VBUS pin
    so plugging into the dock wakes the chip (USB power-detect event).
  - No button (tap gestures from the IMU), no load-sharing path (the pod
    isn't used while it charges), no JST socket (cell wires solder to pads).
  - New: 2 MB SPI flash for recordings, sharing the IMU's SPI bus.
  - No 32 kHz crystal: the internal RC (calibrated, ~250 ppm) is enough,
    because the phone re-syncs the time on every connection.
  - No on-board battery protection: the cell has its own (datasheet PCM).
  - LDO: XC6206 (~1 uA quiescent) instead of ME6211 (~40 uA), since a
    40 mAh cell can't afford ~1 mAh/day of regulator drain.

nRF52840 pins (Raytac MDBT50Q-1MV2 pin number -> GPIO -> use)
  37 P0.13 SPI_SCK    36 P0.14 SPI_MOSI   39 P0.15 SPI_MISO
  38 P0.16 IMU_CS     41 P0.17 IMU_INT1   44 P0.20 IMU_INT2
  42 P0.19 FLASH_CS
  20 P0.04 VBAT_SENSE (AIN2)   26 P1.09 MOTOR_EN   24 P0.08 LED
  22 P0.06 CHG_STAT (MCP73831 STAT, low while charging; internal pull-up)
  32 VBUS  <- charger input (dock detect)      40 P0.18 nRESET
  51 SWDIO  53 SWDCLK (bottom test pads)
"""

NC = None  # pin left unconnected (gets a no-connect flag)

R0402 = "Resistor_SMD:R_0402_1005Metric"
C0402 = "Capacitor_SMD:C_0402_1005Metric"

PARTS = [
    # ---- nRF52840 module ------------------------------------------------
    dict(ref="U1", sym=("RF_Module", "MDBT50Q-1MV2"), value="MDBT50Q-1MV2",
         fp="RF_Module:Raytac_MDBT50Q", lcsc="C5118826", unused_nc=True,
         pins={"1": "GND", "2": "GND", "15": "GND", "33": "GND", "55": "GND",
               "28": "+3V3", "30": "+3V3",          # VDD, VDDH tied: normal-voltage mode
               "31": NC,                            # DCCH unused
               "32": "VCHG",                        # VBUS pin: dock detect
               "34": NC, "35": NC,                  # USB data unused
               "37": "SPI_SCK", "36": "SPI_MOSI", "39": "SPI_MISO", "38": "IMU_CS",
               "41": "IMU_INT1", "44": "IMU_INT2", "42": "FLASH_CS",
               "17": NC, "18": NC,              # no 32 kHz crystal: internal RC + phone time sync
               # right-hand column, facing the power parts (easier to route):
               "20": "VBAT_SENSE",                  # P0.04 = AIN2
               "22": "CHG_STAT", "24": "LED", "26": "MOTOR_EN",   # P0.06, P0.08, P1.09
               "40": "nRESET", "51": "SWDIO", "53": "SWDCLK"}),
    dict(ref="C1", sym=("Device", "C"), value="10uF", fp=C0402,
         lcsc="C15525", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C2", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C3", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "VCHG", "2": "GND"}),   # nRF VBUS decoupling


    # ---- IMU: BMI270 on SPI (BMI160 symbol: same LGA-14 pinout) ----------
    dict(ref="U2", sym=("Sensor_Motion", "BMI160"), value="BMI270",
         fp="Package_LGA:Bosch_LGA-14_3x2.5mm_P0.5mm", lcsc="C2836813",
         pins={"1": "SPI_MISO",    # SDO
               "2": NC, "3": NC,   # ASDx/ASCx: aux I2C unused, may float
               "4": "IMU_INT1", "5": "+3V3", "6": "GND", "7": "GND", "8": "+3V3",
               "9": "IMU_INT2", "10": NC, "11": NC,   # OCSB/OSDO: aux SPI, open
               "12": "IMU_CS", "13": "SPI_SCK", "14": "SPI_MOSI"}),
    dict(ref="C4", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),   # VDD
    dict(ref="C5", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),   # VDDIO

    # ---- 2 MB flash for recordings, on the same SPI bus -------------------
    dict(ref="U7", sym=("Memory_Flash", "W25Q32JVSS"), value="W25Q16JVSNIQ",
         fp="Package_SO:SOIC-8_3.9x4.9mm_P1.27mm", lcsc="C2456211",
         pins={"1": "FLASH_CS", "2": "SPI_MISO", "3": "+3V3", "4": "GND",
               "5": "SPI_MOSI", "6": "SPI_SCK", "7": "+3V3", "8": "+3V3"}),
    dict(ref="C14", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="R13", sym=("Device", "R"), value="100k", fp=R0402,
         lcsc="C25741", pins={"1": "+3V3", "2": "FLASH_CS"}),   # flash deselected at boot

    # ---- Charging input: 2 pads on the bottom, from the magnetic dock ------
    # The dock's magnets only fit one way round, but the input is still
    # protected: TVS for ESD on the exposed pads, Schottky against reverse.
    dict(ref="J1", sym=("Connector_Generic", "Conn_01x02"), value="DOCK",
         fp="lift:Dock_Pads_2x2.4mm_P6mm", lcsc=None, dnp=True,
         pins={"1": "POGO_IN", "2": "GND"}),
    dict(ref="D5", sym=("Device", "D_Zener"), value="ESD5Z5.0T1G",
         fp="Diode_SMD:D_SOD-523", lcsc="C82044",
         pins={"1": "POGO_IN", "2": "GND"}),                 # unidirectional TVS: K, A
    dict(ref="D4", sym=("Device", "D_Schottky"), value="BAT54WS",
         fp="Diode_SMD:D_SOD-323", lcsc="C2991936",
         pins={"1": "VCHG", "2": "POGO_IN"}),                 # K, A
    dict(ref="U3", sym=("Battery_Management", "MCP73831-2-OT"), value="MCP73831-2-OT",
         fp="Package_TO_SOT_SMD:SOT-23-5", lcsc="C424093",
         pins={"1": "CHG_STAT", "2": "GND", "3": "VBAT", "4": "VCHG", "5": "CHG_PROG"}),
    dict(ref="R3", sym=("Device", "R"), value="47k", fp=R0402,
         lcsc="C25792", pins={"1": "CHG_PROG", "2": "GND"}),
    dict(ref="C6", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "VCHG", "2": "GND"}),
    dict(ref="C7", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "VBAT", "2": "GND"}),
    # charge LED: on while charging (STAT pulls low), works with a flat cell too
    dict(ref="R4", sym=("Device", "R"), value="1k", fp=R0402,
         lcsc="C11702", pins={"1": "VCHG", "2": "LED_CHG_A"}),
    dict(ref="D3", sym=("Device", "LED"), value="RED", fp="LED_SMD:LED_0603_1608Metric",
         lcsc="C2286", pins={"2": "LED_CHG_A", "1": "CHG_STAT"}),

    # ---- Battery: cell wires solder to 2 bottom pads ----------------------
    # Pad + = VBAT, pad - = GND. The cell MUST have its own protection board
    # (the 301120 40 mAh cell: DW01 + 8205, 4.28 V / 2.4 V / 2-6 A cut-offs).
    dict(ref="J2", sym=("Connector_Generic", "Conn_01x02"), value="CELL",
         fp="lift:Cell_Pads_2x1.8x2.4mm_P3mm", lcsc=None, dnp=True,
         pins={"1": "VBAT", "2": "GND"}),
    # battery voltage sense: 1M/1M -> AIN0 (2 uA), 100 nF holds it for the ADC
    dict(ref="R5", sym=("Device", "R"), value="1M", fp=R0402,
         lcsc="C26083", pins={"1": "VBAT", "2": "VBAT_SENSE"}),
    dict(ref="R6", sym=("Device", "R"), value="1M", fp=R0402,
         lcsc="C26083", pins={"1": "VBAT_SENSE", "2": "GND"}),
    dict(ref="C8", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "VBAT_SENSE", "2": "GND"}),

    # ---- 3.3 V: XC6206 LDO from the battery (~1 uA quiescent) --------------
    dict(ref="U4", sym=("Regulator_Linear", "XC6206PxxxMR"), value="XC6206P332MR",
         fp="Package_TO_SOT_SMD:SOT-23-3", lcsc="C5446",
         pins={"1": "GND", "2": "+3V3", "3": "VBAT"}),
    dict(ref="C9", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "VBAT", "2": "GND"}),
    dict(ref="C10", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "+3V3", "2": "GND"}),

    # ---- Vibration motor (coin ERM on top of the board, wires to 2 pads) ----
    dict(ref="Q1", sym=("Transistor_FET", "AO3400A"), value="AO3400A",
         fp="Package_TO_SOT_SMD:SOT-23", lcsc="C20917",
         pins={"1": "MOTOR_G", "2": "GND", "3": "MOTOR_N"}),
    dict(ref="R7", sym=("Device", "R"), value="100", fp=R0402,
         lcsc="C25076", pins={"1": "MOTOR_EN", "2": "MOTOR_G"}),
    dict(ref="R8", sym=("Device", "R"), value="100k", fp=R0402,
         lcsc="C25741", pins={"1": "MOTOR_G", "2": "GND"}),   # motor off at boot
    dict(ref="D1", sym=("Device", "D"), value="1N4148WS", fp="Diode_SMD:D_SOD-323",
         lcsc="C2128", pins={"1": "VBAT", "2": "MOTOR_N"}),   # flyback
    dict(ref="J4", sym=("Connector_Generic", "Conn_01x02"), value="MOTOR",
         fp="lift:Motor_Pads_2x1.2x1.6mm_P2.5mm", lcsc=None, dnp=True,
         pins={"1": "VBAT", "2": "MOTOR_N"}),

    # ---- Status LED (shines through a light pipe in the lid) -----------------
    dict(ref="R9", sym=("Device", "R"), value="330", fp=R0402,
         lcsc="C25104", pins={"1": "LED", "2": "LED_A"}),
    dict(ref="D2", sym=("Device", "LED"), value="GREEN", fp="LED_SMD:LED_0603_1608Metric",
         lcsc="C72043", pins={"2": "LED_A", "1": "GND"}),

    # ---- SWD: 5 bottom test pads for the first flash (then BLE updates) -----
    dict(ref="J3", sym=("Connector_Generic", "Conn_01x05"), value="SWD",
         fp="lift:SWD_Pads_1x05_P1.5mm", lcsc=None, dnp=True,
         pins={"1": "+3V3", "2": "SWDIO", "3": "SWDCLK", "4": "nRESET", "5": "GND"}),

    # ---- Power flags (tell ERC these nets are driven) ----------------------
    dict(ref="#FLG01", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "GND"}, virtual=True),
    dict(ref="#FLG02", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "POGO_IN"}, virtual=True),
    # VCHG is driven by the dock through the Schottky D4
    dict(ref="#FLG03", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "VCHG"}, virtual=True),
]
