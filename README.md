# Haard

Een eigen vervanger voor de Google Nest Hub: een Fire HD 8-tablet die luistert naar **"Hey Fire"**,
vragen beantwoordt met een **lokale AI** en het huis bedient via **Home Assistant**.
Alles draait thuis, zonder Google of Amazon.

```
 Fire HD 8 (app Haard)                Home Assistant (HA OS)              Dell XPS 8700 (Arch, alleen CPU)
 ┌───────────────────────┐   wss    ┌──────────────────────────┐  LAN  ┌──────────────────────────────┐
 │ wekwoord op de tablet │ ───────► │ Assist-pijplijn          │ ────► │ Whisper   spraak → tekst     │
 │ klok · weer · foto's  │          │  • eigen afhandeling     │       │ Ollama    taalmodel (MoE)    │
 │ muziek · nachtstand   │ ◄─────── │    ("lamp aan": direct)  │ ◄──── │ Piper     tekst → spraak     │
 └───────────────────────┘   tts    │  • anders het taalmodel  │       │ Open WebUI → ai.timvanbergen │
                                    └──────────────────────────┘       └──────────────────────────────┘
```

## Onderdelen

| Map | Wat |
| --- | --- |
| `android/` | De app voor de tablet (Kotlin + een pagina in `assets/www`). |
| `server/` | `docker-compose.yml` + `install.sh` voor de XPS: Ollama, Open WebUI, Whisper, Piper, Cloudflare Tunnel. |
| `server/wekwoord/` | `train.sh` traint het wekwoord "Hey Fire" / "OK Fire" (openWakeWord). |

## Hoe een vraag loopt

1. De tablet hoort zelf het wekwoord (openWakeWord, ~20 ms rekenen per 80 ms geluid). Pas daarna gaat er geluid naar Home Assistant.
2. Home Assistant zet het geluid met Whisper om naar tekst en hoort zelf wanneer je klaar bent met praten.
3. Muziek en volume regelt de tablet meteen zelf, zoals "volgende", "pauze", "harder" en "volume op 4".
4. De rest gaat naar Home Assistant. Gewone commando's ("doe de lamp in Tims kamer aan") handelt HA zelf af, binnen een seconde. Echte vragen gaan naar het taalmodel op de XPS. Het antwoord verschijnt woord voor woord en Piper leest het voor.
5. Stelt de assistent een wedervraag, dan luistert de tablet meteen weer, zonder wekwoord.

## De tablet klaarmaken (Fire HD 8, 2020)

1. Zet in Instellingen → Alexa **handsfree uit**, anders vechten Alexa en Haard om de microfoon.
2. Instellingen → Beveiliging → **Apps van onbekende bronnen** toestaan, en installeer `Haard.apk` (GitHub-release).
3. Open Haard. Geef toestemming voor de microfoon en de foto's, en tik dan op **Inloggen**. Je logt in op het eigen inlogscherm van Home Assistant.
4. Muziek: tik in de instellingen op **Toegang geven** bij *Bediening van Spotify*. Gaat dat menu op Fire OS niet open, dan kan het via adb:
   `adb shell cmd notification allow_listener nl.timvanbergen.haard/nl.timvanbergen.haard.MediaLuisteraar`
5. Fotolijst: zet foto's in **Pictures/Haard** op de tablet.
6. Wil je dat Haard altijd het startscherm is? Dat kan alleen via adb, omdat Fire OS geen andere launchers toestaat. Dat is optioneel.

## Server (XPS 8700)

Op een verse Arch:

```sh
cd server && ./install.sh
```

Daarna in Home Assistant:

- **Wyoming** → `XPS-IP:10300` (Whisper) en `XPS-IP:10200` (Piper)
- **Ollama** → `http://XPS-IP:11434`, het model uit `.env`, en "Assist" aan zodat het apparaten mag bedienen
- Een spraakassistent **Haard** met die drie, in het Nederlands, met *Voorkeur voor lokaal afhandelen* aan
- Kies in de app (Instellingen → Spraak) de assistent "Haard"

### Waarom dit model?

Op DDR3 bepaalt vooral de geheugensnelheid hoe snel een model praat, niet de CPU. Een MoE-model zoals
Qwen3-30B-A3B is slim als een 30B-model, maar per woord rekent het met ~3B. Op de i7-4770 haalt het
naar verwachting zo'n 6-9 woorden per seconde. Een gewoon 8B-model haalt ~4 en is minder slim.

## Wekwoord

Tot "Hey Fire" getraind is, luistert Haard naar **"Hey Jarvis"** (kant-en-klaar model van openWakeWord).
Trainen gebeurt op de XPS met `server/wekwoord/train.sh`. Dat duurt een paar uur. Het model
`hey_fire.onnx` komt daarna in de app.
