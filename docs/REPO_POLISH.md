# Polished public repo: plan

Pod v2 bring-up is done and the README is rewritten (28 Nov). Still to do: the demo clip below, and photos of the
finished pod (the README has `<!-- PHOTO -->` markers where they go).

## The hero demo (top of the README)

A ~15 s clip: Rajat doing **preacher curls on the machine** wearing the pod, side by side with a **live screen recording of
the app** counting the reps, so anyone can see the count is right as it happens.

How to shoot it:
- Phone screen recording running (Android quick settings → Screen record) with the app on the set screen.
- A second phone or a friend filming the lift, framed so the reps and the pod on the wrist are clear.
- Clap once at the start, in view of the camera and with the phone recording, to line the two videos up.
- 8–12 clean reps, then rack. Auto-end should stop the set on its own, which is worth showing too.
- Put the two side by side (lift on the left, app on the right), trimmed to ~15 s.

Make it small enough for GitHub: an MP4 under 10 MB plays inline in a README (drag it into the README editor on github.com),
or a GIF (≤ ~8 MB, 480 px wide, 12–15 fps), e.g.
`ffmpeg -i demo.mp4 -vf "fps=12,scale=480:-1:flags=lanczos" -loop 0 demo.gif`.

## Rest of the polish (to plan that day)

- README: one-line pitch, the demo, numbers (rep accuracy on real sets), how it works diagram, hardware photos (docs/photos),
  build/flash steps, links to HOW_IT_WORKS.md and the pod-v2 hardware folder.
- Check nothing personal or secret is in the repo before making it public: API keys, health data exports, data/raw recordings.
