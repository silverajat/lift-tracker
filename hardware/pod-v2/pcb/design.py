"""
Lift pod v2 (the final, Fitbit-sized one): the whole circuit as data.

Same flow as v1.1: each part is ref, (library, symbol), value, footprint,
LCSC number, and a map of pin number -> net. gen_schematic.py turns this
into a KiCad schematic; build_pcb.py places it from placement.py.

What changed from v1.1 (see hardware/pod-v2/SPEC.md)
  - No USB-C, no USB ESD, no CC resistors: data and updates go over BLE.
  - Charging from the Google Fitbit Air charger: 2 ENIG pads on the
    underside, 18 mm apart, with a 5 mm steel disc between them for the
    charger's centre magnet. The pod can sit either way round, so the pads
    feed a bridge rectifier (4x B5819WS Schottky) into VCHG, with a TVS on VCHG. The
    bridge diodes also steer ESD on the exposed pads into that TVS. VCHG
    goes to the nRF's VBUS pin, so putting the pod on the charger wakes it.
  - No button (tap gestures from the IMU), no load-sharing path (the pod
    isn't used while it charges), no JST socket (cell wires solder to pads).
  - New: 2 MB SPI flash for recordings, sharing the IMU's SPI bus.
  - No 32 kHz crystal: the internal RC (calibrated, ~250 ppm) is enough,
    because the phone re-syncs the time on every connection.
  - No on-board battery protection: the cell has its own (datasheet PCM).
  - LDO: XC6206 (~1 uA quiescent) instead of ME6211 (~40 uA), since a
    40 mAh cell can't afford ~1 mAh/day of regulator drain.

nRF52840 pins (Raytac MDBT50Q-1MV2 pin number -> GPIO -> use). The module's
right-hand pad column faces the rest of the board, so the pins are picked in
the same order as the pads they go to (no crossings):
  IMU on its own SPI bus (spi1), next to the IMU:
    43 P0.21 IMU_INT2   41 P0.17 IMU_CS    39 P0.15 IMU_SCK
    38 P0.16 IMU_MOSI   37 P0.13 IMU_MISO  36 P0.14 IMU_INT1
  Flash on a second SPI bus (spi2), lines run up under the flash:
    50 P1.02 FL_MISO    49 P0.25 FLASH_CS  48 P0.24 FL_SCK   47 P1.00 FL_MOSI
  46 P0.22 LED          45 P0.23 CHG_STAT (MCP73831 STAT, low while charging; pull-up)
  20 P0.04 VBAT_SENSE (AIN2)   26 P1.09 MOTOR_EN   (bottom row)
  32 VBUS  <- VCHG (charger detect)
  51 SWDIO  53 SWDCLK (pads on the underside; no reset pad: the nRF52 programs
  and recovers over SWD alone, and the flash's CS is driven high at boot)
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
               "43": "IMU_INT2", "41": "IMU_CS", "39": "IMU_SCK",
               "38": "IMU_MOSI", "37": "IMU_MISO", "36": "IMU_INT1",
               "50": "FL_MISO", "49": "FLASH_CS", "48": "FL_SCK", "47": "FL_MOSI",
               "46": "LED", "45": "CHG_STAT",
               "17": NC, "18": NC,              # no 32 kHz crystal: internal RC + phone time sync
               "20": "VBAT_SENSE",                  # P0.04 = AIN2 (bottom row)
               "26": "MOTOR_EN",                    # P1.09 (bottom row)
               "51": "SWDIO", "53": "SWDCLK"}),
    dict(ref="C1", sym=("Device", "C"), value="10uF", fp=C0402,
         lcsc="C15525", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C2", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C3", sym=("Device", "C"), value="4.7uF", fp=C0402,
         lcsc="C23733", pins={"1": "VCHG", "2": "GND"}),   # nRF VBUS decoupling


    # ---- IMU: BMI270 on SPI (BMI160 symbol: same LGA-14 pinout) ----------
    dict(ref="U2", sym=("Sensor_Motion", "BMI160"), value="BMI270",
         fp="Package_LGA:Bosch_LGA-14_3x2.5mm_P0.5mm", lcsc="C2836813",
         pins={"1": "IMU_MISO",    # SDO
               "2": NC, "3": NC,   # ASDx/ASCx: aux I2C unused, may float
               "4": "IMU_INT1", "5": "+3V3", "6": "GND", "7": "GND", "8": "+3V3",
               "9": "IMU_INT2", "10": NC, "11": NC,   # OCSB/OSDO: aux SPI, open
               "12": "IMU_CS", "13": "IMU_SCK", "14": "IMU_MOSI"}),
    dict(ref="C4", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),   # VDD
    dict(ref="C5", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),   # VDDIO

    # ---- 2 MB flash for recordings, on its own SPI bus ----------------------
    dict(ref="U7", sym=("Memory_Flash", "W25Q32JVSS"), value="W25Q16JVSNIQ",
         fp="Package_SO:SOIC-8_3.9x4.9mm_P1.27mm", lcsc="C2456211",
         pins={"1": "FLASH_CS", "2": "FL_MISO", "3": "+3V3", "4": "GND",
               "5": "FL_MOSI", "6": "FL_SCK", "7": "+3V3", "8": "+3V3"}),
    dict(ref="C14", sym=("Device", "C"), value="100nF", fp=C0402,
         lcsc="C1525", pins={"1": "+3V3", "2": "GND"}),

    # ---- Charging input: Fitbit Air charger, either polarity ---------------
    # Pads on the underside (B.Cu). Full bridge of 1N5819-class Schottkys:
    # ~0.25 V each at 20 mA, so a 5 V charger gives ~4.5 V at the MCP73831,
    # enough headroom to finish the cell at 4.2 V. (A BAT54S pair drops
    # ~0.8 V, which would leave the charger in dropout around 4.0 V.)
    dict(ref="J1", sym=("Connector_Generic", "Conn_01x02"), value="CHARGER",
         fp="lift:Charger_Contacts_FitbitAir_P18mm", lcsc=None, dnp=True,
         pins={"1": "CH_A", "2": "CH_B"}),
    dict(ref="D6", sym=("Device", "D_Schottky"), value="B5819WS",
         fp="Diode_SMD:D_SOD-323", lcsc="C22624",
         pins={"1": "VCHG", "2": "CH_A"}),                    # K, A: pad A -> VCHG
    dict(ref="D7", sym=("Device", "D_Schottky"), value="B5819WS",
         fp="Diode_SMD:D_SOD-323", lcsc="C22624",
         pins={"1": "CH_A", "2": "GND"}),                     # GND -> pad A
    dict(ref="D8", sym=("Device", "D_Schottky"), value="B5819WS",
         fp="Diode_SMD:D_SOD-323", lcsc="C22624",
         pins={"1": "VCHG", "2": "CH_B"}),                    # pad B -> VCHG
    dict(ref="D9", sym=("Device", "D_Schottky"), value="B5819WS",
         fp="Diode_SMD:D_SOD-323", lcsc="C22624",
         pins={"1": "CH_B", "2": "GND"}),                     # GND -> pad B
    dict(ref="D5", sym=("Device", "D_Zener"), value="ESD5Z5.0T1G",
         fp="Diode_SMD:D_SOD-523", lcsc="C82044",
         pins={"1": "VCHG", "2": "GND"}),                    # unidirectional TVS: K, A
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

    # ---- Battery: cell wires solder to 2 top pads (cell sits on top) ----------------------
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

    # ---- SWD: 3 test pads on the underside (cover with kapton; reflash anytime) -----
    dict(ref="J3", sym=("Connector_Generic", "Conn_01x03"), value="SWD",
         fp="lift:SWD_Pads_1x03_P1.5mm", lcsc=None, dnp=True,
         pins={"1": "SWDIO", "2": "SWDCLK", "3": "GND"}),

    # ---- Power flags (tell ERC these nets are driven) ----------------------
    dict(ref="#FLG01", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "GND"}, virtual=True),
    # VCHG is driven by the charger through the bridge D6/D7
    dict(ref="#FLG03", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "VCHG"}, virtual=True),
]
