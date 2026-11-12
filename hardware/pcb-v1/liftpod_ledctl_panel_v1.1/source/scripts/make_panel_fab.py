"""JLCPCB BOM + CPL for the combined panel.

CPL: every assembled part on the panel, position from the panel's bottom-left
corner (Y up), KiCad rotation (same convention as the LED board's own files).
BOM: grouped by LCSC part number.
"""
import csv
import os
import sys
from collections import OrderedDict

import pcbnew

PANEL, OUTDIR, LED_BOM = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUTDIR, exist_ok=True)
b = pcbnew.LoadBoard(PANEL)
bb = b.GetBoardEdgesBoundingBox()
x0 = bb.GetX() / 1e6 + 0.05          # outline line is 0.1 mm wide
y1 = bb.GetBottom() / 1e6 - 0.05

# LED board BOM comments (keeps the other session's part descriptions)
led_comment = {}
for r in csv.DictReader(open(LED_BOM)):
    for d in r["Designator"].split(","):
        led_comment[d.strip()] = (r["Comment"], r["Footprint"])

rows, bom = [], OrderedDict()
for fp in b.GetFootprints():
    ref = fp.GetReference()
    if fp.IsDNP() or fp.IsExcludedFromBOM() or fp.IsExcludedFromPosFiles():
        continue
    lcsc = fp.GetFieldByName("LCSC").GetText() if fp.HasFieldByName("LCSC") else ""
    if not lcsc:
        continue
    pos = fp.GetPosition()
    side = "Bottom" if fp.IsFlipped() else "Top"
    rows.append([ref, f"{pos.x / 1e6 - x0:.4f}mm", f"{y1 - pos.y / 1e6:.4f}mm", side,
                 f"{fp.GetOrientationDegrees() % 360:.1f}"])
    comment, fpname = led_comment.get(ref, (fp.GetValue(), fp.GetFPID().GetLibItemName().wx_str()))
    key = lcsc
    if key not in bom:
        bom[key] = dict(comment=comment, refs=[], fp=fpname)
    elif bom[key]["comment"] != comment:
        # same LCSC part on both boards with different labels (e.g. the LED
        # board's "RECONFIG" button): describe the part, not one board's use
        bom[key]["comment"] = fp.GetValue() if ref not in led_comment else comment
        if "TS-1187A" in fpname:
            bom[key]["comment"] = "TS-1187A tact switch"
    bom[key]["refs"].append(ref)

with open(os.path.join(OUTDIR, "panel_CPL_JLCPCB.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
    w.writerows(sorted(rows, key=lambda r: r[0]))
with open(os.path.join(OUTDIR, "panel_BOM_JLCPCB.csv"), "w", newline="") as f:
    w = csv.writer(f)
    w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #"])
    for lcsc, v in bom.items():
        w.writerow([v["comment"], ",".join(sorted(v["refs"])), v["fp"], lcsc])
print("CPL rows:", len(rows), "BOM lines:", len(bom))
os._exit(0)
