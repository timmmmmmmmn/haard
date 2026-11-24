#!/bin/sh
# Dag- of nachtstand van de pc, gelijk aan de stille tijd van Haard: 's nachts 23:00–08:00 (za/zo tot 10:00).
# Nacht: processor begrensd (ventilatoren blijven laag) en de schijf schrijft hooguit één keer per minuut (minder trillen).
uur=$(date +%H); dag=$(date +%u)   # 1 = maandag … 7 = zondag
tot=8; [ "$dag" -ge 6 ] && tot=10
if [ "$uur" -ge 23 ] || [ "$uur" -lt "$tot" ]; then
  systemctl stop haard-dag.service 2>/dev/null
  cpupower frequency-set -g powersave -u 1.6GHz >/dev/null
  sysctl -q -w vm.dirty_writeback_centisecs=6000 vm.dirty_expire_centisecs=6000
  echo "nachtstand"
else
  cpupower frequency-set -g performance -u 4GHz >/dev/null
  sysctl -q -w vm.dirty_writeback_centisecs=500 vm.dirty_expire_centisecs=3000
  echo "dagstand"
fi
