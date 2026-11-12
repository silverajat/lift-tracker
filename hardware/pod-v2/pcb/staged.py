"""Staged routing with a DRC gate: each stage's nets must be fully connected
(KiCad DRC, not Freerouting's own count) before the next stage starts.
usage: python3 staged.py   (needs fan.kicad_pcb + route.dsn)"""
import json, os, re, subprocess, sys

STAGES = [
    ["+3V3"],
    # charging power first: charger pads -> bridge -> VCHG, and VBAT
    ["VCHG", "CH_A", "CH_B", "VBAT"],
    # then every net that leaves the module's edge pads, planned together
    ["SPI_MOSI", "SPI_SCK", "SPI_MISO", "IMU_CS", "IMU_INT1", "FLASH_CS",
     "VBAT_SENSE", "CHG_STAT", "LED", "MOTOR_EN", "SWDIO", "SWDCLK"],
    ["MOTOR_N", "MOTOR_G", "LED_A", "LED_CHG_A", "CHG_PROG"],
]
TRIES = int(os.environ.get("TRIES", "5"))


def sh(cmd):
    return subprocess.run(cmd, shell=True, capture_output=True, text=True).stdout


def unconnected(board):
    sh(f"cp gympod.kicad_pro {board[:-10]}.kicad_pro")
    sh(f"kicad-cli pcb drc --format json -o _drc.json {board}")
    d = json.load(open("_drc.json"))
    nets = []
    for u in d["unconnected_items"]:
        m = re.search(r"\[/([^\]]+)\]", u["items"][0]["description"])
        nets.append(m.group(1) if m else "?")
    return nets


START = int(os.environ.get("START", "1"))
prev, dsn, done = "fan.kicad_pcb", "route.dsn", []
if START > 1:   # resume on top of a finished stage
    prev, dsn = f"q{START - 1}.kicad_pcb", f"q{START - 1}b.dsn"
    done = [n for st in STAGES[:START - 1] for n in st]
for i, nets in enumerate(STAGES, 1):
    if i < START:
        continue
    name = f"q{i}"
    ok = False
    for t in range(TRIES):
        sh(f"python3 dsn_subset.py {dsn} {name}.dsn {' '.join(nets)}")
        print(sh(f"./route_sub.sh {name} {name}.dsn").strip(), flush=True)
        if not os.path.exists(f"{name}.ses"):
            continue
        sh(f"python3 import_ses.py {prev} {name}.ses {name}.kicad_pcb")
        left = [n for n in unconnected(f"{name}.kicad_pcb") if n in nets + done]
        print(f"  stage {i} try {t + 1}: unconnected {left}", flush=True)
        if not left:
            ok = True
            break
    if not ok:
        print(f"STAGE {i} FAILED"); sys.exit(i)
    done += nets
    sh(f"cp gympod.kicad_pro {name}.kicad_pro")
    sh(f"python3 export_dsn.py {name}.kicad_pcb {name}b.dsn")
    prev, dsn = f"{name}.kicad_pcb", f"{name}b.dsn"
sh(f"cp {prev} routed.kicad_pcb; cp gympod.kicad_pro routed.kicad_pro")
print("STAGED_OK")
