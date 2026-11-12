"""Generate gympod.kicad_sch from design.py. Nets connect through labels
placed exactly on each pin's connection point."""
import copy
import sys
import uuid

from kiutils.items.common import Effects, Font, Justify, Position, Property
from kiutils.items.schitems import (LocalLabel, NoConnect, SchematicSymbol,
                                    SymbolProjectInstance, SymbolProjectPath, Text)
from kiutils.schematic import Schematic
from kiutils.symbol import SymbolLib

sys.path.insert(0, "/home/claude/gympod-pcb")
from design import PARTS  # noqa: E402

LIBDIR = "/usr/share/kicad/symbols"
_libs = {}


def lib_symbol(lib, name):
    """Return a flattened copy of a library symbol (resolves 'extends')."""
    if lib not in _libs:
        path = ("/home/claude/gympod-pcb/gympod.kicad_sym" if lib == "gympod"
                else f"{LIBDIR}/{lib}.kicad_sym")
        _libs[lib] = SymbolLib.from_file(path)
    syms = {s.entryName: s for s in _libs[lib].symbols}
    s = copy.deepcopy(syms[name])
    if s.extends:
        parent = copy.deepcopy(syms[s.extends])
        child_props = {p.key: p for p in s.properties}
        parent.properties = [child_props.get(p.key, p) for p in parent.properties]
        for u in parent.units:
            u.entryName = name
        parent.entryName = name
        parent.extends = None
        s = parent
    s.libraryNickname = lib
    return s


def uid():
    return str(uuid.uuid4())


NS = uuid.UUID("6f3c2a1e-9b8d-4c7e-a5f4-3d2b1c0e9f8a")


def sym_uuid(orig_ref):
    """Fixed UUID per part, so the board keeps matching the schematic."""
    return str(uuid.uuid5(NS, "gympod:" + orig_ref))


def new_ref(ref):
    """Gym pod parts are numbered from 101 so they never clash with the LED
    board's C1, R1, U1... when both share a JLCPCB panel."""
    import re as _re
    if ref.startswith("#"):
        return ref
    return _re.sub(r"(\d+)$", lambda m: str(100 + int(m.group(1))), ref)


def snap(v):
    return round(v / 1.27) * 1.27


sch = Schematic(version="20231120", generator="eeschema")
sch.uuid = "1d2c3b4a-5f6e-4a7b-8c9d-0e1f2a3b4c5d"
sch.paper.paperSize = "A2"
root_path = f"/{sch.uuid}"

placed_lib = {}
x0, y0 = 30.48, 30.48
col_x, row_y, row_h = x0, y0, 0.0
COL_W, PAGE_W = 60.96, 520.0

for part in PARTS:
    lib, name = part["sym"]
    key = f"{lib}:{name}"
    if key not in placed_lib:
        ls = lib_symbol(lib, name)
        placed_lib[key] = ls
        sch.libSymbols.append(ls)
    ls = placed_lib[key]

    pins = [p for u in ls.units for p in u.pins]
    xs = [p.position.X for p in pins] or [0]
    ys = [p.position.Y for p in pins] or [0]
    w = max(xs) - min(xs) + 25.4
    h = max(ys) - min(ys) + 15.24
    big = len(pins) > 20
    width = max(w + 30.48, COL_W if not big else w + 60.96)
    if col_x + width > PAGE_W:
        col_x = x0
        row_y += row_h + 12.7
        row_h = 0
    cx = snap(col_x - min(xs) + 20.32)
    cy = snap(row_y + max(ys) + 7.62)
    col_x += width
    row_h = max(row_h, h + 10)

    sym = SchematicSymbol()
    sym.libraryNickname, sym.entryName = lib, name
    sym.position = Position(cx, cy, 0)
    sym.unit = 1
    sym.inBom = not part.get("virtual", False) and not part.get("dnp", False)
    sym.onBoard = not part.get("virtual", False)
    sym.dnp = bool(part.get("dnp", False))
    sym.uuid = sym_uuid(part["ref"])
    props = {
        "Reference": new_ref(part["ref"]), "Value": part["value"], "Footprint": part["fp"],
        "Datasheet": "", "Description": "",
    }
    if part.get("lcsc"):
        props["LCSC"] = part["lcsc"]
    hide_keys = {"Footprint", "Datasheet", "Description", "LCSC"}
    for i, (k, v) in enumerate(props.items()):
        eff = Effects(font=Font(width=1.27, height=1.27))
        eff.hide = k in hide_keys or part.get("virtual", False)
        off = {"Reference": -3.81, "Value": 3.81}.get(k, 0)
        sym.properties.append(Property(key=k, value=v, id=i,
                                       position=Position(cx, cy + (-(max(ys)) - 3.81 if k == "Reference" else (-min(ys)) + 3.81 if k == "Value" else 0), 0),
                                       effects=eff))
    sym.pins = {p.number: uid() for p in pins}
    sym.instances = [SymbolProjectInstance(name="gympod", paths=[
        SymbolProjectPath(sheetInstancePath=root_path, reference=new_ref(part["ref"]), unit=1)])]
    sch.schematicSymbols.append(sym)

    # labels / no-connects on every pin
    done = set()
    for p in pins:
        if p.number not in part["pins"] and part.get("unused_nc"):
            part["pins"][p.number] = None        # spare GPIO: no-connect
        if p.number not in part["pins"]:
            raise SystemExit(f"{part['ref']} pin {p.number} ({p.name}) not assigned")
        net = part["pins"][p.number]
        ax = round(cx + p.position.X, 4)
        ay = round(cy - p.position.Y, 4)       # library Y is up, schematic Y is down
        if (ax, ay, net) in done:
            continue                              # stacked pins share one label
        done.add((ax, ay, net))
        if net is None:
            sch.noConnects.append(NoConnect(position=Position(ax, ay), uuid=uid()))
            continue
        ang = (p.position.angle + 180) % 360
        if ang in (90, 270):
            ang = (ang + 180) % 360
        lab = LocalLabel(text=net, position=Position(ax, ay, ang), uuid=uid())
        lab.effects = Effects(font=Font(width=1.0, height=1.0))
        if ang == 180:
            lab.effects.justify = Justify(horizontally="right")
        sch.labels.append(lab)
    extra = set(part["pins"]) - {p.number for p in pins}
    if extra:
        raise SystemExit(f"{part['ref']}: unknown pins {extra}")

t = Text(text="Gym pod v1 dev board - nRF52840 (MDBT50Q) + BMI270 + MCP73831 + ME6211. Generated from design.py",
         position=Position(30.48, 15.24, 0), uuid=uid())
t.effects = Effects(font=Font(width=2.0, height=2.0))
sch.texts.append(t)

from kiutils.items.schitems import HierarchicalSheetInstance
sch.sheetInstances = [HierarchicalSheetInstance(instancePath="/", page="1")]
sch.to_file("/home/claude/gympod-pcb/gympod.kicad_sch")
print("parts:", len(PARTS), "labels:", len(sch.labels), "no-connects:", len(sch.noConnects))
