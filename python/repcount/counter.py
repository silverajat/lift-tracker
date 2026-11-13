"""Rep counters: v0 (what the app shipped) and v1 (sensor fusion).

v1 is the reference for app/.../reps/RepCounter.kt: same steps, same
numbers, so a set can be checked here against what the phone said.

Why v0 double counts slow presses: it picks whichever raw channel moves
most. On an overhead press the forearm barely turns, so that's an
accelerometer axis, and acceleration goes through TWO cycles per rep
(speed up, slow down on the way up; again on the way down). Counting its
swings counts each rep twice. v1 only counts signals that go through ONE
cycle per rep: how fast the forearm turns, how far it's tilted, how fast
the wrist moves up/down or forward/back (acceleration integrated to speed,
after the gravity estimate from sensor fusion is taken out).
"""
import numpy as np

# --------------------------------------------------------------------- v0 ---


def _ma(x, w):
    """Trailing moving average (only the past)."""
    c = np.cumsum(np.insert(x.astype(np.float64), 0, 0.0))
    out = np.empty(len(x))
    for i in range(len(x)):
        lo = max(0, i + 1 - w)
        out[i] = (c[i + 1] - c[lo]) / (i + 1 - lo)
    return out


def count_v0(data, hz=25):
    n = len(data)
    if n < hz:
        return 0
    noise = [0.04, 0.04, 0.04, 10, 10, 10]
    best, bs = None, 0
    for c in range(6):
        sm = _ma(data[:, c], max(1, hz // 5))
        hp = sm - _ma(sm, 4 * hz)
        s = hp.std() / noise[c]
        if s > bs:
            bs, best = s, hp
    if best is None or bs < 1.5:
        return 0
    return int(np.ceil(len(_swings(best, 0.5 * best.std(), int(0.5 * hz))) / 2))


def _swings(x, h, min_gap):
    state, last, sw = 0, -min_gap, []
    for i, v in enumerate(x):
        nxt = 1 if v > h else (-1 if v < -h else state)
        if nxt != state:
            if state != 0 and i - last >= min_gap:
                sw.append(i)
                last = i
            state = nxt
    return sw


# --------------------------------------------------------------------- v1 ---

DEFAULT = dict(h=0.35, gap=0.10, signal="auto", detrend=1.0)
NAMES = ["turn", "tilt", "lift", "push"]


def gyro_bias(data, hz):
    """Mean gyro over the stillest second (if it's really still)."""
    g = data[:, 3:6]
    w = hz
    if len(g) < w:
        return np.zeros(3)
    best, bi = 1e9, -1
    mag = np.linalg.norm(g, axis=1)
    for i in range(0, len(g) - w + 1, max(1, w // 5)):
        s = mag[i:i + w].std()
        if s < best:
            best, bi = s, i
    if best > 4.0:
        return np.zeros(3)
    return g[bi:bi + w].mean(axis=0)


def fuse(data, hz, bias):
    """Gravity direction in the pod's frame, tracked with the gyro and gently
    pulled toward the accelerometer (complementary filter, ~2 s), and the
    linear acceleration (accel minus gravity) in m/s^2, split into its
    vertical part and its horizontal part (pod frame)."""
    dt = 1.0 / hz
    alpha = dt / 2.0
    n = len(data)
    gb = np.zeros((n, 3))
    g = data[0, :3] / max(1e-6, np.linalg.norm(data[0, :3]))
    for i in range(n):
        a = data[i, :3].astype(np.float64)
        w = np.deg2rad(data[i, 3:6] - bias)
        g = g - np.cross(w, g) * dt              # gravity is fixed in the world
        an = np.linalg.norm(a)
        if abs(an - 1.0) < 0.35:                 # trust accel only near 1 g
            g = (1 - alpha) * g + alpha * a / an
        g = g / np.linalg.norm(g)
        gb[i] = g
    lin = (data[:, :3] - gb) * 9.81              # m/s^2, pod frame
    vert = np.einsum("ij,ij->i", lin, gb)        # + = up
    horiz = lin - vert[:, None] * gb
    return gb, vert, horiz


def main_axis(v):
    """Unit vector of the biggest variance (power iteration)."""
    v = v - v.mean(axis=0)
    cov = v.T @ v
    u = np.ones(3)
    for _ in range(30):
        nu = cov @ u
        ln = np.linalg.norm(nu)
        if ln < 1e-12:
            return np.array([1.0, 0, 0])
        u = nu / ln
    return u


def leaky_int(x, hz, tau=1.5):
    lam = np.exp(-1.0 / (hz * tau))
    out = np.empty(len(x))
    s = 0.0
    for i, v in enumerate(x):
        s = lam * s + v / hz
        out[i] = s
    return out


def candidates(data, hz):
    bias = gyro_bias(data, hz)
    gb, vert, horiz = fuse(data, hz, bias)
    gyro = data[:, 3:6] - bias
    turn = gyro @ main_axis(gyro)                          # deg/s
    tilt = np.rad2deg(np.arcsin(np.clip((gb - gb.mean(0)) @ main_axis(gb), -1, 1)))  # deg
    lift = leaky_int(vert, hz)                             # m/s
    push = leaky_int(horiz @ main_axis(horiz), hz)         # m/s
    return {"turn": turn, "tilt": tilt, "lift": lift, "push": push}


# What counts as moving, per signal (noise floor of the 25 Hz stream)
FLOOR = {"turn": 6.0, "tilt": 2.0, "lift": 0.04, "push": 0.04}


def period(x, hz):
    """Rep period (samples) and how periodic the signal is (0..1), from the
    autocorrelation's first clear peak between 0.8 s and 9 s."""
    x = x - x.mean()
    n = len(x)
    lo, hi = int(0.8 * hz), min(int(9 * hz), n - hz)
    if hi <= lo + 2:
        return 3 * hz, 0.0
    e = float(x @ x)
    if e <= 0:
        return 3 * hz, 0.0
    r = np.array([float(x[:n - L] @ x[L:]) / e * n / (n - L) for L in range(lo, hi + 1)])
    best_l, best_r = 3 * hz, 0.0
    peak = r.max()
    for k in range(1, len(r) - 1):
        if r[k] >= r[k - 1] and r[k] >= r[k + 1] and r[k] > 0.25 and r[k] >= 0.6 * peak:
            return lo + k, float(r[k])
    return best_l, best_r


def _cma(x, w):
    """Centred moving average (window w, shrinks at the ends)."""
    c = np.cumsum(np.insert(x.astype(np.float64), 0, 0.0))
    n, h = len(x), w // 2
    out = np.empty(n)
    for i in range(n):
        lo, hi = max(0, i - h), min(n, i + h + 1)
        out[i] = (c[hi] - c[lo]) / (hi - lo)
    return out


def detrend(x, w):
    return x - _cma(x, max(3, int(w)))


def prepared(x, hz, w):
    return detrend(_ma(x, max(1, hz // 5)), w)


def amplitude(x):
    return (np.percentile(x, 95) - np.percentile(x, 5)) / 2


def choose(cands, hz, p):
    """Pick the signal: the most periodic one that clearly moves. When the
    forearm turns a lot, the gravity estimate lags behind and leaks into
    the speeds worked out from acceleration, so those are skipped then."""
    turns = amplitude(prepared(cands["tilt"], hz, 4 * hz)) >= 15 or \
        amplitude(prepared(cands["turn"], hz, 4 * hz)) >= 40
    best = None
    for name in NAMES:
        if p["signal"] != "auto" and name != p["signal"]:
            continue
        if p["signal"] == "auto" and turns and name in ("lift", "push"):
            continue
        x = prepared(cands[name], hz, 4 * hz)
        amp = amplitude(x)
        snr = amp / FLOOR[name]
        if snr < 2.0:
            continue
        T, per = period(x, hz)
        score = per * min(1.0, snr / 4)
        if best is None or score > best[0]:
            best = (score, name, T)
    return best


def runs(x, h):
    """Stretches where the signal is clearly high (+1) or low (-1), with
    hysteresis: it has to cross the other threshold to switch. Each run is
    [sign, start, end, samples actually beyond the threshold]."""
    out = []
    state = 0
    for i, v in enumerate(x):
        nxt = 1 if v > h else (-1 if v < -h else state)
        if nxt != state:
            if out:
                out[-1][2] = i
            if nxt != 0:
                out.append([nxt, i, len(x), 0])
            state = nxt
        if out and state * v > h:
            out[-1][3] += 1
    return out


def clean_runs(rs, min_len):
    """Drop blips shorter than min_len: re-racking at the end, a twitch
    mid-rep. An interior blip joins its two neighbours into one stretch."""
    rs = [list(r) for r in rs]
    while True:
        last = len(rs) - 1
        k = next((i for i, r in enumerate(rs)
                  if r[3] < min_len or (i < last and r[2] - r[1] < 2 * min_len)), None)
        if k is None:
            return rs
        if k == 0 or k == len(rs) - 1:
            rs.pop(k)
        else:
            a, b = rs[k - 1], rs[k + 1]
            rs[k - 1:k + 2] = [[a[0], a[1], b[2], a[3] + b[3]]]


def count_v1(data, hz=25, p=DEFAULT, return_info=False, cands=None):
    if len(data) < hz:
        return (0, None) if return_info else 0
    if cands is None:
        cands = candidates(np.asarray(data, np.float64), hz)
    pick = choose(cands, hz, p)
    if pick is None:
        return (0, None) if return_info else 0
    _, name, T = pick
    # Speeds (turn, lift, push) already sit at 0 when still: only remove
    # slow drift. Positions (tilt) need their mean taken out per rep.
    w = (1.0 if name == "tilt" else 2.0) * p["detrend"] * T
    x = prepared(cands[name], hz, w)
    amp = (np.percentile(x, 95) - np.percentile(x, 5)) / 2
    rs = clean_runs(runs(x, p["h"] * amp), p["gap"] * T)
    reps = int(np.ceil((len(rs) - 1) / 2)) if rs else 0
    if return_info:
        return reps, dict(signal=name, T=T / hz, swings=[r[1] for r in rs[1:]], x=x)
    return reps


def last_bout(data, hz):
    """One-arm lifts: the pod's (right) arm is still while the left arm
    works, then does its own reps. Returns (start, end) of the last clear
    stretch of movement that's long enough to be a set (>= 3 s), so only
    the right arm's reps get counted."""
    n = len(data)
    bias = gyro_bias(data, hz)
    gmag = np.linalg.norm(data[:, 3:6] - bias, axis=1)
    amag = np.abs(np.linalg.norm(data[:, :3], axis=1) - 1.0) * 300   # 0.1 g ~ 30 deg/s
    act = _cma(np.maximum(gmag, amag), hz)
    thr = max(12.0, 0.25 * np.percentile(act, 95))
    on = act > thr
    segs, i = [], 0
    while i < n:
        if on[i]:
            j = i
            while j < n and on[j]:
                j += 1
            segs.append([i, j])
            i = j
        else:
            i += 1
    merged = []
    for s in segs:
        if merged and s[0] - merged[-1][1] < 1.5 * hz:
            merged[-1][1] = s[1]
        else:
            merged.append(s)
    long_ = [s for s in merged if s[1] - s[0] >= 3 * hz]
    if not long_:
        return 0, n
    a, b = long_[-1]
    return max(0, a - hz // 2), min(n, b + hz // 2)
