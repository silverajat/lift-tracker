"""Bottom-side silkscreen labels for the hand-soldered wires + board name.
Bottom silk reads correctly when the board is flipped (mirrored text)."""
import os, sys
import pcbnew
IN = sys.argv[1]
b = pcbnew.LoadBoard(IN)
fps = {f.GetReference(): f for f in b.GetFootprints()}
def text(s, x, y, size=0.8, layer=pcbnew.B_SilkS, rot=0):
    t = pcbnew.PCB_TEXT(b)
    t.SetText(s); t.SetLayer(layer)
    t.SetTextSize(pcbnew.VECTOR2I(pcbnew.FromMM(size), pcbnew.FromMM(size)))
    t.SetTextThickness(pcbnew.FromMM(0.15))
    t.SetPosition(pcbnew.VECTOR2I(pcbnew.FromMM(x), pcbnew.FromMM(y)))
    t.SetTextAngleDegrees(rot)
    if layer == pcbnew.B_SilkS: t.SetMirrored(True)
    b.Add(t)
def pad(ref, n):
    p = fps[ref].FindPadByNumber(n).GetPosition()
    return p.x / 1e6, p.y / 1e6
# battery socket J102 (top, keyed JST-SH): note the cell lead order on the bottom
bx = (pad("J102","1")[0] + pad("J102","2")[0]) / 2
text("cell plugs into J102 (JST-SH): pin 1 = +, pin 2 = -", 119, 112, 0.8)
# motor wire pads J104 (bottom side): + / - beside each pad, MOTOR below
mx = (pad("J104","1")[0] + pad("J104","2")[0]) / 2
for n, lab in (("1", "+"), ("2", "-")):
    x, y = pad("J104", n); text(lab, x + (2.0 if x > mx else -2.0), y, 1.0)
text("MOTOR", mx, max(pad("J104","1")[1], pad("J104","2")[1]) + 1.6, 0.8)
# SWD header pin names, written below each hole
for n, lab in (("1", "3V3"), ("2", "DIO"), ("3", "CLK"), ("4", "RST"), ("5", "GND")):
    x, y = pad("J103", n); text(lab, x, y + 1.7, 0.8, rot=90)
text("LIFT POD v1.1  2026", 119, 108, 1.2)
text("battery side", 119, 110, 0.8)
# ---- top-side reference designators: find a free spot or hide ----------
import math
pads = []
for f in b.GetFootprints():
    for p in f.Pads():
        bb = p.GetBoundingBox()
        pads.append((bb.GetX()/1e6-0.15, bb.GetY()/1e6-0.15, bb.GetRight()/1e6+0.15, bb.GetBottom()/1e6+0.15))
holes = [(f.GetPosition().x/1e6, f.GetPosition().y/1e6) for f in b.GetFootprints()]
placed = []
def ov(a, c):
    return not (a[2] <= c[0] or c[2] <= a[0] or a[3] <= c[1] or c[3] <= a[1])
def inside(r, m=0.4):
    for (x, y) in ((r[0], r[1]), (r[2], r[1]), (r[0], r[3]), (r[2], r[3])):
        lx, ly = x-100, y-100
        if not (m <= lx <= 38-m and m <= ly <= 27-m): return False
        cx = min(max(lx, 4), 34); cy = min(max(ly, 4), 23)
        if math.hypot(lx-cx, ly-cy) > 4-m: return False
    return True
SZ = 0.8
order = sorted(b.GetFootprints(), key=lambda f: -f.GetCourtyard(pcbnew.F_CrtYd).BBox().GetArea())
shown = hidden = 0
for f in order:
    t = f.Reference(); ref = f.GetReference()
    t.SetTextSize(pcbnew.VECTOR2I(pcbnew.FromMM(SZ), pcbnew.FromMM(SZ)))
    t.SetTextThickness(pcbnew.FromMM(0.15))
    cb = f.GetCourtyard(pcbnew.F_CrtYd).BBox()
    X0, Y0, X1, Y1 = cb.GetX()/1e6, cb.GetY()/1e6, cb.GetRight()/1e6, cb.GetBottom()/1e6
    cx, cy = (X0+X1)/2, (Y0+Y1)/2
    w = len(ref) * SZ * 0.72 + 0.2
    h = SZ + 0.25
    best = None
    cands = []
    for rot in (0, 90):
        tw, th = (w, h) if rot == 0 else (h, w)
        for (px, py) in ((cx, Y0 - th/2), (cx, Y1 + th/2), (X0 - tw/2, cy), (X1 + tw/2, cy),
                         (X0 + tw/2, Y0 - th/2), (X1 - tw/2, Y0 - th/2), (X0 + tw/2, Y1 + th/2), (X1 - tw/2, Y1 + th/2),
                         (cx, cy)):
            cands.append((rot, px, py, tw, th))
    for rot, px, py, tw, th in cands:
        r = (px - tw/2, py - th/2, px + tw/2, py + th/2)
        if not inside(r): continue
        if any(ov(r, p) for p in pads): continue
        if any(ov(r, q) for q in placed): continue
        best = (rot, px, py, r); break
    if best:
        rot, px, py, r = best
        t.SetVisible(True)
        t.SetTextAngleDegrees(rot)        # absolute angle on the board
        t.SetPosition(pcbnew.VECTOR2I(pcbnew.FromMM(px), pcbnew.FromMM(py)))
        placed.append(r); shown += 1
    else:
        t.SetVisible(False); hidden += 1
print("refs shown", shown, "hidden", hidden)
b.Save(IN)
print("silk labels added")
os._exit(0)
