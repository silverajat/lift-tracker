"""Hand edit after the IMU swap: move the MISO via left so IMU pins 2/3 (GND)
get their own via at (20.75, 13.4). usage: tie_gnd.py in out"""
import sys, pcbnew
b = pcbnew.LoadBoard(sys.argv[1])
mm = pcbnew.FromMM
O = 100.0
def P(x, y): return pcbnew.VECTOR2I(mm(x + O), mm(y + O))
def near(a, x, y, d=0.05):
    v = pcbnew.ToMM(a); return abs(v[0] - O - x) < d and abs(v[1] - O - y) < d
nets = b.GetNetsByName()
miso, gnd = nets["/SPI_MISO"], nets["/GND"]
# remove the MISO via at (21.16,13.56) and its little stubs on both layers
kill = []
for t in b.GetTracks():
    if t.GetNetname() != "/SPI_MISO":
        continue
    pts = [t.GetStart(), t.GetEnd()]
    if any(near(p, 21.16, 13.56, 0.1) or near(p, 21.16, 13.49, 0.1) or near(p, 21.58, 13.14, 0.05) for p in pts):
        kill.append(t)
for t in kill:
    b.Remove(t)
print("removed", len(kill))
def track(a, c, net, layer, w=0.13):
    t = pcbnew.PCB_TRACK(b); t.SetStart(P(*a)); t.SetEnd(P(*c)); t.SetWidth(mm(w))
    t.SetLayer(layer); t.SetNet(net); b.Add(t)
def via(x, y, net):
    v = pcbnew.PCB_VIA(b); v.SetPosition(P(x, y)); v.SetWidth(mm(0.6)); v.SetDrill(mm(0.3))
    v.SetNet(net); b.Add(v)
# MISO: new via left of the IMU at (19.85, 13.3), B.Cu at y 12.99 (between CH_A
# and the new GND via), then back on its old line y 13.14 to the flash.
via(19.85, 13.3, miso)
track((19.85, 13.3), (20.16, 12.99), miso, pcbnew.B_Cu)
track((20.16, 12.99), (21.75, 12.99), miso, pcbnew.B_Cu)
track((21.75, 12.99), (21.9, 13.14), miso, pcbnew.B_Cu)
track((21.9, 13.14), (27.74, 13.14), miso, pcbnew.B_Cu)
# IMU pins 2 and 3 (GND) -> GND via where the MISO via was
via(21.15, 13.55, gnd)
track((21.938, 13.35), (21.15, 13.55), gnd, pcbnew.F_Cu, 0.15)
track((21.938, 13.85), (21.15, 13.55), gnd, pcbnew.F_Cu, 0.15)
# refill every zone so the new vias get their clearances
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(sys.argv[2])
