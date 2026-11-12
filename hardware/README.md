# Hardware

## pod/ — gym prototype enclosure

OpenSCAD source (`lift_pod.scad`, all sizes are parameters at the top) and print-ready STLs for the base, separator and lid. Holds the XIAO nRF52840 Sense and a 302030 LiPo; a one-piece 20 mm loop band threads through a channel underneath.

Print: PETG (PLA for fit tests), 0.16–0.2 mm layers, 3 walls, no supports. Base floor-down with a brim, lid dome-up, separator flat.

To change a size: edit the number in `lift_pod.scad`, render (F6), export STL.

Later: `pcb/` for the custom board (D8).
