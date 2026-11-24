#!/bin/sh
# Zware klussen alleen overdag (08:30–22:00): eerst het model, dan het wekwoord trainen.
u=$(date +%H); [ "$u" -ge 10 ] && [ "$u" -lt 22 ] || exit 0
cd ~/haard
docker exec ollama ollama list | grep -q qwen3:30b-a3b || sh model.sh >> model.log 2>&1
[ -f wekwoord/uit/hey_fire.onnx ] || bash wekwoord/train.sh >> wekwoord/train.log 2>&1
