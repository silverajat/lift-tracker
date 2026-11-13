"""Write simulated sets for the app's unit tests (RepEngineTest.kt):
app/android/app/src/test/resources/repsets/*.csv. The header line holds the
true rep count and what this Python reference counts, so the test checks
the Kotlin port gives the same answers."""
import os
import numpy as np
import sim
from counter import count_v1, last_bout

OUT = os.path.join(os.path.dirname(__file__), "../../app/android/app/src/test/resources/repsets")


def main():
    os.makedirs(OUT, exist_ok=True)
    for f in os.listdir(OUT):
        os.remove(os.path.join(OUT, f))
    rng = np.random.default_rng(2026)
    k = 0
    for lift in sim.LIFTS:
        for one_arm in ([False, True] if lift in ("lateral", "triceps", "curl") else [False]):
            for _ in range(3):
                n = int(rng.integers(5, 13))
                d, true = sim.simulate(lift, n, rng, one_arm_first=one_arm, noise=1.3)
                text = "\n".join(",".join(np.format_float_positional(v, unique=True) for v in row) for row in d)
                d2 = np.array([[float(v) for v in line.split(",")] for line in text.splitlines()], np.float32)
                x = d2
                if one_arm:
                    a, b = last_bout(d2, 25)
                    x = d2[a:b]
                py = count_v1(x)
                with open(os.path.join(OUT, f"{k:02d}_{lift}{'_onearm' if one_arm else ''}.csv"), "w") as fh:
                    fh.write(f"# lift={lift} true={true} py={py} onearm={int(one_arm)}\n{text}\n")
                k += 1
    print(k, "sets written")


if __name__ == "__main__":
    main()
