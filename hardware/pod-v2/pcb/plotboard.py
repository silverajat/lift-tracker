"""Debug plot: pads/tracks/vias with net labels, region selectable."""
import sys, os, pcbnew
import matplotlib; matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import Rectangle, Circle, Polygon
IN, OUT = sys.argv[1], sys.argv[2]
x0,y0,x1,y1 = [float(v) for v in sys.argv[3:7]] if len(sys.argv)>6 else (100,100,138,124)
hl = set(sys.argv[7].split(",")) if len(sys.argv)>7 else set()
b = pcbnew.LoadBoard(IN)
fig, ax = plt.subplots(figsize=(16, 16*(y1-y0)/(x1-x0)))
colors = {pcbnew.F_Cu:"#d33", pcbnew.B_Cu:"#33d", pcbnew.In1_Cu:"#3a3", pcbnew.In2_Cu:"#c8c"}
for fp in b.GetFootprints():
    for p in fp.Pads():
        poly = p.GetEffectivePolygon(pcbnew.F_Cu if p.IsOnLayer(pcbnew.F_Cu) else pcbnew.B_Cu)
        o = poly.Outline(0)
        pts = [(o.CPoint(i).x/1e6, o.CPoint(i).y/1e6) for i in range(o.PointCount())]
        n = p.GetNetname()
        c = "#f90" if n in hl else ("#bbb" if n not in ("/GND","/+3V3") else ("#8c8" if n=="/GND" else "#c9e"))
        ax.add_patch(Polygon(pts, closed=True, fc=c, ec="k", lw=0.3))
        cx, cy = p.GetPosition().x/1e6, p.GetPosition().y/1e6
        if x0<cx<x1 and y0<cy<y1:
            ax.text(cx, cy, n.replace("/","")[:9], fontsize=5, ha="center", va="center")
    r = fp.GetPosition()
    ax.text(r.x/1e6, r.y/1e6-0.0, fp.GetReference(), fontsize=7, color="b", alpha=.5)
for t in b.GetTracks():
    if t.Type() == pcbnew.PCB_VIA_T:
        ax.add_patch(Circle((t.GetPosition().x/1e6, t.GetPosition().y/1e6), t.GetWidth(pcbnew.F_Cu)/2e6, fc="#555", alpha=.7))
    else:
        lw = t.GetWidth()/1e6 * 72 * 16/(x1-x0)  # approx points
        ax.plot([t.GetStart().x/1e6, t.GetEnd().x/1e6], [t.GetStart().y/1e6, t.GetEnd().y/1e6],
                color=colors.get(t.GetLayer(), "k"), lw=lw, alpha=.6, solid_capstyle="round")
ax.set_xlim(x0, x1); ax.set_ylim(y1, y0); ax.set_aspect("equal")
ax.set_xticks(range(int(x0), int(x1)+1)); ax.set_yticks(range(int(y0), int(y1)+1)); ax.grid(alpha=.3)
plt.savefig(OUT, dpi=110, bbox_inches="tight")
os._exit(0)
