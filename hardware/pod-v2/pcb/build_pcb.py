"""Build gympod.kicad_pcb from the schematic's netlist + placement table.

Board: pod v2, rounded rectangle, 4 layers, 0.8 mm.
  F.Cu  signals + parts      In1.Cu  solid GND
  In2.Cu  +3V3 plane         B.Cu    signals + GND pour; this face is the pod's
                                     underside (charger contacts, SWD pads)
Antenna in the top-left corner. Cell, motor and steel disc drawn on Dwgs.User.
"""
import sys
import pcbnew
import sexpdata

sys.path.insert(0, "/home/claude/podv2")
from placement import PLACE, BOARD_W, BOARD_H, CORNER_R, MECH, CELL, MOTOR  # noqa: E402
from design import PARTS  # noqa: E402
DNP = {p["ref"] for p in PARTS if p.get("dnp")}

FPLIB = "/usr/share/kicad/footprints"
OX, OY = 100.0, 100.0       # board origin in KiCad coordinates (mm)
mm = pcbnew.FromMM


def P(x, y):
    return pcbnew.VECTOR2I(mm(OX + x), mm(OY + y))


# ---- read the netlist exported from the schematic ----------------------
S = sexpdata.Symbol
net_s = sexpdata.loads(open("/home/claude/podv2/gympod.net").read())


def kids(node, key):
    return [x for x in node if isinstance(x, list) and x and x[0] == S(key)]


def val(node, key):
    k = kids(node, key)
    return k[0][1] if k else None


comps = {}
for c in kids(kids(net_s, "components")[0], "comp"):
    ref = val(c, "ref")
    fields = {}
    for f in kids(kids(c, "fields")[0], "field") if kids(c, "fields") else []:
        fields[val(f, "name") if kids(f, "name") else f[1][1]] = f[-1] if isinstance(f[-1], str) else ""
    comps[ref] = dict(value=val(c, "value"), fp=val(c, "footprint"),
                      tstamp=val(c, "tstamps"), fields=fields)

pad_net = {}   # (ref, pad) -> net name
for n in kids(kids(net_s, "nets")[0], "net"):
    name = val(n, "name")
    for nd in kids(n, "node"):
        pad_net[(val(nd, "ref"), val(nd, "pin"))] = name

board = pcbnew.BOARD()
board.SetCopperLayerCount(4)
ds = board.GetDesignSettings()
ds.SetBoardThickness(mm(0.8))
ds.m_TrackMinWidth = mm(0.1)
ds.m_MinClearance = mm(0.1)
ds.m_ViasMinSize = mm(0.45)
ds.m_MinThroughDrill = mm(0.25)
ds.m_CopperEdgeClearance = mm(0.3)
nc = ds.m_NetSettings.GetDefaultNetclass()
nc.SetTrackWidth(mm(0.127))   # JLC 4-layer min is 0.09 mm: 0.127 is standard price
nc.SetClearance(mm(0.127))
nc.SetViaDiameter(mm(0.6))
nc.SetViaDrill(mm(0.3))

# Power nets get wider tracks.
power = pcbnew.NETCLASS("Power")
power.SetTrackWidth(mm(0.2))   # fits between 0.5 mm-pitch pads
power.SetClearance(mm(0.127))
power.SetViaDiameter(mm(0.6))
power.SetViaDrill(mm(0.3))
ds.m_NetSettings.SetNetclass("Power", power)
POWER_NETS = ["/GND", "/+3V3", "/VBAT", "/VCHG"]  # other supplies carry < 0.2 A (67 mA charge, ~100 mA motor): 0.2 mm is plenty
for pn in POWER_NETS:
    ds.m_NetSettings.SetNetclassPatternAssignment(pn, "Power")
# v2 TODO: give the battery path (VBAT, BATT_N, PROT_D: 0.4 mm; SYS: 0.3 mm)
# its own net class from the start. Tried on v1.1 (Nov 12): widening in place
# only reached about half the path, and full reroutes with wide tracks kept
# failing around the IMU, so v1.1 ships with 0.2 mm (fine below ~0.5 A; the
# DW01A trips at ~3 A).

nets = {}


def net(name):
    if name not in nets:
        ni = pcbnew.NETINFO_ITEM(board, name)
        board.Add(ni)
        nets[name] = ni
    return nets[name]


