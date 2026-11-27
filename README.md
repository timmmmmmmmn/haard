# Haard

Een eigen vervanger voor de Google Nest Hub: een Fire HD 8-tablet die luistert naar **"Hey Fire"**,
het huis bedient via **Home Assistant** en moeilijke vragen aan **Gemini** stelt.
Geen server nodig: verstaan en spreken gebeurt op de tablet zelf.

```
 Fire HD 8 (app Haard)                                  Home Assistant (HA OS)
 ┌─────────────────────────────────────────┐   wss    ┌──────────────────────────────┐
 │ wekwoord        openWakeWord (eigen)     │ ───────► │ eigen afhandeling (geen AI): │
 │ commando's      Vosk, vaste zinnen       │          │ lampen, timers, tijd, …      │
 │ stem            Piper "Ronnie"           │ ◄─────── │ ESPHome-apparaat "Haard"     │
 │ klok · weer · foto's · muziek · agenda   │          └──────────────────────────────┘
 └─────────────────────────────────────────┘
          │ alleen na het wekwoord, als het geen vast commando is
          ▼
   Gemini (Google): uitschrijven wat je zei + antwoord op echte vragen (met Google Zoeken)
```

De XPS-server (Whisper, Ollama, Piper, Speech-to-Phrase) in `server/` wordt op dit moment niet gebruikt.

## Onderdelen

| Map | Wat |
| --- | --- |
| `android/` | De app voor de tablet (Kotlin + een pagina in `assets/www`). |
| `server/` | `docker-compose.yml` + `install.sh` voor de XPS: Ollama, Open WebUI, Whisper, Piper, Cloudflare Tunnel. |
| `server/wekwoord/` | `train.sh` traint het wekwoord "Hey Fire" / "OK Fire" (openWakeWord). |

## Wat Haard kan

- **Klok in de geest van de Nest Hub** (Figtree) op een lucht die het weer van nu laat zien: zon, wolken, regen, sneeuw, mist, onweer, dag/schemer/nacht met maan en sterren. Met kerst lichtjes en sneeuw, van 27 december t/m 1 januari vuurwerk.
- **Muziek**: wat er speelt (op de tablet zelf of via Spotify in Home Assistant) met de hoes als achtergrond; "speel Frenna" vraagt op welk apparaat en start het via Music Assistant.
- **Agenda** (eigen CalDAV-agenda via HA): volgende afspraak onder de klok, "wat staat er morgen op mijn agenda?", "zet morgen om half vier tandarts in mijn agenda".
- **Apparaat in Home Assistant** (ESPHome-API): spraakassistent met kamer, timers, omroepen, mediaspeler en een schakelaar "Microfoon uit".
- **Stille tijd** (23–8, weekend tot 10): geen enkel geluid, Niet storen op totale stilte, een heel donkere rode klok; timers en omroepen alleen op het scherm.
- **Zuinig**: het wekwoord rekent niet als het stil is, de lucht tekent alleen zo vaak als nodig, de helderheid volgt de lichtsensor.

## Hoe een vraag loopt

1. De tablet hoort zelf het wekwoord "Hey Fire" / "Oké Fire" (openWakeWord, eigen model).
2. Daarna luistert **Vosk** op de tablet mee, met een lijst vaste commando's die Haard zelf maakt uit wat Home Assistant aan de assistent laat zien (namen, aliassen, kamers): "doe het licht uit", "zet mijn lamp op rood", "zet een timer van tien minuten", "volgend nummer". De tablet hoort zelf wanneer je klaar bent met praten (~0,5 s stilte).
3. Een vast commando gaat meteen naar de eigen afhandeling van Home Assistant (met het apparaat erbij, dus de kamer); gelukt = een belletje. Dit werkt ook zonder internet.
4. Anders schrijft **Gemini** uit wat je zei. Muziek, volume, agenda en weer regelt de tablet zelf; opdrachten gaan naar Home Assistant; wat HA niet kent is een vraag voor Gemini, met de echte waarden uit huis en Google Zoeken.
5. Het antwoord spreekt een stem van **Gemini** uit (Charon, als stroom: geluid na ~0,5 s). Zonder internet of boven de gratis limiet (3 per minuut per model) neemt **Piper** (stem Ronnie) het over, op de tablet zelf; die is op de Fire wel ~3x trager dan hij praat. Omroepen uit Home Assistant spreekt Haard ook zelf uit.

De spraakmodellen (Vosk + Piper, ~140 MB) haalt de app één keer op uit de release `modellen-1`.
Zonder Gemini-sleutel werken alleen de vaste commando's. Een sleutel maak je gratis op
[aistudio.google.com/apikey](https://aistudio.google.com/apikey) en plak je in Instellingen → Spraak.

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
