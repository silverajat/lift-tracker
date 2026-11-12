"""One JLCPCB panel: LED controller board (100 x 62) + gym pod board (38 x 24),
joined with mouse-bite tabs, 5 mm rails top and bottom with tooling holes
and fiducials (needed for JLCPCB assembly of a customer panel)."""
import sys
from itertools import chain

import pcbnew
from kikit import panelize_ui_impl as ki
from kikit.panelize import Origin, Panel
from kikit.units import mm as MM
mm = lambda v: int(v * MM)

LED = sys.argv[1]
GYM = sys.argv[2]
OUT = sys.argv[3]

preset = ki.obtainPreset(
    [":jlcTooling"],
    layout={"hspace": "3mm", "vspace": "3mm"},
    # 2 tabs on each long edge, none on the short edges: keeps tabs clear of
    # the LED board's ESP32 antenna keepout (x 0-20 on its top edge) and the
    # gym pod's antenna end. Bites sit on the edge line (offset 0) so the
    # holes stay clear of tracks that run 0.6 mm from the LED board's edge.
    tabs={"type": "fixed", "width": "3mm", "hcount": "0", "vcount": "2"},
    cuts={"type": "mousebites", "drill": "0.5mm", "spacing": "0.8mm",
          "offset": "0mm", "prolong": "0.5mm"},
    framing={"type": "railstb", "width": "5mm", "space": "3mm"},
    fiducials={"type": "3fid", "hoffset": "10mm", "voffset": "2.5mm",
               "coppersize": "1mm", "opening": "2mm"},
    post={"millradius": "1mm"},
)

led_board = pcbnew.LoadBoard(LED)
panel = Panel(OUT)
panel.inheritDesignSettings(led_board)
panel.inheritProperties(led_board)
panel.inheritLayerNames(led_board)

# LED board at the left; gym pod to its right, vertically centred.
led_bb = led_board.GetBoardEdgesBoundingBox()
led_w, led_h = led_bb.GetWidth(), led_bb.GetHeight()
gap = mm(3)
panel.appendBoard(LED, pcbnew.VECTOR2I(0, 0), origin=Origin.TopLeft,
                  tolerance=mm(1), inheritDrc=False)
gym_board = pcbnew.LoadBoard(GYM)
gb = gym_board.GetBoardEdgesBoundingBox()
panel.appendBoard(GYM, pcbnew.VECTOR2I(led_w + gap, (led_h - gb.GetHeight()) // 2),
                  origin=Origin.TopLeft, tolerance=mm(1), inheritDrc=False)

substrates = panel.substrates
framingSubstrates = ki.dummyFramingSubstrate(substrates, preset)
# The gym pod is much shorter than the LED board, so fill the space above and
# below it with solid blocks that join the rails. Its tabs then only bridge a
# 3 mm gap instead of hanging on long thin strips.
from shapely.geometry import box
from kikit.panelize_ui_impl import polygonToSubstrate
lx0, ly0, lx1, ly1 = substrates[0].bounds()
gx0, gy0, gx1, gy1 = substrates[1].bounds()
g3 = mm(3)
for blk in (box(gx0, ly0 - g3 - mm(0.5), gx1, gy0 - g3),
            box(gx0, gy1 + g3, gx1, ly1 + g3 + mm(0.5))):
    framingSubstrates.append(polygonToSubstrate(blk))
    panel.appendSubstrate(blk)
panel.buildPartitionLineFromBB(framingSubstrates)
# Tabs: KiKit's fixed layout for the LED board; hand-picked spots for the gym
# pod, which is dense right up to its edges. Its tabs sit next to resistors,
# LEDs and the DNP headers, away from the BMI270 (U102) and the MLCCs.
# Positions are mm from the gym board's left edge.
GYM_TABS = {"top": [8.0], "bottom": [26.5, 31.0]}   # bottom-left holds the battery socket: no tab there
GYM_TAB_W = {"top": 3.0, "bottom": 2.5}
from kikit.annotations import TabAnnotation
tp = preset["tabs"]
panel.clearTabsAnnotations()
panel.buildTabAnnotationsFixed(tp["hcount"], tp["vcount"], tp["hwidth"], tp["vwidth"],
                               tp["mindistance"], framingSubstrates)
gs = panel.substrates[1]
gx0g, gy0g, gx1g, gy1g = gs.bounds()
proto = {}
for a in gs.annotations:
    side = "top" if abs(a.origin[1] - gy0g) < abs(a.origin[1] - gy1g) else "bottom"
    proto.setdefault(side, a)
print("gym tab prototypes:", {k: (v.origin, v.direction) for k, v in proto.items()})
gs.annotations = []
for side, xs in GYM_TABS.items():
    a0 = proto[side]
    for x in xs:
        gs.annotations.append(TabAnnotation(None, (gx0g + mm(x), a0.origin[1]), a0.direction,
                                            mm(GYM_TAB_W[side]), a0.maxLength))
tabCuts = panel.buildTabsFromAnnotations(tp["fillet"])
preFrame = panel.boardSubstrate.substrates
frameCuts = ki.buildFraming(preset, panel)
ki.buildTabFillets(preset, panel, preFrame)
ki.buildTooling(preset, panel)
ki.buildFiducials(preset, panel)
ki.buildPostprocessing(preset["post"], panel)
ki.makeTabCuts(preset, panel, tabCuts)
ki.makeOtherCuts(preset, panel, chain([], frameCuts))
ki.setStackup(preset["source"], panel)
panel.save(reconstructArcs=True, refillAllZones=True)
if panel.hasErrors():
    print("PANEL ERRORS:", panel.errors)
print("panel saved")
import os
os._exit(0)   # pcbnew objects crash on interpreter teardown
