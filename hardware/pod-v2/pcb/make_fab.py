"""JLCPCB BOM + CPL for the pod v2 board (assembled parts only, top side).
CPL positions from the board's bottom-left corner (Y up), KiCad rotation;
check the rotations in JLC's placement preview before ordering.
ROT_FIX: JLC's part model is turned against KiCad's footprint (checked in the preview)."""
import csv, os, sys
from collections import OrderedDict
import pcbnew
PCB, OUTDIR = sys.argv[1], sys.argv[2]
os.makedirs(OUTDIR, exist_ok=True)
b = pcbnew.LoadBoard(PCB)
bb = b.GetBoardEdgesBoundingBox()
x0 = bb.GetX() / 1e6 + 0.05
y1 = bb.GetBottom() / 1e6 - 0.05
ROT_FIX = {"C2836813": 180}   # BMI270 (old IMU): JLC model has pin 1 in the opposite corner
rows, bom, skipped = [], OrderedDict(), []
for fp in b.GetFootprints():
    ref = fp.GetReference()
    if fp.IsDNP() or fp.IsExcludedFromBOM() or fp.IsExcludedFromPosFiles() or fp.IsBoardOnly():
        skipped.append(ref); continue
    lcsc = fp.GetFieldByName("LCSC").GetText() if fp.HasFieldByName("LCSC") else ""
    if not lcsc:
        raise SystemExit(f"{ref} has no LCSC number")
    pos = fp.GetPosition()
    rows.append([ref, f"{pos.x / 1e6 - x0:.4f}mm", f"{y1 - pos.y / 1e6:.4f}mm",
                 "Bottom" if fp.IsFlipped() else "Top", f"{(fp.GetOrientationDegrees() + ROT_FIX.get(lcsc, 0)) % 360:.1f}"])
    bom.setdefault(lcsc, dict(comment=fp.GetValue(), refs=[], fp=fp.GetFPID().GetLibItemName().wx_str()))
    bom[lcsc]["refs"].append(ref)
with open(os.path.join(OUTDIR, "liftpod_v2_CPL_JLCPCB.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["Designator", "Mid X", "Mid Y", "Layer", "Rotation"])
    w.writerows(sorted(rows, key=lambda r: (r[0][0], int(''.join(c for c in r[0] if c.isdigit()) or 0))))
with open(os.path.join(OUTDIR, "liftpod_v2_BOM_JLCPCB.csv"), "w", newline="") as f:
    w = csv.writer(f); w.writerow(["Comment", "Designator", "Footprint", "LCSC Part #"])
    for lcsc, v in bom.items():
        w.writerow([v["comment"], ",".join(sorted(v["refs"])), v["fp"], lcsc])
print("CPL rows:", len(rows), "BOM lines:", len(bom), "not assembled:", sorted(skipped))
os._exit(0)
