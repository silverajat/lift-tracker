"""Routed SES -> board, then pour zones. KiCad's SES import replaces all
tracks, so tracks of nets the SES doesn't mention (earlier routing stages,
fan-out) are put back afterwards."""
import os, sys
import pcbnew
IN, SES, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
b = pcbnew.LoadBoard(IN)
saved = [t.Duplicate() for t in b.GetTracks()]
ok = pcbnew.ImportSpecctraSES(b, SES)
have = {t.GetNetCode() for t in b.GetTracks()}
back = 0
for t in saved:
    if t.GetNetCode() not in have:
        b.Add(t); back += 1
print("import:", ok, "restored:", back)
pcbnew.ZONE_FILLER(b).Fill(b.Zones())
b.Save(OUT)
os._exit(0)