# ---- outline: rounded rectangle ----------------------------------------
def edge_line(a, b):
    s = pcbnew.PCB_SHAPE(board)
    s.SetShape(pcbnew.SHAPE_T_SEGMENT)
    s.SetStart(a)
    s.SetEnd(b)
    s.SetLayer(pcbnew.Edge_Cuts)
    s.SetWidth(mm(0.1))
    board.Add(s)


def edge_arc(center, start, angle_deg):
    s = pcbnew.PCB_SHAPE(board)
    s.SetShape(pcbnew.SHAPE_T_ARC)
    s.SetCenter(center)
    s.SetStart(start)
    s.SetArcAngleAndEnd(pcbnew.EDA_ANGLE(angle_deg, pcbnew.DEGREES_T))
    s.SetLayer(pcbnew.Edge_Cuts)
    s.SetWidth(mm(0.1))
    board.Add(s)


W, H, R = BOARD_W, BOARD_H, CORNER_R
edge_line(P(R, 0), P(W - R, 0))
edge_line(P(W, R), P(W, H - R))
edge_line(P(W - R, H), P(R, H))
edge_line(P(0, H - R), P(0, R))
edge_arc(P(W - R, R), P(W - R, 0), 90)
edge_arc(P(W - R, H - R), P(W, H - R), 90)
edge_arc(P(R, H - R), P(R, H), 90)
edge_arc(P(R, R), P(0, R), 90)

# ---- footprints -----------------------------------------------------------
for ref, c in comps.items():
    if ref.startswith("#"):
        continue
    lib, name = c["fp"].split(":")
    libdir = "/home/claude/podv2/lib" if lib == "lift" else FPLIB
    fp = pcbnew.FootprintLoad(f"{libdir}/{lib}.pretty", name)
    if fp is None:
        raise SystemExit(f"footprint not found: {c['fp']}")
    fp.SetReference(ref)
    fp.SetValue(c["value"])
    fp.SetFPID(pcbnew.LIB_ID(lib, name))
    fp.SetPath(pcbnew.KIID_PATH(f"/{c['tstamp']}"))
    for k, v in c["fields"].items():
        if k in ("LCSC",):
            fp.SetField(k, v)
            fp.GetFieldByName(k).SetVisible(False)
    fp.Value().SetVisible(False)
    ref_t = fp.Reference()
    ref_t.SetTextSize(pcbnew.VECTOR2I(mm(0.8), mm(0.8)))
    ref_t.SetTextThickness(mm(0.15))
    import re as _re
    orig = _re.sub(r"(\d+)$", lambda m: str(int(m.group(1)) - 100), ref)
    if orig not in PLACE:
        raise SystemExit(f"no placement for {ref} ({orig})")
    x, y, rot = PLACE[orig][:3]
    board.Add(fp)
    fp.SetPosition(P(x, y))
    fp.SetOrientationDegrees(rot)
    if len(PLACE[orig]) > 3 and PLACE[orig][3] == "B":
        fp.Flip(fp.GetPosition(), pcbnew.FLIP_DIRECTION_TOP_BOTTOM)   # bottom side (DNP wire pads only)
    for pad in fp.Pads():
        num = pad.GetNumber()
        key = (ref, num)
        if key in pad_net:
            pad.SetNet(net(pad_net[key]))
        elif num in ("", "MP"):
            pass                             # mechanical pads
    # DNP parts (pads for wires / dock / SWD) are not assembled
    if orig in DNP:
        fp.SetDNP(True)
        fp.SetExcludedFromBOM(True)
        fp.SetExcludedFromPosFiles(True)


# ---- board-only mechanical footprints (steel disc spot) -------------------
for ref, (lib, name, x, y, rot) in MECH.items():
    libdir = "/home/claude/podv2/lib" if lib == "lift" else FPLIB
    fp = pcbnew.FootprintLoad(f"{libdir}/{lib}.pretty", name)
    fp.SetReference(ref)
    fp.SetFPID(pcbnew.LIB_ID(lib, name))
    fp.SetBoardOnly(True)
    fp.SetExcludedFromBOM(True)
    fp.SetExcludedFromPosFiles(True)
    board.Add(fp)
    fp.SetPosition(P(x, y))
    fp.SetOrientationDegrees(rot)


