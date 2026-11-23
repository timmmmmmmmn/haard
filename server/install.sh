#!/usr/bin/env bash
# Zet de Haard-server op een verse Arch-installatie: Docker, de containers en het model.
# Draai als gewone gebruiker met sudo, vanuit deze map:  ./install.sh
set -euo pipefail
cd "$(dirname "$0")"

stap() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }

stap "Pakketten"
sudo pacman -Syu --needed --noconfirm docker docker-compose curl jq cpupower

stap "Docker aanzetten"
sudo systemctl enable --now docker
if ! id -nG "$USER" | grep -qw docker; then
  sudo usermod -aG docker "$USER"
  echo "Je bent aan de groep docker toegevoegd; dit script gebruikt voorlopig sudo voor docker."
fi
DOCKER="docker"; docker info >/dev/null 2>&1 || DOCKER="sudo docker"

stap "CPU op volle snelheid (scheelt merkbaar bij het rekenen)"
sudo sed -i "s/^#\?governor=.*/governor='performance'/" /etc/default/cpupower 2>/dev/null || true
sudo systemctl enable --now cpupower 2>/dev/null || sudo cpupower frequency-set -g performance || true

[ -f .env ] || cp .env.example .env
set -a; . ./.env; set +a

stap "Containers starten"
PROFIEL=""; [ -n "${TUNNEL_TOKEN:-}" ] && PROFIEL="--profile tunnel"
$DOCKER compose $PROFIEL pull
$DOCKER compose $PROFIEL up -d

stap "Model downloaden: $MODEL (kan even duren, ~18 GB)"
until curl -sf http://127.0.0.1:11434/api/version >/dev/null; do sleep 2; done
$DOCKER exec ollama ollama pull "$MODEL"

stap "Model één keer laden zodat het meteen klaarstaat"
curl -s http://127.0.0.1:11434/api/generate -d "{\"model\":\"$MODEL\",\"prompt\":\"hoi\",\"stream\":false,\"keep_alive\":-1}" | jq -r '.response' | head -c 200; echo

IP=$(ip -4 route get 1.1.1.1 | awk '{for(i=1;i<=NF;i++) if($i=="src") print $(i+1)}')
cat <<EOF

Klaar. In Home Assistant (Instellingen → Apparaten en diensten → Integratie toevoegen):
  • Wyoming Protocol  → host $IP, poort 10300  (Whisper, spraak → tekst)
  • Wyoming Protocol  → host $IP, poort 10200  (Piper, tekst → spraak)
  • Ollama            → http://$IP:11434, model $MODEL, "Assist" aan
Daarna een spraakassistent "Haard" maken met die drie (Instellingen → Spraakassistenten),
taal Nederlands en "Voorkeur voor lokaal afhandelen" aan.

Chatsite: https://ai.timvanbergen.nl (via de tunnel). Het eerste account dat je daar maakt is beheerder.
EOF
