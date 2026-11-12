"""DNP wire pads get no solder paste (J104 is on the bottom: without this the
bottom stencil would print paste on two pads nobody populates)."""
import os, sys
import pcbnew
b = pcbnew.LoadBoard(sys.argv[1])
n = 0
for fp in b.GetFootprints():
    if fp.GetReference() in ("J103", "J104"):
        for p in fp.Pads():
            ls = p.GetLayerSet()
            for L in (pcbnew.F_Paste, pcbnew.B_Paste):
                if ls.Contains(L):
                    ls.RemoveLayer(L); n += 1
            p.SetLayerSet(ls)
b.Save(sys.argv[1])
print("paste removed from", n, "pad layers")
os._exit(0)
