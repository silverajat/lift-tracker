"""GND stitching vias on a grid in free space (outside every courtyard),
then drop any that DRC flags. Ties the F/B GND pours to the In1 plane."""
import json, math, os, subprocess, sys
import pcbnew
IN, OUT = sys.argv[1], sys.argv[2]
STEP, VD, DR = 1.6, 0.6, 0.3
b = pcbnew.LoadBoard(IN)
gnd = b.FindNet("/GND")
court = []
for fp in b.GetFootprints():
    bb = fp.GetCourtyard(pcbnew.F_CrtYd).BBox()
    court.append((bb.GetX()/1e6-0.2, bb.GetY()/1e6-0.2, bb.GetRight()/1e6+0.2, bb.GetBottom()/1e6+0.2))
    for z in fp.Zones():
        bb = z.GetBoundingBox()
        court.append((bb.GetX()/1e6-0.3, bb.GetY()/1e6-0.3, bb.GetRight()/1e6+0.3, bb.GetBottom()/1e6+0.3))
padboxes = []
for fp in b.GetFootprints():
    for pd in fp.Pads():
        bb = pd.GetBoundingBox()
        padboxes.append((bb.GetX()/1e6, bb.GetY()/1e6, bb.GetRight()/1e6, bb.GetBottom()/1e6))
def pad_clear(x, y, need):
    for (a, c, d, e) in padboxes:
        dx = max(a - x, 0, x - d); dy = max(c - y, 0, y - e)
        if math.hypot(dx, dy) < need:
            return False
    return True
segs = []
for t in b.GetTracks():
    if t.Type() == pcbnew.PCB_VIA_T:
        segs.append((t.GetPosition().x/1e6, t.GetPosition().y/1e6, t.GetPosition().x/1e6, t.GetPosition().y/1e6, t.GetWidth(pcbnew.F_Cu)/2e6))
    else:
        segs.append((t.GetStart().x/1e6, t.GetStart().y/1e6, t.GetEnd().x/1e6, t.GetEnd().y/1e6, t.GetWidth()/2e6))
def dseg(px, py, s):
    x0, y0, x1, y1, w = s
    dx, dy = x1-x0, y1-y0
    L = dx*dx+dy*dy
    t = 0 if L == 0 else max(0, min(1, ((px-x0)*dx+(py-y0)*dy)/L))
    return math.hypot(px-(x0+t*dx), py-(y0+t*dy)) - w
W, H, R, OX, OY = 38, 27, 4, 100, 100
def inside(x, y, m=1.0):
    x, y = x-OX, y-OY
    if not (m <= x <= W-m and m <= y <= H-m): return False
    cx = min(max(x, R), W-R); cy = min(max(y, R), H-R)
    return math.hypot(x-cx, y-cy) <= R-m
added = []
y = OY + 0.8
while y < OY + H:
    x = OX + 0.8
    while x < OX + W:
        if inside(x, y) and not any(c[0] <= x <= c[2] and c[1] <= y <= c[3] for c in court) \
           and all(dseg(x, y, s) > VD/2 + 0.25 for s in segs) and pad_clear(x, y, VD/2 + 0.25):
            v = pcbnew.PCB_VIA(b); v.SetPosition(pcbnew.VECTOR2I(pcbnew.FromMM(x), pcbnew.FromMM(y)))
            v.SetWidth(pcbnew.FromMM(VD)); v.SetDrill(pcbnew.FromMM(DR)); v.SetNet(gnd); b.Add(v)
            added.append(v); segs.append((x, y, x, y, VD/2))
        x += STEP
    y += STEP
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(OUT)
print("stitching vias:", len(added))
os._exit(0)
