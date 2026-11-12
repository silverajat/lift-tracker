"""Fanned-out board -> Specctra DSN for Freerouting.
 - Outer pours are removed (re-poured after import); inner planes become
   power layers.
 - The routing boundary is inset 0.2 mm, because Freerouting keeps only its
   own 0.127 mm from the edge and the board needs 0.3 mm copper-to-edge.
 - The F.Cu no-track area inside the nRF module's pad ring is added by hand
   (KiCad's exporter drops board-level keepouts that block tracks)."""
import math, os, re, sys
import pcbnew
HERE = __import__("os").path.dirname(__import__("os").path.abspath(__file__))
sys.path.insert(0, HERE)
from placement import BOARD_W, BOARD_H, CORNER_R, PLACE  # noqa
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
if os.environ.get("IN2_SIGNAL", "1") == "1":
    # In2 is routable: drop its +3V3 plane from the DSN (re-poured after import)
    s = re.sub(r'\(plane /\+3V3 \(polygon In2\.Cu[^)]*\)\)\s*', '', s)
else:
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
from placement import MODULE_INSIDE
x0, y0, x1, y1 = MODULE_INSIDE
ko = [(x0, y0), (x1, y0), (x1, y1), (x0, y1), (x0, y0)]
s = s.replace('    (via "Via', '    (keepout "" (polygon F.Cu 0  ' + "  ".join(um(*p) for p in ko) + "))\n    (via \"Via", 1)
open(OUT, "w").write(s)
print("dsn written", s.count("(type power)"))
os._exit(0)