# ---- keep-outs under the nRF module: its underside has pads, so only the
# short escape tracks from each pad go there. No vias under the body (any
# layer), no tracks on F.Cu inside the pad ring.
def rule_area(layers, x0, y0, x1, y1, tracks, vias):
    z = pcbnew.ZONE(board)
    z.SetIsRuleArea(True)
    ls = pcbnew.LSET()
    for l in layers:
        ls.AddLayer(l)
    z.SetLayerSet(ls)
    z.SetDoNotAllowTracks(not tracks)
    z.SetDoNotAllowVias(not vias)
    z.SetDoNotAllowPads(False)
    z.SetDoNotAllowFootprints(False)
    z.SetDoNotAllowCopperPour(False)
    ol = z.Outline()
    ol.NewOutline()
    for (x, y) in [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]:
        ol.Append(mm(OX + x), mm(OY + y))
    board.Add(z)


ux, uy = PLACE["U1"][:2]
rule_area([pcbnew.F_Cu, pcbnew.In1_Cu, pcbnew.In2_Cu, pcbnew.B_Cu],
          ux - 5.0, uy - 7.75, ux + 5.0, uy + 7.75, tracks=True, vias=False)
rule_area([pcbnew.F_Cu], ux - 3.3, uy - 3.9, ux + 3.3, uy + 5.85, tracks=False, vias=False)


# ---- drawings: where the cell and motor sit (documentation only) ---------
def user_rect(x0, y0, x1, y1):
    s = pcbnew.PCB_SHAPE(board)
    s.SetShape(pcbnew.SHAPE_T_RECT)
    s.SetStart(P(x0, y0))
    s.SetEnd(P(x1, y1))
    s.SetLayer(pcbnew.Dwgs_User)
    s.SetWidth(mm(0.1))
    board.Add(s)


def user_text(x, y, txt):
    t = pcbnew.PCB_TEXT(board)
    t.SetText(txt)
    t.SetPosition(P(x, y))
    t.SetLayer(pcbnew.Dwgs_User)
    t.SetTextSize(pcbnew.VECTOR2I(mm(0.8), mm(0.8)))
    t.SetTextThickness(mm(0.12))
    board.Add(t)


user_rect(*CELL)
user_text((CELL[0] + CELL[2]) / 2, (CELL[1] + CELL[3]) / 2 + 1.5, "LiPo 301120 (on top of parts)")
c = pcbnew.PCB_SHAPE(board)
c.SetShape(pcbnew.SHAPE_T_CIRCLE)
c.SetCenter(P(MOTOR[0], MOTOR[1]))
c.SetEnd(P(MOTOR[0] + MOTOR[2], MOTOR[1]))
c.SetLayer(pcbnew.Dwgs_User)
c.SetWidth(mm(0.1))
board.Add(c)
user_text(MOTOR[0], MOTOR[1], "coin motor (on module)")


# ---- copper planes ------------------------------------------------------
def zone(layer, netname, prio=0):
    z = pcbnew.ZONE(board)
    z.SetLayer(layer)
    z.SetNet(net(netname))
    z.SetAssignedPriority(prio)
    z.SetLocalClearance(mm(0.2))
    z.SetMinThickness(mm(0.2))
    z.SetPadConnection(pcbnew.ZONE_CONNECTION_FULL if layer in (pcbnew.F_Cu, pcbnew.B_Cu)
                       else pcbnew.ZONE_CONNECTION_THERMAL)
    z.SetIslandRemovalMode(pcbnew.ISLAND_REMOVAL_MODE_ALWAYS)
    z.SetThermalReliefGap(mm(0.25))
    z.SetThermalReliefSpokeWidth(mm(0.3))
    ol = z.Outline()
    ol.NewOutline()
    for (x, y) in [(0, 0), (W, 0), (W, H), (0, H)]:
        ol.Append(mm(OX + x), mm(OY + y))
    board.Add(z)
    return z


zone(pcbnew.In1_Cu, "/GND")
zone(pcbnew.In2_Cu, "/+3V3")
zone(pcbnew.B_Cu, "/GND")
zone(pcbnew.F_Cu, "/GND")

board.Save("/home/claude/podv2/gympod.kicad_pcb")
print("footprints:", len(board.GetFootprints()), "nets:", len(nets))
