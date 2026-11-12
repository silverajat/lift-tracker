"""
Pull recordings off the lift tracker logger (D1 firmware) as CSV files.

Usage (XIAO plugged in over USB, blue LED on):
    python dump_logs.py                 # auto-find the board, save every recording
    python dump_logs.py --port COM5     # pick the port yourself
    python dump_logs.py --erase         # erase the board after a successful dump

Files land in data/raw/<date-time>/rec_<id>.csv, one per recording, plus
recordings.csv listing them all. Fill in notes.csv afterwards: which
exercise, weight, warm-up or working, anything odd.

Units: accel in milli-g, gyro in 0.1 deg/s (divide by 10 for deg/s).
"""
import argparse
import csv
import datetime as dt
import pathlib
import sys
import time

import serial                      # pip install pyserial
from serial.tools import list_ports

ZEPHYR_VID = 0x2FE3                # USB vendor ID the firmware uses


def find_port():
    for p in list_ports.comports():
        if p.vid == ZEPHYR_VID or "Lift Tracker" in (p.product or ""):
            return p.device
    names = ", ".join(p.device for p in list_ports.comports()) or "none"
    sys.exit(f"Board not found. Ports seen: {names}. Try --port COMx.")


class Shell:
    """Talks to the Zephyr shell on the board, one command at a time."""

    def __init__(self, port):
        self.ser = serial.Serial(port, 115200, timeout=0.5)
        self.ser.dtr = True        # the firmware waits for DTR before talking
        time.sleep(0.5)
        self.ser.reset_input_buffer()
        self.ser.write(b"\r\n")
        time.sleep(0.3)
        self.ser.reset_input_buffer()

    def lines(self, cmd, until, timeout=60):
        """Send cmd, yield output lines until a line starts with `until`."""
        self.ser.write(cmd.encode() + b"\r\n")
        deadline = time.time() + timeout
        buf = b""
        while time.time() < deadline:
            chunk = self.ser.read(4096)
            if not chunk:
                continue
            deadline = time.time() + timeout   # still receiving: keep going
            buf += chunk
            *done, buf = buf.split(b"\n")
            for raw in done:
                line = raw.decode(errors="replace").strip()
                if line.startswith(until):
                    yield line
                    return
                yield line
        raise TimeoutError(f"no reply to '{cmd}'")

    def run(self, cmd, timeout=10):
        """Send cmd and return everything printed before the next prompt."""
        self.ser.write(cmd.encode() + b"\r\n")
        time.sleep(0.2)
        out, deadline = b"", time.time() + timeout
        while time.time() < deadline:
            chunk = self.ser.read(4096)
            out += chunk
            if b"uart:~$" in out.split(cmd.encode(), 1)[-1]:
                break
        text = out.decode(errors="replace").split(cmd, 1)[-1]
        return [l.strip() for l in text.splitlines()
                if l.strip() and not l.strip().startswith("uart:~$")]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port")
    ap.add_argument("--out", default="data/raw")
    ap.add_argument("--erase", action="store_true",
                    help="erase the board after everything is saved")
    args = ap.parse_args()

    sh = Shell(args.port or find_port())

    listing = [l for l in sh.run("rec list") if l and l[0].isdigit()]
    if not listing:
        print("No recordings on the board.")
        return
    print(f"{len(listing)} recordings on the board")

    out = pathlib.Path(args.out) / dt.datetime.now().strftime("%Y-%m-%d_%H%M")
    out.mkdir(parents=True, exist_ok=True)
    (out / "recordings.csv").write_text(
        "id,start_s,seconds,samples,pretrigger\n" + "\n".join(listing) + "\n")

    saved = 0
    for row in listing:
        rec_id = row.split(",")[0]
        rows = []
        started = False
        for line in sh.lines(f"rec dump {rec_id}", until=f"#end,{rec_id}"):
            if line.startswith("#begin"):
                started = True
            elif line.startswith("#end"):
                break
            elif started and line and (line[0].isdigit() or line.startswith("i,")):
                rows.append(line)
        path = out / f"rec_{int(rec_id):03d}.csv"
        path.write_text("\n".join(rows) + "\n")
        n = len(rows) - 1
        print(f"  rec {rec_id}: {n} samples -> {path}")
        saved += 1

    notes = out / "notes.csv"
    if not notes.exists():
        with notes.open("w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["id", "exercise", "weight_kg", "set_type", "reps", "notes"])
            for row in listing:
                w.writerow([row.split(",")[0], "", "", "working/warm-up/junk", "", ""])

    print(f"Saved {saved} recordings to {out}")
    print("Now fill in notes.csv: which exercise each recording was.")

    if args.erase and saved == len(listing):
        print("Erasing the board...")
        for l in sh.run("rec erase", timeout=90):
            print("  " + l)


if __name__ == "__main__":
    main()
