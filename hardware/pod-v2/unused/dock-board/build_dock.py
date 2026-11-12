"""Lift pod v2 charging dock board: USB-C (power only) -> 2 spring pins.
32 x 25.4 mm, 2 layers, 1.0 mm. The USB-C sits at the board's top edge, which is
the dock's side (beside the pod, where the band never goes).
Board x = pod-frame x + 12, board y = 17.4 - pod-frame y.
Pin and magnet positions mirror the pod's pads (podv2/placement.py J1, case magnets)."""
import os, pcbnew
mm = pcbnew.FromMM
OX, OY = 100.0, 100.0
W, H, R = 32.0, 25.4, 2.0
def P(x, y): return pcbnew.VECTOR2I(mm(OX + x), mm(OY + y))
b = pcbnew.BOARD()
b.SetCopperLayerCount(2)
ds = b.GetDesignSettings()
ds.SetBoardThickness(mm(1.0))
nc = ds.m_NetSettings.GetDefaultNetclass(); nc.SetTrackWidth(mm(0.5)); nc.SetClearance(mm(0.2))
nets = {}
def net(n):
    if n not in nets:
        ni = pcbnew.NETINFO_ITEM(b, n); b.Add(ni); nets[n] = ni
    return nets[n]
def seg(a, c):
    s = pcbnew.PCB_SHAPE(b); s.SetShape(pcbnew.SHAPE_T_SEGMENT); s.SetStart(a); s.SetEnd(c)
    s.SetLayer(pcbnew.Edge_Cuts); s.SetWidth(mm(0.1)); b.Add(s)
def arc(cn, st):
    s = pcbnew.PCB_SHAPE(b); s.SetShape(pcbnew.SHAPE_T_ARC); s.SetCenter(cn); s.SetStart(st)
    s.SetArcAngleAndEnd(pcbnew.EDA_ANGLE(90, pcbnew.DEGREES_T)); s.SetLayer(pcbnew.Edge_Cuts); s.SetWidth(mm(0.1)); b.Add(s)
seg(P(R, 0), P(W-R, 0)); seg(P(W, R), P(W, H-R)); seg(P(W-R, H), P(R, H)); seg(P(0, H-R), P(0, R))
arc(P(W-R, R), P(W-R, 0)); arc(P(W-R, H-R), P(W, H-R)); arc(P(R, H-R), P(R, H)); arc(P(R, R), P(0, R))

def place(lib, name, ref, val, x, y, rot, padnets, lcsc=None, libdir="/usr/share/kicad/footprints"):
    fp = pcbnew.FootprintLoad(f"{libdir}/{lib}.pretty" if lib else libdir, name)
    fp.SetReference(ref); fp.SetValue(val); fp.Value().SetVisible(False)
    b.Add(fp); fp.SetPosition(P(x, y)); fp.SetOrientationDegrees(rot)
    if lcsc: fp.SetField("LCSC", lcsc); fp.GetFieldByName("LCSC").SetVisible(False)
    for p in fp.Pads():
        n = padnets.get(p.GetNumber())
        if n: p.SetNet(net(n))
    return fp

# pod frame -> dock board: pod pads at board-x 22.5 / 28.5 (VIN / GND), pod y 14.2
# dock board x = pod_board_x + 6.0, board y = 13.7 (USB-C end at x = 0)
# pod pads at pod-frame (6.0, -5.7) VIN and (12.0, -5.7) GND; magnets at (2.4, -5.7), (15.1, -5.7)
VIN_X, GND_X, PIN_Y = 6.0 + 12, 12.0 + 12, 17.4 + 5.7
MAG = [(2.4 + 12, PIN_Y), (15.1 + 12, PIN_Y)]
usb = place("Connector_USB", "USB_C_Receptacle_HRO_TYPE-C-31-M-12", "J1", "USB-C", 16.0, 3.7, 180,
            {"A1": "GND", "A12": "GND", "B1": "GND", "B12": "GND", "S1": "GND",
             "A4": "VBUS", "A9": "VBUS", "B4": "VBUS", "B9": "VBUS", "A5": "CC1", "B5": "CC2"}, "C165948")
place("Resistor_SMD", "R_0603_1608Metric", "R1", "5.1k", 23.0, 10.0, 90, {"1": "CC1", "2": "GND"}, "C23186")
place("Resistor_SMD", "R_0603_1608Metric", "R2", "5.1k", 9.0, 10.0, 90, {"1": "CC2", "2": "GND"}, "C23186")
place(None, "Pogo_KH-204065-DZ", "P1", "VIN", VIN_X, PIN_Y, 0, {"1": "VBUS"}, "C530676", "/home/claude/dockv2")
place(None, "Pogo_KH-204065-DZ", "P2", "GND", GND_X, PIN_Y, 0, {"1": "GND"}, "C530676", "/home/claude/dockv2")
fpl = b.GetFootprints()
for f in fpl:
    if f.GetReference() in ("P1", "P2"):
        pass
