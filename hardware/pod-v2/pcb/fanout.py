"""Fan out GND and +3V3 SMD pads: a short trace + via straight down to the
import sys; sys.path.insert(0, __import__("os").path.dirname(__import__("os").path.abspath(__file__)))
inner planes (In1 = GND, In2 = +3V3). Tries several directions/distances per
pad and keeps the first spot that clears every other pad, via and keepout."""
import math
import sys

import pcbnew

IN, OUT = sys.argv[1], sys.argv[2]
mm = pcbnew.FromMM
b = pcbnew.LoadBoard(IN)

VIA_D, VIA_DRILL, CLR, TRACK_W = 0.6, 0.3, 0.15, 0.2
FAN_NETS = {"/GND", "/+3V3"}

# obstacles: every pad (as a rotated rect approximated by a circle-ish box)
pads = []
for fp in b.GetFootprints():
    for p in fp.Pads():
        bb = p.GetBoundingBox()
        pads.append((p, bb.GetX() / 1e6, bb.GetY() / 1e6, bb.GetRight() / 1e6, bb.GetBottom() / 1e6))

edge = b.GetBoardEdgesBoundingBox()
EX0, EY0, EX1, EY1 = edge.GetX() / 1e6 + 0.05, edge.GetY() / 1e6 + 0.05, edge.GetRight() / 1e6 - 0.05, edge.GetBottom() / 1e6 - 0.05  # bbox includes the 0.1 mm outline width
OX, OY = EX0, EY0
from placement import CORNER_R as R_CORNER

keepouts = []
for fp in b.GetFootprints():
    for z in fp.Zones():
        if z.GetIsRuleArea() and z.GetDoNotAllowVias():
            bb = z.GetBoundingBox()
            keepouts.append((bb.GetX() / 1e6, bb.GetY() / 1e6, bb.GetRight() / 1e6, bb.GetBottom() / 1e6))

for z in b.Zones():
    if z.GetIsRuleArea() and z.GetDoNotAllowVias():
        bb = z.GetBoundingBox()
        keepouts.append((bb.GetX() / 1e6, bb.GetY() / 1e6, bb.GetRight() / 1e6, bb.GetBottom() / 1e6))

vias = []


def same(a, b):
    return a.GetParentFootprint().GetReference() == b.GetParentFootprint().GetReference() and a.GetNumber() == b.GetNumber()


def inside_board(x, y, margin):
    if not (EX0 + margin <= x <= EX1 - margin and EY0 + margin <= y <= EY1 - margin):
        return False
    for cx, cy in [(EX0 + R_CORNER, EY0 + R_CORNER), (EX1 - R_CORNER, EY0 + R_CORNER),
                   (EX0 + R_CORNER, EY1 - R_CORNER), (EX1 - R_CORNER, EY1 - R_CORNER)]:
        if (x < EX0 + R_CORNER and x < cx or x > EX1 - R_CORNER and x > cx) and \
           (y < EY0 + R_CORNER and y < cy or y > EY1 - R_CORNER and y > cy):
            if math.hypot(x - cx, y - cy) > R_CORNER - margin:
                return False
    return True


def rect_dist(x, y, r):
    dx = max(r[0] - x, 0, x - r[2])
    dy = max(r[1] - y, 0, y - r[3])
    return math.hypot(dx, dy)


def seg_clear(x0, y0, x1, y1, own_pad, netname, layer):
    """Track from pad to via must not pass near other-net pads on its layer."""
    for i in range(1, 9):
        t = i / 9
        x, y = x0 + (x1 - x0) * t, y0 + (y1 - y0) * t
        for p, *r in pads:
            if same(p, own_pad) or p.GetNetname() == netname or not p.IsOnLayer(layer):
                continue
            if rect_dist(x, y, r) < TRACK_W / 2 + CLR:
                return False
    return True


def spot_ok(x, y, own_pad, netname):
    if not inside_board(x, y, VIA_D / 2 + 0.32):
        return False
    for k in keepouts:
        if rect_dist(x, y, k) < VIA_D / 2 + 0.1:
            return False
    for p, *r in pads:
        if same(p, own_pad):
            continue
        need = VIA_D / 2 + (0.15 if p.GetNetname() == netname else CLR)
        if rect_dist(x, y, r) < need:
            return False
    for (vx, vy, vn) in vias:
        if math.hypot(x - vx, y - vy) < VIA_D + (0.1 if vn == netname else CLR):
            return False
    return True


added = 0
failed = []
for fp in b.GetFootprints():
    fc = fp.GetPosition()
    for p in fp.Pads():
        net = p.GetNetname()
        if net not in FAN_NETS or p.GetAttribute() == pcbnew.PAD_ATTRIB_PTH:
            continue
        bottom = not p.IsOnLayer(pcbnew.F_Cu)   # wire/test pads on the bottom
        px, py = p.GetPosition().x / 1e6, p.GetPosition().y / 1e6
        ang0 = math.atan2(py - fc.y / 1e6, px - fc.x / 1e6)
        if abs(px - fc.x / 1e6) < 1e-6 and abs(py - fc.y / 1e6) < 1e-6:
            ang0 = -math.pi / 2
        best = None
        for dist in (0.75, 0.95, 1.15, 1.4, 1.7, 2.0, 2.4, 2.8, 3.2, 3.6, 3.9, 4.2):
            for k in range(0, 24):
                a = ang0 + (k // 2) * (math.pi / 12) * (1 if k % 2 == 0 else -1)
                x, y = px + dist * math.cos(a), py + dist * math.sin(a)
                if spot_ok(x, y, p, net) and seg_clear(px, py, x, y, p, net, pcbnew.B_Cu if bottom else pcbnew.F_Cu):
                    best = (x, y)
                    break
            if best:
                break
        if not best:
            failed.append(f"{fp.GetReference()}.{p.GetNumber()}")
            continue
        x, y = best
        v = pcbnew.PCB_VIA(b)
        v.SetPosition(pcbnew.VECTOR2I(mm(x), mm(y)))
        v.SetWidth(mm(VIA_D))
        v.SetDrill(mm(VIA_DRILL))
        v.SetNet(p.GetNet())
        b.Add(v)
        t = pcbnew.PCB_TRACK(b)
        t.SetStart(p.GetPosition())
        t.SetEnd(v.GetPosition())
        t.SetWidth(mm(TRACK_W))
        t.SetLayer(pcbnew.B_Cu if bottom else pcbnew.F_Cu)
        t.SetNet(p.GetNet())
        b.Add(t)
        vias.append((x, y, net))
        added += 1

b.Save(OUT)
print("fanout vias:", added, "failed:", failed)
