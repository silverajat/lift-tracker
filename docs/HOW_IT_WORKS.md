# How Life works out its numbers

The app shows results, not methods. This file holds the explanations that used to be on screen.

## Recovery (1–99%)

Each morning, compared with your last 30 days (needs 5+ days of data).

| Part | Weight | How it counts |
|---|---|---|
| HRV last night (ln RMSSD) | 35% | Near your normal or a little above is good. Far off in either direction counts against you: a big drop means stress, illness, alcohol or hard training; a big spike (2–3× normal) is also a warning, often heavy load or a bad reading. |
| HRV, past 7 days (average) | 15% | Same shape, compared with the same normal. One odd night can't carry the score. |
| Resting heart rate | 25% | Lower than normal helps a little (capped); higher hurts, up to 2.5 "usual spreads". A rise of 3+ bpm often means stress, illness, alcohol or a late meal. |
| Sleep, last 4 nights | 20% | 0.6 × last night + 0.4 × the three before, as % of what you needed; 85% is neutral. |
| Breathing rate | 5% | A rise of 1+ breath a minute can be an early sign of getting sick. A drop doesn't add anything. |

Each part is a z-score against your own normal: (today − your average) ÷ your usual spread. Spreads have floors (HRV 8%, resting HR 2 bpm, breathing 0.6/min), so a very steady month can't make a small blip look like a crash.

Combined: `score = 100 × Φ(z / 0.7 + 0.3)`, where Φ is the normal curve. A normal day lands around 60. One combined "usual spread" better gives about 90; one worse gives about 25.

Zones: green 67+, yellow 34–66, red under 34. Strain targets: green 14–18, yellow 10–14, red 5–10.

Why it's built this way:

- **Fitbit / Google Health Daily Readiness** uses HRV, resting HR and the past week of sleep. It flags HRV "much higher than usual" as a negative. Its bands are high 65+, moderate 30–64, low under 30. Activity was dropped as an input.
- **WHOOP Recovery** is HRV-led, plus resting HR, sleep and breathing rate, against your own baseline. Zones are green 67+, yellow 34–66, red under 34.
- **Oura Readiness** compares HRV balance (recent vs long-term) and resting HR "substantially higher **or lower** than usual". It also uses sleep, sleep balance over 2 weeks, temperature and activity balance.
- **HRV-guided training research** (Kiviniemi 2007; Plews & Buchheit; Vesterinen; Javaloyes 2020):
  - Daily HRV is noisy. A ~7-day average against a 1–2-month normal range is more reliable.
  - Going outside the normal range in **either** direction is a reason for caution. High HRV can mean parasympathetic overreaching (Le Meur 2013).
  - The usual decision rule: one low day changes little; 2–3 low days in a row means swap hard sessions for easy ones.

## Strain (0–21)

Heart rate load is Banister TRIMP: for each minute, `HRr × 0.64 × e^(1.92 × HRr)`. HRr is (HR − resting) ÷ (max − resting), and only minutes at 30%+ of reserve count.

The gym adds muscular load from the pod (sets × reps × weight, add-ons at 1 kg each), turned into the same units (× 0.25).

`strain = 21 × (1 − e^(−TRIMP / 110))`. It's logarithmic like WHOOP's, Borg-inspired 0–21 scale: each extra point takes more work. An hour at ~75% of heart-rate reserve comes to about 14.

Bands: light 0–9, moderate 10–13, high 14–17, all out 18–21.

Heart-rate zones are minutes at each share of max HR. Zones 2–3 build your base; 4–5 are hard efforts.

## Sleep

- **Need:** 7 h 30 min, plus up to 45 min after a hard day (+4 min per strain point above 10), plus half of the last 3 nights' shortfall (max 60 min). It resets as you catch up.
- **Performance:** time asleep ÷ need.
- **Score:**

  | Part | Weight | How it counts |
  |---|---|---|
  | Hours vs need | 55% | |
  | Consistency | 15% | how close your bed and wake times are to the last 4 nights; −1% per minute of spread |
  | Efficiency | 15% | asleep ÷ in bed; 85%+ is good |
  | Restorative | 15% | deep + REM share |

  Zones: 85+ optimal, 70–84 sufficient, under 70 poor.
- **Stages:** typical adults spend 13–23% in deep, 20–25% in REM, and about half in light.

## Body weight

- **Trend:** each day moves 10% of the way toward that morning's weigh-in (an exponentially weighted average, from the Hacker's Diet). Water, salt and food make the scale bounce. The trend is the number that matters, which is why it can say 56.6 while the scale said 56.4.
- **Rate:** a straight-line fit through the last 2 or 4 weeks of weigh-ins, in kg/week.
- **Daily swing:** how much a single weigh-in normally bounces around the trend. Changes smaller than this mean nothing.
- **Graph:** blue line = trend, shading = normal bounce, dashed = goal pace from the start of the range. Hold and slide to read any day.

## Gym

- **Add-ons:** 1 kg each. They count as weight everywhere: graphs, records, volume, one-rep max.
- **Est. one-rep max:** Epley, `weight × (1 + reps / 30)`.
- **"Next time":**
  - If every working set hit the top of the rep range, go up one step.
  - If a set fell 3+ reps short of the range, build back to it.
  - Otherwise, add a rep per set.
- **Strength arrow:** change in your best set's one-rep max over about 3 weeks.
- **A lift's graph headline:** new best, down 3%+ from last time, no new best for 3+ sessions, or holding.
- **Workout vs last:** compared with the last workout of the same day type. A lift counts as stronger or weaker when its best one-rep max moved more than 1%.

## Rep counter

- **What it counts:** reps from the pod's motion. It picks the signal that repeats most cleanly, then counts swings with hysteresis.
- **Learning:** from every corrected set. Each exercise gets its own settings after 3. Warm-ups learn separately, because they're lighter and quicker.
- **Stop-button reach:** reaching for the phone is cut off at the end of the set: a short burst of movement under about 1.6 s, after the set has gone still for 0.3 s or more.

## Garden

Level n starts at 100 × n(n−1)/2 points.

| Plant | Points |
|---|---|
| Study and work | 1 per focused minute; the timer pauses while you're in other apps |
| Gym | 60 + 3 per working set |
| Sports | strain × 6 per game or run. Walks get a third of that, 20 a day at most |
| Sleep | 60 / 40 / 20 by sleep zone, +20 for green recovery |
| Mind | 10 for planning, 15 per main task done, 25 for reflecting, 1 per minute read (90 a day at most), 50 per book finished |

## Sources

- Fitbit / Google Health Daily Readiness: https://support.google.com/googlehealth/answer/14236710
- Oura Readiness Score: https://ouraring.com/blog/readiness-score/
- WHOOP Strain: https://www.whoop.com/au/en/thelocker/how-does-whoop-strain-work-101
- WHOOP Strain, independent breakdown: https://the5krunner.com/2022/05/24/whoop-strain-everything/
- HRV-guided training history (Altini): https://marcoaltini.substack.com/p/a-brief-history-of-heart-rate-variability
- HRV-guided training rules: https://www.athletedata.health/guides/hrv-guided-training
