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

Before the average and spread are worked out, any reading more than 3 robust spreads (1.4826 × median absolute deviation) from the median is pulled in to that limit. Without this, one wild night (a bad reading, a sick day) would stretch "normal" for a whole month, and every real warning after it would look normal.

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

## Body (what last night's signals say together)

Each night, five signals are compared with your normal: the median and robust spread (1.4826 × median absolute deviation) of the nights 4 to 33 days earlier. The last 3 days are left out so a few rough days don't become the new normal. Needs 7+ nights.

| Signal | A bit off | Well off |
|---|---|---|
| Breathing rate (up) | +0.5/min and 1.5 spreads | +1.0/min and 2.5 spreads |
| Skin temperature (up) | +0.5 °C and 1.5 spreads | +1.0 °C and 2.5 spreads |
| Resting HR (up) | +3 bpm and 1.5 spreads | +5 bpm and 2.5 spreads |
| HRV (either way) | 12% and 1.5 spreads | 25% and 2.5 spreads |
| Blood oxygen (down) | −1 point and 1.5 spreads | −2 points and 2.5 spreads |
| Time asleep (up) | +60 min and 1.5 spreads | |

Then the pattern is read together with what you did, and each possible reason gets its evidence:

- **Getting sick:** breathing rate and skin temperature up are the strongest early signs (both rose before symptoms in WHOOP's, Oura's and others' studies). Points: breathing 2–3, temperature 2–3, resting HR, HRV, oxygen and longer sleep 1–2 each, +1 if the night before was off too. It only counts with breathing, temperature or oxygen off, or resting HR and HRV off together. 3+ points: possible; 5+ with breathing, temperature or oxygen: likely. Skin temperature only shows up if the Fitbit shares it with Health Connect.
- **Recovering from yesterday's training:** resting HR up or HRV off, breathing normal, after a day with strain 14+, a gym day 120%+ of your usual, or muscular strain 12+.
- **A heavy week adding up:** HRV's 7-day average 10%+ below normal (or 25%+ above, which in hard training can mean deep fatigue) while this week's strain is 30%+ above the month's.
- **Short on sleep:** the same heart signs after under 75% of your need, or 2+ hours short over 3 nights.
- **Late meal:** the same, one night only, after last food past 9:30 PM.
- **Stress, alcohol or an injury:** heart signs clearly off with nothing above to explain them. After 3+ nights it's flagged more strongly: injuries and inflammation raise resting HR too.
- **Settling down:** after a sick-looking night in the last 10 days, which signs are back to normal and which aren't yet.
- **Recovering well:** HRV up, resting HR normal or lower, breathing normal.

None of this is a diagnosis: it says the body is under some load and what fits best.

## Strain (0–21)

Heart rate load is Banister TRIMP: for each minute, `HRr × 0.64 × e^(1.92 × HRr)`. HRr is (HR − resting) ÷ (max − resting), and only minutes at 30%+ of reserve count.

The gym adds muscular load from the pod (sets × reps × weight, add-ons at 1 kg each), turned into the same units (× 0.25).

`strain = 21 × (1 − e^(−TRIMP / 110))`. It's logarithmic like WHOOP's, Borg-inspired 0–21 scale: each extra point takes more work. An hour at ~75% of heart-rate reserve comes to about 14.

Bands: light 0–9, moderate 10–13, high 14–17, all out 18–21.

Heart-rate zones are minutes at each share of max HR. Zones 2–3 build your base; 4–5 are hard efforts.

## Sleep

- **Need:** 7 h 30 min, plus up to 45 min after a hard day (+4 min per strain point above 10, today's full strain including the gym), plus half of what's still owed from the last 3 nights (max 60 min). Owed is the net shortfall against 7 h 30: a long night pays back a short one, so it really does reset as you catch up.
- **Tonight's lights-out:** tomorrow's wake-up minus need ÷ your sleep efficiency (last 7 nights; nobody sleeps 100% of the night) minus 15 min to fall asleep. Tomorrow's wake-up is your median wake time on that same weekday over the last 6 weeks (needs 2), else weekdays vs weekends, else the last 2 weeks. Tonight's need uses today's strain so far, so it moves through the day, and adds 30 min when Body says you're fighting something off (15 while settling).
- **Performance:** time asleep ÷ need.
- **Score:**

  | Part | Weight | How it counts |
  |---|---|---|
  | Hours vs need | 55% | |
  | Consistency | 15% | how close your bed and wake times are to the last 4 nights; −1% per minute of spread |
  | Efficiency | 15% | asleep ÷ in bed. 85% is the line for good sleep (National Sleep Foundation), scores 75; 90%+ scores full |
  | Deep | 8% | share of sleep. 5% or less scores 0, 15%+ full |
  | REM | 7% | share of sleep. 10% or less scores 0, 20%+ full |

  Deep and REM are scored separately because they do different jobs (deep: physical repair and growth hormone; REM: memory and mood), so lots of one can't hide too little of the other. They carry only 15% together because wrist trackers are least accurate at telling sleep stages apart, deep especially; one low night means little, a low week means more.

  Zones: 85+ optimal, 70–84 sufficient, under 70 poor.
- **Stages:** typical adults spend 13–23% in deep, 20–25% in REM, and about half in light.

## Body weight

- **Trend:** each day moves 10% of the way toward that morning's weigh-in (an exponentially weighted average, from the Hacker's Diet). Water, salt and food make the scale bounce. The trend is the number that matters, which is why it can say 56.6 while the scale said 56.4.
- **Rate:** a straight-line fit through the last 2 or 4 weeks of weigh-ins, in kg/week.
- **Daily swing:** how much a single weigh-in normally bounces around the trend. Changes smaller than this mean nothing.
- **Graph:** blue line = trend, shading = normal bounce, dashed = goal pace from the start of the range. Hold and slide to read any day.

## Gym

- **Add-ons:** 1 kg each. They count as weight everywhere: graphs, records, volume, one-rep max.
- **Est. one-rep max:** `weight × (1 + reps / k)`, with k per lift. Epley uses k = 30 for everything, but how many reps a kilo is worth differs by exercise: at 80% of max, about 9 reps on the bench press but about 13 on the leg press (Nuzzo et al. 2023, 269 studies), and a fixed k fits light and isolation lifts worst (2026 analysis of 303,000 logged sets).
  - Starting k: upper-body compounds 34, lower-body compounds 50, isolation 38.
  - Then learned from your sessions: every time the weight changed from one session to the next, how many reps you gained or lost says what a kilo is worth on that lift. k is the value that keeps est. 1RM steadiest across those changes, pulled toward the start value (session-to-session noise 2.5%, start value ±30%).
- **Heavier, fewer reps:** after a weight jump, the reps you'd expect if you were exactly as strong as last time: `k × (last 1RM ÷ new weight − 1)`. Within ¾ of a rep: on track; up to 2 short: normal; more: a big step for this lift.
- **What a step costs:** the same +1.25 kg is +15% on an 8 kg raise (about 6 reps) but +2% on a 62 kg pec dec (under 1). "Next time" says go heavier only when you'd still land in the rep range (top − 3) at the new weight; otherwise it says how many reps to build to first.
- **"Next time":**
  - If every working set hit the top of the rep range, go up one step, unless that step would drop you below the range on this lift (then build reps first).
  - If a set fell 3+ reps short of the range, build back to it.
  - Otherwise, add a rep per set.
- **Strength arrow:** change in your best set's one-rep max over about 3 weeks.
- **A lift's graph:** opens on strength (est. 1RM), which goes up with more weight or more reps. Under each point is the set behind it (8.3×11), so you can see which one moved. "Reps at top weight" charts reps alone.
- **A lift's headline:** from each session's strongest set: new best (heavier, or +N reps at the same weight), heavier, +N reps, down 3%+ in est. 1RM, no new best for 3+ sessions, or holding.
- **Workout vs last:** compared with the last workout of the same day type. A lift counts as stronger or weaker when its best one-rep max moved more than 1%.

## Food targets

- **Calories:** your scale-based maintenance once there are 10+ fully logged days over 2+ weeks; before that, today's Fitbit burn corrected by the scale (k). Plus the weight goal × 7,700 ÷ 7.
- **Today's burn so far → whole day:** the Fitbit's number so far, plus the rest of the day the way your days usually go: asleep at about BMR (55% of the day's average rate), awake at the rest of a typical day spread over waking hours. Bedtime and time in bed come from your last 2 weeks.
- **Protein:** 2.2 g per kg of trend weight (top of the range where more stops helping muscle gain; Morton et al. 2018).
- **Fat:** 20% of calories (low end of the 20–35% guideline), never under 0.8 g/kg.
- **Carbs:** everything left.
- **Fibre:** 14 g per 1,000 kcal (US Dietary Guidelines), never under 26 g (HPB's figure for men).
- **"Move more" suggestions:** only the extra burn counts. Running ~0.9 kcal per kg per km on top of resting; football 7 METs and badminton 5.5 METs (Compendium of Physical Activities), minus the 1 MET you'd burn sitting anyway.

## What affects your recovery

Next-morning recovery on days with vs without a habit. Needs 5+ days each way. "Clear" only when the difference is more than 2.5 × its uncertainty: six habits are tested at once, so the usual 2 would flag a fluke about 1 time in 4.

## Rep counter

- **What it counts:** reps from the pod's motion. It picks the signal that repeats most cleanly, then counts swings with hysteresis.
- **Learning:** from every corrected set. Each exercise gets its own settings after 3. Warm-ups learn separately, because they're lighter and quicker.
- **Stop-button reach:** reaching for the phone is cut off at the end of the set: a short burst of movement under about 1.6 s, after the set has gone still for 0.3 s or more.
- **Hands off the bar:** racking, letting go and reaching for the phone with no still moment in between can look like 1–3 extra reps. They're nothing like the set's reps, so the last "rep" is dropped while it's 2.5× the size of a typical rep in the set, or 1.3× the size in 0.85× the time. Needs 4+ reps and never goes below 3.

## The pod (v2)

- Asleep unless a workout is open: the app switches it into gym mode when a set's countdown starts, and back to
  sleep when you finish the workout (or by itself 5 min after the app goes away, or 45 min after the last set).
- It buzzes once when rest is over and twice when a set starts.
- Tap it twice (two quick knocks on the case) to end the set: the sensor's own tap detector, on only in gym mode.
  A short buzz says it took.
- After a workout the app copies the pod's full-rate recordings (100 Hz) to the phone and then clears the pod.
  "Export N recordings" on the pod bar saves them as a zip in the same layout as the laptop dumps.
- The live stream and the rep counter are unchanged from the prototype, so everything learned so far carries over.
  First flash and updates: `firmware/pod/BRINGUP.md`.

## Auto-end

A set stops itself when the motion changes, not after a pause (a long breather at lockout is still the set):

- **Racking:** a last "rep" far bigger than the set's own reps (or bigger and quicker), once it's 0.6 s past.
- **Hands off / arms down / reaching for the phone:** the wrist ends up at an angle it never reached during the
  set's reps (30° beyond that range) and stays there 0.6 s. Lifts that swing the wrist a lot need a bigger change.

It needs 3 reps first, and the set is cut where the change began, so it never costs a rep. "Keep going" gives
2 s to cancel; it won't stop again until another rep. Every saved set is replayed through it: a lift where it
would ever have stopped a set before its last rep keeps auto-end off. Off for one-arm lifts and lifts the pod
doesn't count. Reference: `python/repcount/set_end.py`.

## Your week

Monday to Sunday (or to today), each number next to the week before. Opens from Today; on Monday morning a notification sums up the week that ended.

- **Training:** gym sessions, working sets, volume (add-ons included, one-arm lifts ×2), average daily strain.
- **Lifts:** each lift's best set this week against your last session before the week, in est. 1RM with the lift's own k. "New best" when it beats everything before.
- **Recovery:** average score, green/yellow/red days, HRV (geometric mean) and resting HR against the 30 days before the week.
- **Sleep:** asleep and needed per night, nights that met 95% of the need, sleep score, usual bedtime and its spread.
- **Food:** fully logged days only. Calories, protein, carbs, fat and fibre against each day's own targets.
- **Weight:** trend change over the week against the goal pace, and weigh-ins.
- **Body:** nights with one sign well off or two a bit off.
- **What to change (at most 4, most important first):**
  1. Lighter week if 3+ red days or HRV 10%+ below your month.
  2. Earlier bedtime if 30+ min short a night, or steadier bedtimes if they spread more than ±45 min.
  3. Protein under 90% of target: how many more shakes.
  4. Log full days if under 3.
  5. Weight off the goal pace by 0.2+ kg/week: the kcal a day to change.
  6. Fewer gym sessions than your usual (last 4 weeks).
  7. Two or more lifts not moving.
  8. Fibre under 85% of target.

## Garden

Level n starts at 100 × n(n−1)/2 points.

| Plant | Points |
|---|---|
| Study and work | 1 per focused minute; the timer pauses while you're in other apps |
| Gym | 60 + 3 per working set |
| Sports | strain × 6 per game or run. Walks get a third of that, 20 a day at most |
| Sleep | 60 / 40 / 20 by sleep score zone (85+ / 70–84 / under 70), +20 for green recovery |
| Mind | 10 for planning, 15 per main task done, 25 for reflecting, 1 per minute read (90 a day at most), 50 per book finished |

## Sources

- Fitbit / Google Health Daily Readiness: https://support.google.com/googlehealth/answer/14236710
- Oura Readiness Score: https://ouraring.com/blog/readiness-score/
- WHOOP Strain: https://www.whoop.com/au/en/thelocker/how-does-whoop-strain-work-101
- WHOOP Strain, independent breakdown: https://the5krunner.com/2022/05/24/whoop-strain-everything/
- HRV-guided training history (Altini): https://marcoaltini.substack.com/p/a-brief-history-of-heart-rate-variability
- HRV-guided training rules: https://www.athletedata.health/guides/hrv-guided-training
- Sleep quality indicators (NSF, Ohayon 2017): https://sleepreviewmag.com/sleep-health/prevailing-attitudes/good-quality-sleep-national-sleep-foundation-provides-guidance/
- Sleep stage norms (Ohayon 2004 meta-analysis): https://www.med.upenn.edu/cbti/assets/user-content/documents/carskadonmeta-analysisresleepnorms.pdf
- HPB fibre recommendation: https://www.healthhub.sg/live-healthy/more-fibre-for-a-fit-and-fabulous-you
