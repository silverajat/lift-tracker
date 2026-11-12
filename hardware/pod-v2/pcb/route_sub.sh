#!/bin/bash
# route_sub.sh NAME DSN : run Freerouting up to ~3 min; report unrouted trend and whether SES was written
NAME=$1; DSN=$2
rm -f $NAME.ses
timeout 400 java -jar /home/claude/tools/fr.jar -de $DSN -do $NAME.ses --gui.enabled=false > fr_$NAME.log 2>&1 &
PID=$!
for i in $(seq 1 24); do sleep 8; [ -f $NAME.ses ] && break; n=$(grep -c "pass #" fr_$NAME.log); [ "$n" -ge 25 ] && break; done
sleep 2; kill $PID 2>/dev/null; pkill -f "fr.jar -de $DSN" 2>/dev/null
echo "$NAME: $(grep 'pass #' fr_$NAME.log | grep -o '([0-9]* unrouted)' | tail -3 | tr '\n' ' ') ses=$( [ -f $NAME.ses ] && echo yes || echo no)"
