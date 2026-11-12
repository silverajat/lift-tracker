#!/bin/bash
# staged routing; each stage routes a net group on top of the previous board
# usage: ./staged.sh   (needs fan.kicad_pcb + route.dsn from export_dsn.py)
set -e
STAGES=(
  "+3V3"
  "IMU_MOSI IMU_SCK IMU_MISO IMU_CS IMU_INT1 IMU_INT2"
  "FL_MOSI FL_SCK FL_MISO FLASH_CS SWDIO SWDCLK"
  "LED LED_A LED_CHG_A MOTOR_G MOTOR_N MOTOR_EN CHG_STAT CHG_PROG VBAT_SENSE VBAT VCHG CH_A CH_B"
)
prev=fan.kicad_pcb; dsn=route.dsn; i=0
for nets in "${STAGES[@]}"; do
  i=$((i+1)); n=sg$i
  python3 dsn_subset.py $dsn $n.dsn $nets >/dev/null
  for try in 1 2 3; do ./route_sub.sh $n $n.dsn; [ -f $n.ses ] && break; done
  [ -f $n.ses ] || { echo "stage $i failed"; exit $i; }
  python3 import_ses.py $prev $n.ses $n.kicad_pcb 2>/dev/null | grep import
  cp gympod.kicad_pro $n.kicad_pro
  python3 export_dsn.py $n.kicad_pcb ${n}b.dsn >/dev/null
  prev=$n.kicad_pcb; dsn=${n}b.dsn
done
cp $prev routed.kicad_pcb; cp gympod.kicad_pro routed.kicad_pro
echo STAGED_OK
