"""Silkscreen finishing on the routed board (no copper changes):
hide reference designators (the parts go on by pick-and-place, and the top
is under the cell anyway), mark cell/motor polarity, label the underside."""
import os, sys
import pcbnew
IN, OUT = sys.argv[1], sys.argv[2]
OX, OY = 100.0, 100.0
mm = pcbnew.FromMM
b = pcbnew.LoadBoard(IN)


def text(x, y, s, layer, size=0.8, mirror=False):
    t = pcbnew.PCB_TEXT(b)
    t.SetText(s)
    t.SetPosition(pcbnew.VECTOR2I(mm(OX + x), mm(OY + y)))
    t.SetLayer(layer)
    t.SetTextSize(pcbnew.VECTOR2I(mm(size), mm(size)))
    t.SetTextThickness(mm(0.15))
    t.SetMirrored(mirror)
    b.Add(t)


for fp in b.GetFootprints():
    fp.Reference().SetVisible(False)
    if fp.GetReference() == "J103":          # SWD labels: one row, above the pads
        for it in fp.GraphicalItems():
            if isinstance(it, pcbnew.PCB_TEXT):
                it.SetTextSize(pcbnew.VECTOR2I(mm(0.8), mm(0.8)))
                it.SetTextThickness(mm(0.15))


def pad_xy(ref, num):
    for fp in b.GetFootprints():
        if fp.GetReference() == ref:
            for p in fp.Pads():
                if p.GetNumber() == num:
                    q = p.GetPosition()
                    return q.x / 1e6 - OX, q.y / 1e6 - OY


# cell polarity marks (top). The coin ERM motor works either way round.
x, y = pad_xy("J102", "1"); text(x - 2.0, y, "+", pcbnew.F_SilkS, 1.0)
x, y = pad_xy("J102", "2"); text(x - 2.0, y, "-", pcbnew.F_SilkS, 1.0)
# underside: a small label between the charger contacts and the board edge
text(18.5, 15.6, "LIFT POD v2", pcbnew.B_SilkS, 1.0, mirror=True)
text(18.5, 16.9, "silverajat 2026", pcbnew.B_SilkS, 0.8, mirror=True)
# JLC's order number goes here (top, hidden under the cell) instead of the underside
text(26.2, 9.0, "JLCJLCJLCJLC", pcbnew.F_SilkS, 0.8)
b.Save(OUT)
print("done")
os._exit(0)
