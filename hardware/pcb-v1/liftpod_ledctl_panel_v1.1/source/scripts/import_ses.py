"""Routed SES -> board, then pour zones."""
import os, sys
import pcbnew
IN, SES, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
b = pcbnew.LoadBoard(IN)
ok = pcbnew.ImportSpecctraSES(b, SES)
print("import:", ok)
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(OUT)
os._exit(0)
