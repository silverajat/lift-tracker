#!/bin/bash
# routed gym board -> panel -> DRC -> gerbers/drill zip, BOM/CPL, renders
set -e
cd /home/claude/gympod-pcb
LED=/mnt/user-data/uploads/projects/led-sync/source/ledctl.kicad_pcb
REL=release/liftpod_ledctl_panel_v1.1
rm -rf $REL; mkdir -p $REL/fabrication $REL/assembly $REL/previews $REL/checks
python3 make_panel.py $LED final/gympod.kicad_pcb $REL/panel.kicad_pcb
python3 fix_panel_rules.py $REL/panel.kicad_pro
kicad-cli pcb drc --severity-all --format json -o $REL/checks/panel_drc.json $REL/panel.kicad_pcb >/dev/null || true
kicad-cli pcb export gerbers --layers F.Cu,In1.Cu,In2.Cu,B.Cu,F.Paste,B.Paste,F.Silkscreen,B.Silkscreen,F.Mask,B.Mask,Edge.Cuts --output $REL/fabrication/gerbers/ $REL/panel.kicad_pcb >/dev/null
kicad-cli pcb export drill --output $REL/fabrication/gerbers/ $REL/panel.kicad_pcb >/dev/null
(cd $REL/fabrication/gerbers && zip -q ../panel_v1.1_JLCPCB_Gerbers.zip *)
python3 make_panel_fab.py $REL/panel.kicad_pcb $REL/assembly /mnt/user-data/uploads/projects/led-sync/assembly/ledctl_v1_BOM_JLCPCB.csv
kicad-cli pcb render --output $REL/previews/panel_top.png --width 1600 --height 1000 --side top $REL/panel.kicad_pcb >/dev/null
kicad-cli pcb render --output $REL/previews/gympod_top.png --width 1400 --height 900 --side top --zoom 1 final/gympod.kicad_pcb >/dev/null
kicad-cli pcb render --output $REL/previews/gympod_bottom.png --width 1400 --height 900 --side bottom final/gympod.kicad_pcb >/dev/null
# order notes, sources, schematic PDF, single-board checks
cp README_ORDER.md JLCPCB_ORDER.md $REL/
mkdir -p $REL/source/gympod $REL/source/scripts
cp final/gympod.kicad_pcb final/gympod.kicad_sch final/gympod.kicad_pro final/gympod.kicad_sym final/sym-lib-table $REL/source/gympod/
cp design.py gen_schematic.py placement.py build_pcb.py fanout.py export_dsn.py import_ses.py stitch.py silk.py make_panel.py make_panel_fab.py fix_panel_rules.py make_release.sh plotboard.py gympod.kicad_sym nopaste.py $REL/source/scripts/
kicad-cli sch export pdf -o $REL/previews/gympod_schematic.pdf final/gympod.kicad_sch >/dev/null
cp final/drc.json $REL/checks/gympod_drc.json; cp erc.json $REL/checks/gympod_erc.json
cp review_round3.md $REL/checks/independent_review.md; cp review_round2.md $REL/checks/independent_review_v1.md
python3 plotboard.py final/gympod.kicad_pcb $REL/previews/gympod_net_map.png 100 100 138 127 2>/dev/null || true
echo release built
