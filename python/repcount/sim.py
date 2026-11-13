"""Synthetic wrist IMU for gym sets, to test rep counters before real data.

The pod sits on the right wrist. Each lift is a joint angle profile theta(t)
(how much the forearm turns) plus wrist travel p(t) (how far the hand moves),
played out rep by rep with a lift-like tempo: concentric, pause, eccentric,
pause, each a minimum-jerk move. The IMU is simulated at 100 Hz exactly as
the BMI270/LSM6 sees it (specific force in g, body rate in deg/s, noise and
gyro bias), then decimated to the app's 25 Hz stream (every 4th sample, like
firmware/logger/src/ble.c).

Not a biomechanics model: just enough physics that a counter which works
here has a fair chance on the real thing, and one that fails here (e.g. the
v0 counter on slow presses) fails for the same reason it fails in the gym.
"""
import numpy as np

G = 9.81
FS = 100
DECIM = 4


def rot(axis, ang):
    """Rotation matrices for angles ang (rad, array) about unit axis."""
    axis = np.asarray(axis, float)
    axis = axis / np.linalg.norm(axis)
    x, y, z = axis
    c, s = np.cos(ang), np.sin(ang)
    C = 1 - c
    R = np.empty((len(ang), 3, 3))
    R[:, 0, 0] = c + x * x * C
    R[:, 0, 1] = x * y * C - z * s
    R[:, 0, 2] = x * z * C + y * s
    R[:, 1, 0] = y * x * C + z * s
    R[:, 1, 1] = c + y * y * C
    R[:, 1, 2] = y * z * C - x * s
    R[:, 2, 0] = z * x * C - y * s
    R[:, 2, 1] = z * y * C + x * s
    R[:, 2, 2] = c + z * z * C
    return R


def minjerk(n):
    u = np.linspace(0, 1, n, endpoint=False)
    return 10 * u ** 3 - 15 * u ** 4 + 6 * u ** 5


# Lift profiles: forearm turn (deg), wrist travel (m), travel direction in
# the world, tempo (concentric, eccentric, pauses, s). Rotation axis is in
# the pod's frame.
LIFTS = {
    #               turn  travel  dir          conc  ecc   pause   axis
    "ohp":        (12,   0.45,  (0, 0, 1),   1.6,  2.2,  0.5,   (1, 0, 0)),
    "pulldown":   (45,   0.50,  (0, 0, -1),  1.5,  2.4,  0.5,   (0, 1, 0)),
    "incline":    (10,   0.40,  (0.3, 0, 1), 1.3,  2.0,  0.4,   (1, 0, 0)),
    "row":        (20,   0.35,  (-1, 0, 0),  1.0,  1.6,  0.4,   (0, 0, 1)),
    "curl":       (120,  0.30,  (0, 0, 1),   1.0,  1.8,  0.3,   (0, 1, 0)),
    "lateral":    (75,   0.40,  (0, 1, 0.6), 1.0,  1.6,  0.3,   (1, 0, 0)),
    "pecdec":     (70,   0.40,  (0, 1, 0),   1.0,  1.6,  0.3,   (0, 0, 1)),
    "triceps":    (100,  0.25,  (0, 0, -1),  0.9,  1.5,  0.3,   (0, 1, 0)),
}


def rep_profile(n_reps, conc, ecc, pause, rng, slow_end=True):
    """Phase 0..1..0 per rep (0 = start position), at FS, with tempo jitter."""
    parts = []
    amps = []
    for k in range(n_reps):
        fatigue = 1 + (0.35 * k / max(1, n_reps - 1) if slow_end else 0)
        c = conc * fatigue * rng.uniform(0.85, 1.15)
        e = ecc * rng.uniform(0.85, 1.15)
        p1 = pause * rng.uniform(0.3, 1.2)
        p2 = pause * rng.uniform(0.3, 1.5)
        a = rng.uniform(0.85, 1.05)
        up = minjerk(int(c * FS))
        down = 1 - minjerk(int(e * FS))
        seg = np.concatenate([up, np.ones(int(p1 * FS)), down, np.zeros(int(p2 * FS))]) * a
        parts.append(seg)
        amps.append(a)
    return np.concatenate(parts), amps


def still(n, rng, sway=0.01):
    """Small slow wobble while 'still'."""
    t = np.arange(n) / FS
    taper = np.sin(np.linspace(0, np.pi, n)) if n > 1 else np.zeros(n)   # starts and ends at 0
    return sway * taper * np.sin(2 * np.pi * rng.uniform(0.2, 0.5) * t + rng.uniform(0, 6))


def simulate(lift, n_reps, rng, one_arm_first=False, noise=1.0, rack_s=(0.5, 1.0), rack_amp=(0.1, 0.4)):
    """Returns (data[N,6] at 25 Hz: ax ay az g, gx gy gz deg/s, true reps)."""
    turn, travel, d, conc, ecc, pause, axis = LIFTS[lift]
    turn *= rng.uniform(0.8, 1.15)
    travel *= rng.uniform(0.8, 1.15)
    conc *= rng.uniform(0.8, 1.3)
    ecc *= rng.uniform(0.8, 1.3)
    prof, _ = rep_profile(n_reps, conc, ecc, pause, rng)

    pre = int(rng.uniform(0.8, 2.0) * FS)
    post = int(rng.uniform(0.5, 1.5) * FS)
    # one-arm lifts: the left arm goes first while the pod arm is (mostly)
    # still, then a short reach/grab, then the right arm's reps.
    left = np.zeros(0)
    if one_arm_first:
        lprof, _ = rep_profile(n_reps, conc, ecc, pause, rng)
        left = np.concatenate([still(len(lprof), rng, 0.03), np.zeros(int(rng.uniform(1.0, 3.0) * FS))])
        grab = np.sin(np.linspace(0, np.pi, int(0.8 * FS))) * 0.15
        left = np.concatenate([left, grab, np.zeros(int(0.7 * FS))])
    rack = np.sin(np.linspace(0, np.pi, int(rng.uniform(*rack_s) * FS))) * rng.uniform(*rack_amp)
    phase = np.concatenate([still(pre, rng), left, prof, rack, still(post, rng)])
    n = len(phase)

    # orientation: a fixed but random wrist pose, then the forearm turn
    R0 = rot(rng.normal(size=3), np.array([rng.uniform(0, 2 * np.pi)]))[0]
    sway = np.deg2rad(2.0) * np.sin(2 * np.pi * 0.3 * np.arange(n) / FS + rng.uniform(0, 6))
    theta = np.deg2rad(turn) * phase + sway
    R = R0 @ rot(axis, theta)                      # body -> world
    dtheta = np.gradient(theta) * FS
    ax = np.asarray(axis, float) / np.linalg.norm(axis)
    gyro = np.rad2deg(dtheta)[:, None] * ax[None, :]   # body rate (axis fixed in body)

    dvec = np.asarray(d, float) / np.linalg.norm(d)
    pos = travel * phase[:, None] * dvec[None, :]
    acc_w = np.gradient(np.gradient(pos, axis=0), axis=0) * FS * FS
    f_w = acc_w + np.array([0, 0, G])
    acc_b = np.einsum("nji,nj->ni", R, f_w) / G      # R^T f

    bias = rng.uniform(-3, 3, size=3)
    acc_b += rng.normal(0, 0.015 * noise, acc_b.shape)
    gyro = gyro + bias + rng.normal(0, 1.0 * noise, gyro.shape)
    data = np.hstack([acc_b, gyro])[::DECIM]
    return data.astype(np.float32), n_reps
