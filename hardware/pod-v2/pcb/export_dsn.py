"""Fanned-out board -> Specctra DSN for Freerouting.
 - Outer pours are removed (re-poured after import); inner planes become
   power layers.
 - The routing boundary is inset 0.2 mm, because Freerouting keeps only its
   own 0.127 mm from the edge and the board needs 0.3 mm copper-to-edge.
 - The F.Cu no-track area inside the nRF module's pad ring is added by hand
   (KiCad's exporter drops board-level keepouts that block tracks)."""
import math, os, re, sys
import pcbnew
sys.path.insert(0, "/home/claude/podv2")
from placement import BOARD_W, BOARD_H, CORNER_R, PLACE
IN, OUT = sys.argv[1], sys.argv[2]
OX, OY, INSET = 100.0, 100.0, 0.2
b = pcbnew.LoadBoard(IN)
for z in list(b.Zones()):
    if z.GetLayer() in (pcbnew.F_Cu, pcbnew.B_Cu) and not z.GetIsRuleArea():
        b.Remove(z)
pcbnew.ExportSpecctraDSN(b, OUT)
s = open(OUT).read()
if os.environ.get("IN1_POWER", "1") == "1":
    s = re.sub(r'\(layer In1\.Cu\s*\(type signal\)', '(layer In1.Cu (type power)', s)
s = re.sub(r'\(layer In2\.Cu\s*\(type signal\)', '(layer In2.Cu (type power)', s)


def um(x, y):
    return f"{round((OX + x) * 1000)} {-round((OY + y) * 1000)}"


# inset rounded-rectangle boundary
x0, y0, x1, y1, r = INSET, INSET, BOARD_W - INSET, BOARD_H - INSET, CORNER_R - INSET
pts = []
for cx, cy, a0 in [(x1 - r, y0 + r, -90), (x1 - r, y1 - r, 0), (x0 + r, y1 - r, 90), (x0 + r, y0 + r, 180)]:
    for k in range(7):
        a = math.radians(a0 + 15 * k)
        pts.append(um(cx + r * math.cos(a), cy + r * math.sin(a)))
pts.append(pts[0])
s = re.sub(r'\(boundary\s*\(path pcb 0[^)]*\)\s*\)', "(boundary (path pcb 0 " + "  ".join(pts) + "))", s, count=1)

# no tracks on F.Cu inside the module's pad ring
ux, uy = PLACE["U1"][:2]
# (two boxes, leaving out the module's middle row of pads 56-61 at y +0.55,
# which Freerouting would otherwise count as violations)
for ya, yb in ((-3.9, 0.15), (0.95, 5.85)):
    ko = [(ux - 3.3, uy + ya), (ux + 3.3, uy + ya), (ux + 3.3, uy + yb), (ux - 3.3, uy + yb), (ux - 3.3, uy + ya)]
    s = s.replace('    (via "Via', '    (keepout "" (polygon F.Cu 0  ' + "  ".join(um(*p) for p in ko) + "))\n    (via \"Via", 1)
open(OUT, "w").write(s)
print("dsn written", s.count("(type power)"))
os._exit(0)
