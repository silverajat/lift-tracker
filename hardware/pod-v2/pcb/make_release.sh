#!/bin/bash
# routed pod v2 board -> DRC -> gerbers/drill zip, BOM/CPL, renders, schematic PDF
set -e
cd $(dirname "$0")
REL=${1:-$(pwd)/../release/liftpod_v2_revA}
rm -rf $REL; mkdir -p $REL/fabrication/gerbers $REL/assembly $REL/previews $REL/checks
unset DISPLAY
kicad-cli pcb drc --severity-all --schematic-parity --format json -o $REL/checks/drc.json gympod.kicad_pcb >/dev/null || true
cp erc.json $REL/checks/erc.json
kicad-cli pcb export gerbers --layers F.Cu,In1.Cu,In2.Cu,B.Cu,F.Paste,B.Paste,F.Silkscreen,B.Silkscreen,F.Mask,B.Mask,Edge.Cuts --output $REL/fabrication/gerbers/ gympod.kicad_pcb >/dev/null
kicad-cli pcb export drill --output $REL/fabrication/gerbers/ gympod.kicad_pcb >/dev/null
(cd $REL/fabrication/gerbers && zip -q ../liftpod_v2_revA_JLCPCB_Gerbers.zip *)
python3 make_fab.py gympod.kicad_pcb $REL/assembly
kicad-cli pcb render --output $REL/previews/board_top.png --width 1600 --height 900 --side top gympod.kicad_pcb >/dev/null
kicad-cli pcb render --output $REL/previews/board_bottom.png --width 1600 --height 900 --side bottom gympod.kicad_pcb >/dev/null
kicad-cli sch export pdf -o $REL/previews/schematic.pdf gympod.kicad_sch >/dev/null
python3 plotboard.py gympod.kicad_pcb $REL/previews/net_map.png 99.5 99.5 141.5 118.5 2>/dev/null || true
cp README_ORDER.md $REL/
echo release built: $REL
