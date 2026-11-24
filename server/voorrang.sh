#!/bin/sh
# Spraak gaat voor: zodra Home Assistant audio naar Whisper stuurt (poort 10300), bevriest de training;
# 60 seconden na de laatste vraag loopt hij weer door. Zo is Haard snel, ook als er getraind wordt.
gepauzeerd=0; tot=0; pid=""; pidtijd=0
while :; do
  nu=$(date +%s)
  # Docker zet de verbinding door naar de container: kijken in het netwerk van de container zelf (pid elke 30 s verversen)
  if [ $((nu - pidtijd)) -ge 30 ] || [ -z "$pid" ]; then pid=$(docker inspect -f '{{.State.Pid}}' whisper 2>/dev/null); pidtijd=$nu; fi
  if [ -n "$pid" ] && [ "$pid" != 0 ] && nsenter -t "$pid" -n ss -Htn state established '( sport = :10300 )' | grep -q .; then
    tot=$((nu + 60))
    [ $gepauzeerd = 1 ] || { systemctl kill -s STOP haard-dag.service 2>/dev/null; gepauzeerd=1; }
  elif [ $gepauzeerd = 1 ] && [ $nu -ge $tot ]; then
    systemctl kill -s CONT haard-dag.service 2>/dev/null; gepauzeerd=0
  fi
  sleep 0.25
done
