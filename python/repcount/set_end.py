"""When is the set over? Reference for the app's auto-end (reps/SetEnd.kt).

Not a timer: a pause between reps can be long (breathing at lockout, a grinder), so a gap alone
says nothing. What does change at the end is the motion itself:

  * RACK: the counter's trim_end drops a "rep" at the end that's far bigger than the set's reps
    (or bigger and quicker): putting the weight back. Over once it's ~0.6 s past.
  * POSE: the wrist ends up somewhere it never was during the set (hands off the bar, arms
    dropping, the wrist turning over to reach for the phone) and stays there. The set's own
    range of wrist angles is measured on its reps, so a lift that swings the wrist a lot
    (lateral raise, curl) needs a far bigger change than one that holds it still (bench).

Both need 3+ reps first. The cut is where the change began, so it never removes a rep.
"""
import numpy as np

from counter import DEFAULT, count_v1, candidates, choose, prepared, clean_runs, runs, trim_end

CONE_MIN = 12.0      # deg: the set's own range of wrist angles is at least this
CONE_PAD = 30.0      # deg beyond that range ...
HOLD_S = 0.6         # ... held this long
RACK_AFTER_S = 0.6   # a dropped rack "rep": end once it's this far back


def windows(data, hz, p=DEFAULT, cands=None):
    """(rep windows after trim_end, all windows, the counted signal) like count_v1."""
    if cands is None:
        cands = candidates(np.asarray(data, np.float64), hz)
    pick = choose(cands, hz, p)
    if pick is None:
        return [], [], None
    _, name, T = pick
    w = (1.0 if name == "tilt" else 2.0) * p["detrend"] * T
    x = prepared(cands[name], hz, w)
    amp = (np.percentile(x, 95) - np.percentile(x, 5)) / 2
    rs = clean_runs(runs(x, p["h"] * amp), p["gap"] * T)
    reps = int(np.ceil((len(rs) - 1) / 2)) if rs else 0
    allw = [(rs[2 * k][1], rs[2 * k + 2][1] if 2 * k + 2 < len(rs) else len(x)) for k in range(reps)]
    return trim_end(x, allw), allw, x


def gravity(data, hz):
    """Sensor-fusion gravity direction per sample (pod frame), as counter.fuse."""
    from counter import fuse, gyro_bias
    d = np.asarray(data, np.float64)
    gb, _, _ = fuse(d, hz, gyro_bias(d, hz))
    return gb / np.linalg.norm(gb, axis=1, keepdims=True)


def detect(data, hz=25, p=DEFAULT):
    """None while the set is going, else (cut, why): count data[:cut]."""
    n = len(data)
    if n < 4 * hz:
        return None
    ws, allw, x = windows(data, hz, p)
    if len(ws) < 3:
        return None
    last_end = ws[-1][1]
    # RACK: trim_end dropped something at the end, and it's done
    if len(allw) > len(ws) and last_end < n:
        dropped_end = allw[-1][1]
        if dropped_end < n and n - dropped_end >= RACK_AFTER_S * hz:
            return last_end, "rack"
    # POSE: the wrist left the set's range of angles and stayed out. The range comes from every rep but
    # the last (the change itself can look like one more rep), and the search starts after the one before.
    g = gravity(data, hz)
    a0, b0 = ws[0][0], ws[-2][1]
    m = g[a0:b0].mean(0)
    m /= np.linalg.norm(m)
    ang = np.degrees(np.arccos(np.clip(g @ m, -1, 1)))
    cone = max(CONE_MIN, np.percentile(ang[a0:b0], 95))
    out = ang > cone + CONE_PAD
    hold = int(HOLD_S * hz)
    run = 0
    for i in range(b0, n):
        run = run + 1 if out[i] else 0
        if run >= hold:
            start = i - run + 1
            # back up to where it started leaving the set's range
            while start > b0 and ang[start - 1] > cone:
                start -= 1
            if count_v1(data[:start], hz, p) >= 3:
                return start, "pose"
            return None
    return None


def stream(data, hz=25, step=5, p=DEFAULT):
    """Run detect as the app does live (every `step` samples). (fire index, cut, why) or None."""
    for n in range(4 * hz, len(data) + 1, step):
        r = detect(data[:n], hz, p)
        if r:
            return n, r[0], r[1]
    return None
