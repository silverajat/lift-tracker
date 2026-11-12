"""Panel DRC uses the fab's real limits (the LED board's: 0.1 mm clearance,
0.127 mm track, 0.45/0.25 via). Both boards already pass their own, stricter
or equal, rule sets on their own."""
import json, sys
d = json.load(open(sys.argv[1]))
c = d["net_settings"]["classes"][0]
c.update(clearance=0.1, track_width=0.127, via_diameter=0.45, via_drill=0.25)
json.dump(d, open(sys.argv[1], "w"), indent=2)