# magnet holes (NPTH 3.4 mm): the magnets sit in the case and pass through the board
for i, (x, y) in enumerate(MAG):
    fp = pcbnew.FOOTPRINT(b); fp.SetReference(f"H{i+1}"); fp.Reference().SetVisible(False)
    pad = pcbnew.PAD(fp); pad.SetAttribute(pcbnew.PAD_ATTRIB_NPTH); pad.SetShape(pcbnew.PAD_SHAPE_CIRCLE)
    pad.SetSize(pcbnew.VECTOR2I(mm(3.4), mm(3.4))); pad.SetDrillSize(pcbnew.VECTOR2I(mm(3.4), mm(3.4)))
    pad.SetLayerSet(pad.UnplatedHoleMask()); fp.Add(pad); b.Add(fp); fp.SetPosition(P(x, y))
    fp.SetAttributes(pcbnew.FP_EXCLUDE_FROM_BOM | pcbnew.FP_EXCLUDE_FROM_POS_FILES | pcbnew.FP_BOARD_ONLY)
# ---- tracks (hand-routed: 3 nets) ----
def trk(pts, n, w=0.5, layer=pcbnew.F_Cu):
    for (a, c) in zip(pts, pts[1:]):
        t = pcbnew.PCB_TRACK(b); t.SetStart(P(*a)); t.SetEnd(P(*c)); t.SetWidth(mm(w))
        t.SetLayer(layer); t.SetNet(net(n)); b.Add(t)
def via(x, y, n, d=0.7, dr=0.35):
    v = pcbnew.PCB_VIA(b); v.SetPosition(P(x, y)); v.SetWidth(mm(d)); v.SetDrill(mm(dr)); v.SetNet(net(n)); b.Add(v)
# VBUS: both receptacle VBUS pairs -> vias -> bottom layer -> up to the VIN pin
for x in (13.55, 18.45):
    trk([(x, 7.75), (x, 9.3)], "VBUS", 0.5); via(x, 9.3, "VBUS")
trk([(13.55, 9.3), (18.45, 9.3)], "VBUS", 0.6, pcbnew.B_Cu)
trk([(18.45, 9.3), (18.45, 20.9), (18.0, 21.4)], "VBUS", 0.6, pcbnew.B_Cu); via(18.0, 21.4, "VBUS")
trk([(18.0, 21.4), (18.0, 23.1)], "VBUS", 0.6)
# CC1 (A5) -> R1 on the right, CC2 (B5) -> R2 on the left, top layer
trk([(17.25, 7.75), (17.25, 10.6), (17.6, 10.95), (22.6, 10.95), (23.0, 10.83)], "CC1", 0.25)
trk([(14.25, 7.75), (14.25, 10.6), (13.9, 10.95), (9.4, 10.95), (9.0, 10.83)], "CC2", 0.25)
# GND stitching between the two pours
for (x, y) in [(5.0, 6.0), (27.0, 6.0), (5.0, 14.0), (27.0, 14.0), (9.0, 7.6), (23.0, 7.6), (24.0, 20.6), (12.0, 18.0), (29.5, 17.5)]:
    via(x, y, "GND")

# GND pours both sides
for layer in (pcbnew.F_Cu, pcbnew.B_Cu):
    z = pcbnew.ZONE(b); z.SetLayer(layer); z.SetNet(net("GND")); z.SetLocalClearance(mm(0.3))
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL); z.SetMinThickness(mm(0.25))
    ol = z.Outline(); ol.NewOutline()
    for (x, y) in [(0, 0), (W, 0), (W, H), (0, H)]: ol.Append(mm(OX + x), mm(OY + y))
    b.Add(z)
# text
t = pcbnew.PCB_TEXT(b); t.SetText("LIFT POD v2 DOCK  5V"); t.SetLayer(pcbnew.F_SilkS)
t.SetTextSize(pcbnew.VECTOR2I(mm(0.9), mm(0.9))); t.SetTextThickness(mm(0.15)); t.SetPosition(P(16.0, 15.0)); b.Add(t)
b.Save("/home/claude/dockv2/dock.kicad_pcb")
for f in b.GetFootprints():
    print(f.GetReference(), [(p.GetNumber(), round(p.GetPosition().x/1e6-OX,2), round(p.GetPosition().y/1e6-OY,2), p.GetNetname()) for p in f.Pads()][:4])
os._exit(0)
