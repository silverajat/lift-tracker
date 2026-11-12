# Hardware

## pod/ — gym prototype enclosure

OpenSCAD source (`lift_pod.scad`, all sizes are parameters at the top) and print-ready STLs for the base, separator and lid. Holds the XIAO nRF52840 Sense and a 302030 LiPo; a one-piece 20 mm loop band threads through a channel underneath.

Print: PETG (PLA for fit tests), 0.16–0.2 mm layers, 3 walls, no supports. Base floor-down with a brim, lid dome-up, separator flat.

To change a size: edit the number in `lift_pod.scad`, render (F6), export STL.

## pod-v2/ — the final, Fitbit-sized pod

36.2 × 20.2 × 7.0 mm. A custom 4-layer board (nRF52840 module, BMI270 IMU, 2 MB flash, 40 mAh LiPo, coin motor) that charges on the Google Fitbit Air charger. The board is the pod's underside: two gold contacts 18 mm apart plus a steel disc for the charger's magnet, with a bridge so it charges either way round. Everything else goes over Bluetooth.

- `SPEC.md`: decisions, circuit, pin map, parts, assembly.
- `pcb/`: the whole board as Python data (`design.py`, `placement.py`) → KiCad schematic, placement, staged Freerouting, DRC (`make_release.sh`).
- `release/liftpod_v2_revA/`: Gerbers, JLC BOM/CPL, renders, schematic PDF, checks, `README_ORDER.md`.
- `lift_pod_v2.scad` / `lift_pod_v2_shell.stl`: the one-piece shell for JLC3DP (SLA black resin).
