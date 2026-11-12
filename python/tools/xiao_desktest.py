"""D1 step 1 on the real XIAO: erase, guided desk test, list, dump.

Run by double-clicking XIAO_desk_test.bat in the lift-tracker folder.
Everything printed is also saved to logs/desk_test_<time>.txt so Claude can
read it afterwards.
"""
import datetime as dt
import pathlib
import subprocess
import sys
import time

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
from dump_logs import Shell, find_port   # noqa: E402

LOG_DIR = ROOT / "logs"
LOG_DIR.mkdir(exist_ok=True)
log = open(LOG_DIR / f"desk_test_{dt.datetime.now():%Y%m%d_%H%M%S}.txt", "w", encoding="utf-8")


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s, flush=True)
    log.write(s + "\n"); log.flush()


def countdown(n, msg):
    for i in range(n, 0, -1):
        print(f"\r  {msg}: {i:2d} s ", end="", flush=True)
        time.sleep(1)
    print("\r" + " " * 60 + "\r", end="")


try:
    port = find_port()
    say("Board found on", port)
    sh = Shell(port)
    for cmd in ("rec status", "rec imu"):
        say(f"> {cmd}"); [say("  " + l) for l in sh.run(cmd)]
    if not any("imu started: yes" in l for l in sh.run("rec imu")):
        say("STOPPED: the IMU did not start. Tell Claude (the log has the details).")
        raise SystemExit("imu not started")
    say("> rec erase   (up to 30 s)")
    [say("  " + l) for l in sh.run("rec erase", timeout=60)]
    say("")
    say("DESK TEST. Lay the XIAO flat on the desk, cable still plugged in, don't touch it.")
    countdown(5, "Recording starts in")
    [say("  " + l) for l in sh.run("rec start")]
    say("Recording. Keep it STILL ...")
    countdown(10, "Still")
    say("NOW pick it up and do 5 SLOW curl motions (about 2 s up, 2 s down each).")
    countdown(25, "Curls")
    say("Put it down.")
    [say("  " + l) for l in sh.run("rec stop")]
    for cmd in ("rec list", "rec status"):
        say(f"> {cmd}"); [say("  " + l) for l in sh.run(cmd)]
    sh.ser.close()
    time.sleep(1)
    say("")
    say("Dumping recordings to data/raw ...")
    r = subprocess.run([sys.executable, str(HERE / "dump_logs.py")], capture_output=True, text=True)
    say(r.stdout); say(r.stderr)
    say("DONE. Tell Claude it finished.")
except SystemExit as e:
    say("STOPPED:", e)
except Exception as e:   # keep the window useful for debugging
    say("ERROR:", repr(e))
finally:
    log.close()
    input("\nPress Enter to close this window.")
