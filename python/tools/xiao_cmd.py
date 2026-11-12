"""Send shell commands to the XIAO logger and save the replies.

    python xiao_cmd.py "device list" "rec status"
Output also goes to logs/xiao_cmd_<time>.txt so Claude can read it.
"""
import datetime as dt
import pathlib
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from dump_logs import Shell, find_port   # noqa: E402

(ROOT / "logs").mkdir(exist_ok=True)
out = open(ROOT / "logs" / f"xiao_cmd_{dt.datetime.now():%Y%m%d_%H%M%S}.txt", "w", encoding="utf-8")


def say(s):
    print(s, flush=True); out.write(s + "\n"); out.flush()


cmds = sys.argv[1:] or ["device list", "rec status"]
try:
    sh = Shell(find_port())
    for c in cmds:
        say(f"> {c}")
        for line in sh.run(c, timeout=15):
            say("  " + line)
except BaseException as e:
    say(f"ERROR: {e!r}")
finally:
    out.close()
time.sleep(1)
