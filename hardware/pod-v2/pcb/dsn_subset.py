"""Keep only some nets in a DSN's network section (others become plain obstacles).
usage: dsn_subset.py in.dsn out.dsn NET [NET ...]   (names without the leading /)"""
import re, sys
src, dst, keep = sys.argv[1], sys.argv[2], set("/" + n for n in sys.argv[3:])
s = open(src).read()
def strip_nets(s):
    out, i = [], 0
    for m in re.finditer(r'\(net (\S+)\n?\s*\(pins[^)]*\)\s*\)', s):
        out.append(s[i:m.start()])
        if m.group(1) in keep: out.append(m.group(0))
        i = m.end()
    out.append(s[i:]); return "".join(out)
s = strip_nets(s)
open(dst, "w").write(s)
print("nets kept:", len(re.findall(r'\(net \S+\n?\s*\(pins', s)))
