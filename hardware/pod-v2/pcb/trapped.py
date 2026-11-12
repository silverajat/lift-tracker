"""For every connected SMD pad: can a track of its class leave it in some
direction (8 directions, 0.8 mm long) without hitting another net's pad,
the board edge or a keep-out? Prints pads with no exit."""
import math, sys, os, pcbnew
b = pcbnew.LoadBoard(sys.argv[1]); OX = OY = 100
from placement import BOARD_W as W, BOARD_H as H
CLR = 0.127
pads = []
for fp in b.GetFootprints():
    for p in fp.Pads():
        bb = p.GetBoundingBox()
        pads.append((fp.GetReference(), p.GetNumber(), p.GetNetname(), p.IsOnLayer(pcbnew.F_Cu),
                     bb.GetX()/1e6-OX, bb.GetY()/1e6-OY, bb.GetRight()/1e6-OX, bb.GetBottom()/1e6-OY))
keep = []
for fp in b.GetFootprints():
    for z in fp.Zones():
        if z.GetIsRuleArea() and z.IsOnLayer(pcbnew.F_Cu):
            bb = z.GetBoundingBox(); keep.append((bb.GetX()/1e6-OX, bb.GetY()/1e6-OY, bb.GetRight()/1e6-OX, bb.GetBottom()/1e6-OY))
def rd(x, y, r):
    dx = max(r[0]-x, 0, x-r[2]); dy = max(r[1]-y, 0, y-r[3]); return math.hypot(dx, dy)
bad = 0
for (ref, num, net, top, x0, y0, x1, y1) in pads:
    if not net or net.startswith("unconnected") or not top: continue
    w = 0.2 if net in ("/GND", "/+3V3", "/VBAT", "/VCHG") else 0.127
    cx, cy = (x0+x1)/2, (y0+y1)/2
    ok = False
    for k in range(8):
        a = k*math.pi/4; good = True
        for t in [i*0.05 for i in range(1, 17)]:
            px, py = cx + math.cos(a)*t, cy + math.sin(a)*t
            if not (0.3 + w/2 <= px <= W-0.3-w/2 and 0.3 + w/2 <= py <= H-0.3-w/2): good = False; break
            if any(rd(px, py, kk) < w/2 for kk in keep): good = False; break
            for (r2, n2, net2, top2, *bb) in pads:
                if not top2 or (r2 == ref and n2 == num) or net2 == net: continue
                if rd(px, py, bb) < w/2 + CLR: good = False; break
            if not good: break
        if good: ok = True; break
    if not ok:
        print("TRAPPED", ref, num, net, round(cx, 2), round(cy, 2)); bad += 1
print("trapped pads:", bad); os._exit(0)
