"""Swap U102 (BMI270 -> LSM6DSV16X) on the routed board, keep all routing,
rip up SPI_MISO and IMU_INT1 so they can be re-routed together with the new
GND ties on pins 2/3. usage: swap_imu.py in.kicad_pcb out.kicad_pcb"""
import sys, pcbnew, sexpdata
IN, OUT = sys.argv[1], sys.argv[2]
b = pcbnew.LoadBoard(IN)
old = b.FindFootprintByReference("U102")
new = pcbnew.FootprintLoad("/usr/share/kicad/footprints/Package_LGA.pretty", "LGA-14_3x2.5mm_P0.5mm_LayoutBorder3x4y")
new.SetFPID(pcbnew.LIB_ID("Package_LGA", "LGA-14_3x2.5mm_P0.5mm_LayoutBorder3x4y"))
new.SetPosition(old.GetPosition())
new.SetOrientationDegrees(old.GetOrientationDegrees())
new.SetReference("U102")
new.SetValue("LSM6DSV16X")
new.SetPath(old.GetPath())
for f in old.GetFields():
    if f.GetName() not in ("Reference", "Value", "Footprint"):
        nf = pcbnew.PCB_FIELD(new, new.GetNextFieldId(), f.GetName()) if hasattr(new, "GetNextFieldId") else None
        if nf is not None:
            nf.SetText("C5267406" if f.GetName() == "LCSC" else f.GetText())
            nf.SetVisible(False)
            nf.SetLayer(pcbnew.F_Fab)
            new.AddField(nf)
# reference text: keep the old one's position/visibility
new.Reference().SetPosition(old.Reference().GetPosition())
new.Reference().SetVisible(old.Reference().IsVisible())
new.Value().SetVisible(old.Value().IsVisible())
# nets from the new netlist
net = sexpdata.loads(open("gympod.net").read())
def val(node, key):
    for c in node[1:]:
        if isinstance(c, list) and c and str(c[0]) == key:
            return str(c[1])
pad_net = {}
for top in net[1:]:
    if isinstance(top, list) and str(top[0]) == "nets":
        for n in top[1:]:
            name = val(n, "name")
            for nd in n[1:]:
                if isinstance(nd, list) and str(nd[0]) == "node" and val(nd, "ref") == "U102":
                    pad_net[val(nd, "pin")] = name
nets = b.GetNetsByName()
for p in new.Pads():
    name = pad_net.get(p.GetNumber())
    if name is None:
        continue
    if not nets.has_key(name):
        ni = pcbnew.NETINFO_ITEM(b, name)
        b.Add(ni)
        nets = b.GetNetsByName()
    p.SetNet(nets[name])
b.Remove(old)
b.Add(new)
# rip up the IMU's left-side nets
rip = {"/SPI_MISO", "/IMU_INT1"}
gone = [t for t in b.GetTracks() if t.GetNetname() in rip]
for t in gone:
    b.Remove(t)
print("pads:", {p.GetNumber(): p.GetNetname() for p in new.Pads()})
print("ripped up", len(gone), "tracks/vias")
b.Save(OUT)
