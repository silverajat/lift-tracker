"""Fanned-out board -> Specctra DSN for Freerouting. Outer pours are removed
(re-poured after import) and the inner planes are marked as power layers."""
import os, re, sys
import pcbnew
IN, OUT = sys.argv[1], sys.argv[2]
b = pcbnew.LoadBoard(IN)
for z in list(b.Zones()):
    if z.GetLayer() in (pcbnew.F_Cu, pcbnew.B_Cu):
        b.Remove(z)
pcbnew.ExportSpecctraDSN(b, OUT)
s = open(OUT).read()
if os.environ.get("IN1_POWER", "1") == "1":
    s = re.sub(r'\(layer In1\.Cu\s*\(type signal\)', '(layer In1.Cu (type power)', s)
s = re.sub(r'\(layer In2\.Cu\s*\(type signal\)', '(layer In2.Cu (type power)', s)
open(OUT, "w").write(s)
print("dsn written", s.count("(type power)"))
os._exit(0)
