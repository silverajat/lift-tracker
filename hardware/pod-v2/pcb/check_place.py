"""Courtyard overlaps + parts outside the board / in the antenna keep-out."""
import sys, itertools, pcbnew, os
b = pcbnew.LoadBoard(sys.argv[1])
OX, OY = 100, 100
from placement import BOARD_W, BOARD_H
boxes = {}
for fp in b.GetFootprints():
    side = pcbnew.B_CrtYd if fp.IsFlipped() else pcbnew.F_CrtYd
    bb = fp.GetCourtyard(side).BBox()
    if bb.GetWidth() == 0:
        bb = fp.GetBoundingBox(False, False)
    boxes[fp.GetReference()] = (fp.IsFlipped(), bb.GetX()/1e6-OX, bb.GetY()/1e6-OY, bb.GetRight()/1e6-OX, bb.GetBottom()/1e6-OY)
bad = 0
for (a, A), (c, Cc) in itertools.combinations(boxes.items(), 2):
    if A[0] != Cc[0]: continue
    if a == "U101" or c == "U101":
        pass
    ox = min(A[3], Cc[3]) - max(A[1], Cc[1]); oy = min(A[4], Cc[4]) - max(A[2], Cc[2])
    if ox > 0.01 and oy > 0.01:
        print(f"overlap {a} {c} {ox:.2f}x{oy:.2f}"); bad += 1
for r, (fl, x0, y0, x1, y1) in boxes.items():
    if x0 < -0.01 or y0 < -0.01 or x1 > BOARD_W + 0.01 or y1 > BOARD_H + 0.01:
        print(f"outside {r} {x0:.2f},{y0:.2f}-{x1:.2f},{y1:.2f}"); bad += 1
    if r != "U101" and x0 < 4.5:
        print(f"near antenna {r} x0={x0:.2f}"); bad += 1
print("problems:", bad)
os._exit(0)
