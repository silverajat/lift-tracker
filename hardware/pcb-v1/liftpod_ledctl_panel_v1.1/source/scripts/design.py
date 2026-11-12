"""
Gym pod v1 dev board: the whole circuit as data.

Each part: ref, (library, symbol), value, footprint, LCSC number, and a map
of pin number -> net name. gen_schematic.py turns this into a KiCad
schematic; nets connect by label, so every connection is listed right here.

nRF52840 pin choices (Raytac MDBT50Q-1MV2):
  P0.13 IMU_SCK   P0.14 IMU_MOSI   P0.15 IMU_MISO   P0.16 IMU_CS
  P0.17 IMU_INT1  P0.20 IMU_INT2   P0.02/AIN0 VBAT_SENSE
  P0.29 MOTOR_EN  P0.28 BUTTON     P0.30 LED_USER   P0.18 nRESET
Avoided: P0.09/P0.10 (NFC by default), P0.00/P0.01 (32 kHz crystal pins).
"""

NC = None  # pin left unconnected (gets a no-connect flag)

PARTS = [
    # ---- nRF52840 module ------------------------------------------------
    dict(ref="U1", sym=("RF_Module", "MDBT50Q-1MV2"), value="MDBT50Q-1MV2",
         fp="RF_Module:Raytac_MDBT50Q", lcsc="C5118826", unused_nc=True,
         pins={"1": "GND", "2": "GND", "15": "GND", "33": "GND", "55": "GND",
               "28": "+3V3", "30": "+3V3",          # VDD, VDDH tied: normal-voltage mode
               "31": NC,                            # DCCH unused
               "32": "VBUS", "34": "USB_DM", "35": "USB_DP",
               "37": "IMU_SCK", "36": "IMU_MOSI", "39": "IMU_MISO", "38": "IMU_CS",
               "41": "IMU_INT1", "44": "IMU_INT2",
               "11": "VBAT_SENSE", "10": "MOTOR_EN", "13": "BUTTON", "14": "LED_USER",
               "40": "nRESET", "51": "SWDIO", "53": "SWDCLK"}),
    dict(ref="C1", sym=("Device", "C"), value="10uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19702", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C2", sym=("Device", "C"), value="100nF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C14663", pins={"1": "+3V3", "2": "GND"}),
    dict(ref="C3", sym=("Device", "C"), value="4.7uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19666", pins={"1": "VBUS", "2": "GND"}),   # nRF VBUS decoupling

    # ---- IMU: BMI270 on SPI (BMI160 symbol: same LGA-14 pinout) ----------
    dict(ref="U2", sym=("Sensor_Motion", "BMI160"), value="BMI270",
         fp="Package_LGA:Bosch_LGA-14_3x2.5mm_P0.5mm", lcsc="C2836813",
         pins={"1": "IMU_MISO",    # SDO
               "2": NC,            # ASDx: aux I2C unused; datasheet allows floating
               "3": NC,            # ASCx: aux I2C unused; datasheet allows floating
               "4": "IMU_INT1", "5": "+3V3", "6": "GND", "7": "GND", "8": "+3V3",
               "9": "IMU_INT2", "10": NC, "11": NC,   # OCSB/OSDO: aux SPI, leave open
               "12": "IMU_CS", "13": "IMU_SCK", "14": "IMU_MOSI"}),
    dict(ref="C4", sym=("Device", "C"), value="100nF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C14663", pins={"1": "+3V3", "2": "GND"}),   # VDD
    dict(ref="C5", sym=("Device", "C"), value="100nF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C14663", pins={"1": "+3V3", "2": "GND"}),   # VDDIO

    # ---- USB-C (charging + data) ----------------------------------------
    dict(ref="J1", sym=("Connector", "USB_C_Receptacle_USB2.0_16P"), value="USB-C",
         fp="Connector_USB:USB_C_Receptacle_HRO_TYPE-C-31-M-12", lcsc="C165948",
         pins={"A1": "GND", "A12": "GND", "B1": "GND", "B12": "GND", "S1": "GND",
               "A4": "VBUS", "A9": "VBUS", "B4": "VBUS", "B9": "VBUS",
               "A5": "CC1", "B5": "CC2",
               "A6": "USB_CONN_DP", "B6": "USB_CONN_DP", "A7": "USB_CONN_DM", "B7": "USB_CONN_DM",
               "A8": NC, "B8": NC}),
    dict(ref="R1", sym=("Device", "R"), value="5.1k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C23186", pins={"1": "CC1", "2": "GND"}),
    dict(ref="R2", sym=("Device", "R"), value="5.1k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C23186", pins={"1": "CC2", "2": "GND"}),
    # ESD protection on the USB data lines and VBUS
    dict(ref="U5", sym=("Power_Protection", "USBLC6-2SC6"), value="USBLC6-2SC6",
         fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C7519",
         pins={"1": "USB_CONN_DM", "6": "USB_DM", "3": "USB_CONN_DP", "4": "USB_DP",
               "2": "GND", "5": "VBUS"}),

    # ---- Charger: MCP73831-2 (4.2 V), 15k PROG = ~67 mA (0.5C for 140 mAh)
    dict(ref="U3", sym=("Battery_Management", "MCP73831-2-OT"), value="MCP73831-2-OT",
         fp="Package_TO_SOT_SMD:SOT-23-5", lcsc="C424093",
         pins={"1": "CHG_STAT", "2": "GND", "3": "VBAT", "4": "VBUS", "5": "CHG_PROG"}),
    dict(ref="R3", sym=("Device", "R"), value="15k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C22809", pins={"1": "CHG_PROG", "2": "GND"}),
    dict(ref="C6", sym=("Device", "C"), value="4.7uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19666", pins={"1": "VBUS", "2": "GND"}),
    dict(ref="C7", sym=("Device", "C"), value="4.7uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19666", pins={"1": "VBAT", "2": "GND"}),
    # charge LED: on while charging (STAT pulls low)
    dict(ref="R4", sym=("Device", "R"), value="1k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C21190", pins={"1": "VBUS", "2": "LED_CHG_A"}),
    dict(ref="D3", sym=("Device", "LED"), value="RED", fp="LED_SMD:LED_0603_1608Metric",
         lcsc="C2286", pins={"2": "LED_CHG_A", "1": "CHG_STAT"}),

    # ---- Battery ----------------------------------------------------------
    # Keyed JST-SH 1.0 mm socket: the cell plugs in, can't go in backwards.
    # Pin 1 = cell +, pin 2 = cell - (BATT_N, before the protection FETs).
    dict(ref="J2", sym=("Connector_Generic", "Conn_01x02"), value="JST-SH BATT",
         fp="Connector_JST:JST_SH_SM02B-SRSS-TB_1x02-1MP_P1.00mm_Horizontal", lcsc="C160402",
         pins={"1": "VBAT", "2": "BATT_N"}),
    # Battery protection (DW01A + FS8205A), the same circuit as inside a
    # protected cell, so the board is safe with any cell: cuts off at
    # 4.3 V (overcharge), 2.5 V (over-discharge), ~3 A (overcurrent/short).
    # The FETs sit in the cell's negative lead: cell - = BATT_N, board GND = pack -.
    dict(ref="U6", sym=("Battery_Management", "DW01A"), value="DW01A",
         fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C351410",
         pins={"1": "PROT_OD", "2": "PROT_VM", "3": "PROT_OC", "4": NC,
               "5": "PROT_VCC", "6": "BATT_N"}),
    dict(ref="Q3", sym=("gympod", "FS8205A"), value="FS8205A",
         fp="Package_TO_SOT_SMD:SOT-23-6", lcsc="C2830320",
         pins={"1": "BATT_N", "2": "PROT_D", "3": "GND", "4": "PROT_OC",
               "5": "PROT_D", "6": "PROT_OD"}),
    dict(ref="R11", sym=("Device", "R"), value="100", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C22775", pins={"1": "VBAT", "2": "PROT_VCC"}),
    dict(ref="C11", sym=("Device", "C"), value="100nF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C14663", pins={"1": "PROT_VCC", "2": "BATT_N"}),
    dict(ref="R12", sym=("Device", "R"), value="1k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C21190", pins={"1": "PROT_VM", "2": "GND"}),
    # battery voltage sense: 1M/1M divider -> AIN0, 100 nF for the ADC sample
    dict(ref="R5", sym=("Device", "R"), value="1M", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C22935", pins={"1": "VBAT", "2": "VBAT_SENSE"}),
    dict(ref="R6", sym=("Device", "R"), value="1M", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C22935", pins={"1": "VBAT_SENSE", "2": "GND"}),
    dict(ref="C8", sym=("Device", "C"), value="100nF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C14663", pins={"1": "VBAT_SENSE", "2": "GND"}),

    # ---- 3.3 V regulator: ME6211C33 LDO from the battery -----------------
    dict(ref="U4", sym=("Regulator_Linear", "ME6211C33M5"), value="ME6211C33M5G",
         fp="Package_TO_SOT_SMD:SOT-23-5", lcsc="C82942",
         pins={"1": "SYS", "3": "SYS", "2": "GND", "4": NC, "5": "+3V3"}),
    dict(ref="C9", sym=("Device", "C"), value="4.7uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19666", pins={"1": "SYS", "2": "GND"}),
    dict(ref="C10", sym=("Device", "C"), value="4.7uF", fp="Capacitor_SMD:C_0603_1608Metric",
         lcsc="C19666", pins={"1": "+3V3", "2": "GND"}),

    # ---- Vibration motor driver (motor runs from VBAT) --------------------
    dict(ref="Q1", sym=("Transistor_FET", "AO3400A"), value="AO3400A",
         fp="Package_TO_SOT_SMD:SOT-23", lcsc="C20917",
         pins={"1": "MOTOR_G", "2": "GND", "3": "MOTOR_N"}),
    dict(ref="R7", sym=("Device", "R"), value="100", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C22775", pins={"1": "MOTOR_EN", "2": "MOTOR_G"}),
    dict(ref="R8", sym=("Device", "R"), value="100k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C25803", pins={"1": "MOTOR_G", "2": "GND"}),   # keeps the motor off at boot
    dict(ref="D1", sym=("Device", "D"), value="1N4148WS", fp="Diode_SMD:D_SOD-323",
         lcsc="C2128", pins={"1": "SYS", "2": "MOTOR_N"}),   # flyback
    dict(ref="J4", sym=("Connector_Generic", "Conn_01x02"), value="MOTOR",
         fp="Connector_PinHeader_2.00mm:PinHeader_1x02_P2.00mm_Vertical_SMD_Pin1Left", lcsc=None, dnp=True,
         pins={"1": "SYS", "2": "MOTOR_N"}),

    # ---- Load sharing: USB powers the system when plugged in, so the charger
    # only sees the battery and can terminate. Unplugged: Q2 turns on
    # (gate pulled to GND) and the battery feeds SYS through it.
    dict(ref="Q2", sym=("Transistor_FET", "AO3401A"), value="AO3401A",
         fp="Package_TO_SOT_SMD:SOT-23", lcsc="C15127",
         pins={"1": "VBUS", "2": "SYS", "3": "VBAT"}),      # G, S, D
    dict(ref="D4", sym=("Device", "D_Schottky"), value="B5819W",
         fp="Diode_SMD:D_SOD-123", lcsc="C8598",
         pins={"1": "SYS", "2": "VBUS"}),                   # K, A
    dict(ref="R10", sym=("Device", "R"), value="100k", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C25803", pins={"1": "VBUS", "2": "GND"}),    # Q2 gate pull-down

    # ---- Buttons, LED -----------------------------------------------------
    dict(ref="SW1", sym=("Switch", "SW_Push"), value="USER",
         fp="Button_Switch_SMD:SW_Push_1P1T_XKB_TS-1187A", lcsc="C318884",
         pins={"1": "BUTTON", "2": "GND"}),
    dict(ref="R9", sym=("Device", "R"), value="330", fp="Resistor_SMD:R_0603_1608Metric",
         lcsc="C23138", pins={"1": "LED_USER", "2": "LED_USER_A"}),
    dict(ref="D2", sym=("Device", "LED"), value="GREEN", fp="LED_SMD:LED_0603_1608Metric",
         lcsc="C72043", pins={"2": "LED_USER_A", "1": "GND"}),

    # ---- SWD programming header (first flash with a debug probe) ----------
    dict(ref="J3", sym=("Connector_Generic", "Conn_01x05"), value="SWD",
         fp="Connector_PinHeader_1.27mm:PinHeader_1x05_P1.27mm_Vertical", lcsc=None, dnp=True,
         pins={"1": "+3V3", "2": "SWDIO", "3": "SWDCLK", "4": "nRESET", "5": "GND"}),


    # ---- Power flags (tell ERC these nets are driven) ----------------------
    dict(ref="#FLG01", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "GND"}, virtual=True),
    dict(ref="#FLG02", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "VBUS"}, virtual=True),
    dict(ref="#FLG03", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "SYS"}, virtual=True),
    # the cell (via J2) drives these two
    dict(ref="#FLG04", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "BATT_N"}, virtual=True),
    dict(ref="#FLG05", sym=("power", "PWR_FLAG"), value="PWR_FLAG", fp="", lcsc=None,
         pins={"1": "PROT_VCC"}, virtual=True),
]
