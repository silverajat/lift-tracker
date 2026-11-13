"""The app's on-device learner (reference for reps/RepModel.kt).

Every set you save keeps its 25 Hz data and the rep count you confirmed.
After each set the counter's settings are re-fitted to those corrections:
for each exercise, try every combination on the grid below and keep the
one that miscounts the fewest reps over your past sets (ties go to the
default). Exercises with fewer than 3 corrected sets use the settings
fitted on all your sets together.
"""
import itertools
import numpy as np
from counter import DEFAULT, count_v1, candidates

GRID = dict(
    h=[0.25, 0.35, 0.45, 0.55],
    gap=[0.06, 0.10, 0.15, 0.20],
    signal=["auto", "turn", "tilt", "lift", "push"],
    detrend=[0.75, 1.0, 1.5],
)
MIN_SETS = 3


def combos():
    keys = list(GRID)
    for vals in itertools.product(*(GRID[k] for k in keys)):
        yield dict(zip(keys, vals))


def changed(p):
    """How far from the defaults (forcing a signal counts double)."""
    return sum((2 if k == "signal" else 1) * (p[k] != DEFAULT[k]) for k in DEFAULT)


def fit(sets, hz=25):
    """sets: list of (data, true_reps). Returns (params, errors, default's errors).
    Only moves off the defaults when that fixes a clear, repeated mistake
    (at least 3 reps and 0.3 of a rep per set): with a handful of
    sets, chasing single miscounts would just learn the noise."""
    cache = [(candidates(np.asarray(d, np.float64), hz), d, t) for d, t in sets]
    def err(p):
        return sum(abs(count_v1(d, hz, p, cands=c) - t) for c, d, t in cache)
    base = err(DEFAULT)
    best = None
    for p in combos():
        key = (err(p), changed(p))
        if best is None or key < best[0]:
            best = (key, p)
    (e, _), p = best
    if base - e >= max(3, 0.3 * len(sets)):
        return p, e, base
    return dict(DEFAULT), base, base
