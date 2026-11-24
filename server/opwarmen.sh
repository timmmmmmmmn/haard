#!/bin/sh
# Na het opstarten het taalmodel meteen in het geheugen laden: dan is de eerste vraag ook snel
. ~/haard/.env
until curl -sf http://127.0.0.1:11434/api/tags >/dev/null; do sleep 5; done
curl -s http://127.0.0.1:11434/api/generate -d "{\"model\":\"$MODEL\",\"prompt\":\"\",\"keep_alive\":-1}" >/dev/null
