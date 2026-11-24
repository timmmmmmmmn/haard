package nl.timvanbergen.haard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject

/**
 * Eén vraag-en-antwoord via de spraakpijplijn van Home Assistant (Assist):
 *  1. luisteren: het geluid gaat naar HA (Whisper op de server), HA hoort zelf wanneer je klaar bent;
 *  2. de tekst eerst zelf bekijken: muziek en volume regelt de tablet direct;
 *  3. anders de tekst naar HA (eigen afhandeling of het taalmodel) en het antwoord (Piper) afspelen.
 * Alles gebeurt op één eigen draad, zodat er nooit twee dingen tegelijk aan de toestand zitten.
 */
class Assistent(private val ctx: Context) {

    enum class Fase { RUST, LUISTEREN, DENKEN, SPREKEN }

    private val draad = HandlerThread("assistent").apply { start() }
    private val h = Handler(draad.looper)
    private val audio = ctx.getSystemService(AudioManager::class.java)

    @Volatile var fase = Fase.RUST
        private set

    private var beurt = 0 // elke nieuwe vraag krijgt een nummer; late berichten van een oude vraag tellen niet
    private var runId = -1
    private var handlerId = -1
    private val buffer = ArrayList<ByteArray>()
    private var spraakGehoord = false
    private var gestopt = false
    private var gesprek: String? = null
    private var gesprekTijd = 0L
    private var speler: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var antwoord = StringBuilder()
    private var doorpraten = false
    private var wachtOpApparaat: String? = null // "speel Frenna" → wacht op het antwoord op "Op welk apparaat?"
    private var opnieuwUitspreken = false // het antwoord van HA bevatte Engelse toestanden ("off")

    // Snelle route: hetzelfde geluid gaat ook naar Speech-to-Phrase (pijplijn "Haard snel"), dat vaste
    // commando's in een fractie van een seconde herkent. Wie het eerst iets bruikbaars heeft, wint;
    // lukt de snelle route niet, dan gaat het verder met wat Whisper hoorde.
    private var snelRunId = -1
    private var snelHandler = -1
    private val snelBuffer = ArrayList<ByteArray>()
    private var besloten = false        // er is al gekozen welke tekst we gebruiken
    private var snelFaalde = false      // snelle route lukte niet: Whisper afwachten
    private var whisperTekst: String? = null

    /** Wekwoord gehoord of op de microfoon getikt. */
    fun start() = h.post { begin() }

    /** Weg met de vraag of het antwoord (tik op het scherm, of "stop"). */
    fun stop() = h.post {
        beurt++
        stopSpeler()
        if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
        runId = -1
        klaar(null)
    }

    /** 80 ms geluid van de microfoon (16 kHz, 16 bit, mono); alleen nodig tijdens het luisteren. */
    fun geluid(pcm: ByteArray) {
        if (fase != Fase.LUISTEREN) return
        h.post { stuurGeluid(pcm) }
    }

    private fun begin() {
        if (!HomeAssistant.ingelogd) {
            Geluid.fout()
            status("fout", "Haard is nog niet verbonden met Home Assistant. Open de instellingen om in te loggen.")
            return
        }
        stopSpeler()
        if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
        val mijn = ++beurt
        fase = Fase.LUISTEREN
        buffer.clear()
        handlerId = -1
        spraakGehoord = false
        gestopt = false
        antwoord = StringBuilder()
        doorpraten = false
        opnieuwUitspreken = false
        // Een gesprek loopt door als je binnen vijf minuten weer iets vraagt ("en morgen?")
        if (System.currentTimeMillis() - gesprekTijd > 5 * 60_000) gesprek = null
        vraagFocus()
        Geluid.luister()
        status("luisteren")

        val msg = pijplijn("stt", "stt").put("input", JSONObject().put("sample_rate", 16000))
        runId = HomeAssistant.Socket.stuur(msg) { ev -> h.post { if (mijn == beurt) bijLuisteren(ev) } }
        besloten = false; snelFaalde = false; whisperTekst = null
        snelHandler = -1; snelBuffer.clear(); snelRunId = -1
        SnellePijplijn.id?.let { snel ->
            val m = pijplijn("stt", "stt", snel).put("input", JSONObject().put("sample_rate", 16000))
            snelRunId = HomeAssistant.Socket.stuur(m) { ev -> h.post { if (mijn == beurt) bijSnel(ev) } }
        }

        // Geen antwoord van Home Assistant: dan niet blijven hangen
        h.postDelayed({
            if (mijn == beurt && handlerId < 0 && fase != Fase.RUST) {
                HomeAssistant.Socket.opnieuw() // waarschijnlijk een dode verbinding: niet op de ping wachten
                mislukt("verbinding")
            }
        }, 8_000)
        // Niemand zegt iets: na 6 s stoppen. Langer dan 15 s praten: ook stoppen.
        h.postDelayed({ if (mijn == beurt && fase == Fase.LUISTEREN && !spraakGehoord) eindeGeluid() }, 6_000)
        h.postDelayed({ if (mijn == beurt && fase == Fase.LUISTEREN) eindeGeluid() }, 15_000)
    }

    private fun pijplijn(van: String, tot: String, welke: String? = null): JSONObject {
        val m = JSONObject().put("type", "assist_pipeline/run").put("start_stage", van).put("end_stage", tot)
        (welke ?: Instellingen.pipeline.takeIf { it.isNotEmpty() })?.let { m.put("pipeline", it) }
        gesprek?.let { m.put("conversation_id", it) }
        // Het apparaat in Home Assistant: dan weet hij de kamer ("doe het licht aan") en kunnen timers
        Esphome.apparaat?.let { m.put("device_id", it) }
        return m
    }

    private fun stuurGeluid(pcm: ByteArray) {
        if (fase != Fase.LUISTEREN || gestopt) return
        if (snelRunId >= 0) { if (snelHandler < 0) snelBuffer.add(pcm) else HomeAssistant.Socket.binair(byteArrayOf(snelHandler.toByte()) + pcm) }
        if (handlerId < 0) {
            buffer.add(pcm) // HA is er nog niet klaar voor; zo gaat het begin van je zin niet verloren
            return
        }
        HomeAssistant.Socket.binair(byteArrayOf(handlerId.toByte()) + pcm)
    }

    private fun eindeGeluid() {
        if (gestopt) return
        gestopt = true
        if (handlerId >= 0) HomeAssistant.Socket.binair(byteArrayOf(handlerId.toByte()))
        if (snelHandler >= 0) HomeAssistant.Socket.binair(byteArrayOf(snelHandler.toByte()))
        if (fase == Fase.LUISTEREN) {
            fase = Fase.DENKEN
            status("denken", stap = "verstaan")
        }
    }

    private fun bijLuisteren(m: JSONObject) {
        if (m.optString("type") == "result") {
            if (!m.optBoolean("success", true)) mislukt(m.optJSONObject("error")?.optString("message"))
            return
        }
        val ev = m.optJSONObject("event") ?: return
        val data = ev.optJSONObject("data") ?: JSONObject()
        when (ev.optString("type")) {
            "run-start" -> {
                handlerId = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                for (b in buffer) HomeAssistant.Socket.binair(byteArrayOf(handlerId.toByte()) + b)
                buffer.clear()
            }
            "stt-vad-start" -> spraakGehoord = true
            "stt-vad-end" -> eindeGeluid()
            "stt-end" -> {
                eindeGeluid()
                val tekst = data.optJSONObject("stt_output")?.optString("text")?.trim().orEmpty()
                HomeAssistant.Socket.vergeet(runId)
                runId = -1
                // Veiligheid: alleen iets doen als Home Assistant echt spraak hoorde, en nooit op
                // de bekende "spook"-zinnen die Whisper verzint bij stilte of ruis.
                when {
                    besloten && !snelFaalde -> whisperTekst = tekst // de snelle route is al bezig; bewaren voor als die faalt
                    besloten && snelFaalde -> { whisperTekst = tekst; naWhisper() }
                    tekst.isEmpty() -> niksVerstaan()
                    !spraakGehoord || Opdrachten.isSpook(tekst, eenWoordMag = wachtOpApparaat != null) -> stilWeg()
                    else -> { besloten = true; stopSnel(); verwerk(tekst) }
                }
            }
            "error" -> {
                if (data.optString("code") == "stt-no-text-recognized") niksVerstaan()
                else mislukt(data.optString("message"))
            }
        }
    }

    private fun stopSnel() {
        if (snelRunId >= 0) HomeAssistant.Socket.vergeet(snelRunId)
        snelRunId = -1
    }

    private fun bijSnel(m: JSONObject) {
        val ev = m.optJSONObject("event") ?: return
        val data = ev.optJSONObject("data") ?: JSONObject()
        when (ev.optString("type")) {
            "run-start" -> {
                snelHandler = data.optJSONObject("runner_data")?.optInt("stt_binary_handler_id", -1) ?: -1
                for (b in snelBuffer) HomeAssistant.Socket.binair(byteArrayOf(snelHandler.toByte()) + b)
                snelBuffer.clear()
            }
            "stt-vad-start" -> spraakGehoord = true
            "stt-vad-end" -> eindeGeluid()
            "stt-end" -> {
                val tekst = data.optJSONObject("stt_output")?.optString("text")?.trim().orEmpty()
                stopSnel()
                // Alleen een herkend commando telt; leeg = geen bekend commando, dan wacht Haard op Whisper
                if (besloten || tekst.isEmpty() || !spraakGehoord || Opdrachten.isSpook(tekst, eenWoordMag = true)) return
                if (wachtOpApparaat != null) return // antwoord op "op welk apparaat?": dat moet Whisper verstaan
                besloten = true
                eindeGeluid()
                snelUitvoeren(tekst)
            }
            "error" -> stopSnel()
        }
    }

    /** Een commando van Speech-to-Phrase: de tablet zelf of Home Assistant zonder taalmodel. */
    private fun snelUitvoeren(tekst: String) {
        status("denken", jij = tekst, stap = "uitvoeren")
        if (Opdrachten.herken(tekst) != null) {
            if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
            runId = -1
            verwerk(tekst)
            return
        }
        val mijn = beurt
        val snel = SnellePijplijn.id ?: return naWhisper()
        val msg = pijplijn("intent", "intent", snel).put("input", JSONObject().put("text", tekst))
        HomeAssistant.Socket.stuur(msg) { r -> h.post {
            if (mijn != beurt) return@post
            val ev = r.optJSONObject("event") ?: return@post
            if (ev.optString("type") == "error") return@post naWhisper()
            if (ev.optString("type") != "intent-end") return@post
            val out = ev.optJSONObject("data")?.optJSONObject("intent_output") ?: return@post naWhisper()
            val resp = out.optJSONObject("response") ?: JSONObject()
            val soort = resp.optString("response_type")
            val zin = Opdrachten.vertaalStaat(resp.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty())
            when (soort) {
                // Gedaan (lamp, timer): meteen een belletje, geen voorgelezen "Aangezet"; Whisper is niet meer nodig
                "action_done" -> {
                    if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
                    runId = -1
                    Geluid.klaar()
                    klaar(zin.ifEmpty { "Gedaan" }, tekst)
                }
                // Een antwoord (hoe laat is het): voorlezen
                "query_answer" -> {
                    if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
                    runId = -1
                    antwoord = StringBuilder(zin)
                    zeg(tekst, "uitvoeren") { zin }
                }
                else -> naWhisper()
            }
        } }
    }

    /** De snelle route lukte niet: verder met de tekst van Whisper (nu of zodra die er is). */
    private fun naWhisper() {
        snelFaalde = true
        val w = whisperTekst ?: run { status("denken", stap = "verstaan"); return }
        whisperTekst = null
        when {
            w.isEmpty() -> niksVerstaan()
            Opdrachten.isSpook(w, eenWoordMag = wachtOpApparaat != null) -> stilWeg()
            else -> verwerk(w)
        }
    }

    /** Er was niets echts te horen: zonder geluid en zonder iets te doen weer verdwijnen. */
    private fun stilWeg() {
        klaar(null)
        status("weg")
    }

    private fun niksVerstaan() {
        if (Stilte.nu) return stilWeg() // 's nachts geen "niet verstaan" op het scherm

        Geluid.fout()
        klaar("Ik heb je niet verstaan.")
    }

    /** Eerst kijken of de tablet het zelf kan; anders door naar Home Assistant. */
    private fun verwerk(tekst: String) {
        status("denken", jij = tekst, stap = "uitvoeren")
        val zelf = Opdrachten.herken(tekst)
        if (zelf != null) {
            wachtOpApparaat = null
            when (zelf.first) {
                "stop" -> Esphome.stopBel()
                "volume" -> Media.zetVolume(ctx, zelf.second.toInt())
                else -> Media.actie(zelf.first)
            }
            Geluid.klaar()
            fase = Fase.RUST
            laatFocusLos()
            status("klaar", jij = tekst, antwoord = zelf.second.takeIf { zelf.first != "volume" } ?: "Volume op ${zelf.second}")
            return
        }
        wachtOpApparaat?.let { zoek -> wachtOpApparaat = null; return speelMuziek(zoek, tekst, tekst) }
        Opdrachten.speelVerzoek(tekst)?.let { (zoek, waar) -> return speelMuziek(zoek, waar, tekst) }
        Agenda.herken(tekst)?.let { v -> return zeg(tekst, "agenda") { Agenda.antwoord(v) } }
        Weer.herken(tekst)?.let { v -> return zeg(tekst, "weer") { Weer.antwoord(v) } }
        val mijn = beurt
        val msg = pijplijn("intent", "tts").put("input", JSONObject().put("text", Opdrachten.versimpel(tekst)))
        runId = HomeAssistant.Socket.stuur(msg) { ev -> h.post { if (mijn == beurt) bijAntwoord(ev, tekst) } }
        // Home Assistant zelf antwoordt binnen een seconde; duurt het langer, dan denkt de AI na: laten zien
        h.postDelayed({ if (mijn == beurt && fase == Fase.DENKEN && antwoord.isEmpty()) status("denken", jij = tekst, stap = "ai") }, 900)
        // Het taalmodel draait op een CPU en mag even nadenken, maar niet eindeloos
        h.postDelayed({ if (mijn == beurt && fase == Fase.DENKEN) mislukt("Het antwoord duurde te lang.") }, 120_000)
    }

    /** Een antwoord dat Haard zelf maakt (zoals uit de agenda) uitspreken met de stem van de assistent. */
    private fun zeg(jij: String, stap: String = "zoeken", maak: () -> String) {
        status("denken", jij = jij, stap = stap)
        val mijn = beurt
        Thread {
            val tekst = runCatching(maak).getOrElse { "Er ging iets mis." }
            val url = if (Stilte.nu) null else Muziek.stemUrl(tekst)
            h.post {
                if (mijn != beurt) return@post
                antwoord = StringBuilder(tekst)
                if (url == null) klaar(tekst, jij) else spreek(Instellingen.haUrl + url, jij)
            }
        }.start()
    }

    /** "Speel Frenna (op de Sonos)": via Music Assistant; zonder apparaat vraagt Haard eerst waar. */
    private fun speelMuziek(zoek: String, waar: String?, jij: String) {
        if (Stilte.nu) return klaar("'s Nachts zet ik geen muziek aan.", jij)
        status("denken", jij = jij, stap = "muziek")
        val mijn = beurt
        Thread {
            val speler = waar?.let { Muziek.kies(it) }
            if (speler != null) {
                val fout = Muziek.speel(zoek, speler)
                h.post { if (mijn == beurt) { if (fout == null) Geluid.klaar(); klaar(fout ?: "Ik speel $zoek op ${speler.naam}.", jij) } }
                return@Thread
            }
            val namen = Muziek.spelers().map { it.naam }
            if (namen.isEmpty()) { h.post { if (mijn == beurt) klaar("Ik vind geen speakers in Music Assistant.", jij) }; return@Thread }
            if (waar != null) { // al gevraagd en niet herkend: niet blijven doorvragen
                h.post { if (mijn == beurt) klaar("Dat apparaat ken ik niet. Kies uit: ${namen.joinToString(", ")}.", jij) }
                return@Thread
            }
            val url = Muziek.stemUrl("Op welk apparaat?")
            h.post {
                if (mijn != beurt) return@post
                wachtOpApparaat = zoek
                doorpraten = true
                antwoord = StringBuilder("Op welk apparaat? " + namen.take(4).joinToString(", ") + (if (namen.size > 4) " …" else ""))
                if (url == null) naSpreken(jij) else spreek(Instellingen.haUrl + url, jij)
            }
        }.start()
    }

    private fun bijAntwoord(m: JSONObject, jij: String) {
        if (m.optString("type") == "result") {
            if (!m.optBoolean("success", true)) mislukt(m.optJSONObject("error")?.optString("message"))
            return
        }
        val ev = m.optJSONObject("event") ?: return
        val data = ev.optJSONObject("data") ?: JSONObject()
        when (ev.optString("type")) {
            // Het taalmodel schrijft het antwoord stukje voor stukje: dan zie je het al verschijnen
            "intent-progress" -> {
                val stuk = data.optJSONObject("chat_log_delta")?.optString("content").orEmpty()
                if (stuk.isNotEmpty()) {
                    antwoord.append(stuk)
                    status("denken", jij = jij, antwoord = antwoord.toString(), stap = "ai")
                }
            }
            "intent-end" -> {
                val out = data.optJSONObject("intent_output") ?: JSONObject()
                out.optString("conversation_id").takeIf { it.isNotEmpty() }?.let { gesprek = it }
                gesprekTijd = System.currentTimeMillis()
                doorpraten = out.optBoolean("continue_conversation", false)
                val ruw = out.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
                val tekst = Opdrachten.vertaalStaat(ruw)
                // Gedaan door Home Assistant zelf (lamp, timer): niet wachten op een voorgelezen "Aangezet"
                if (data.optBoolean("processed_locally") && out.optJSONObject("response")?.optString("response_type") == "action_done" && !doorpraten) {
                    Geluid.klaar()
                    klaar(tekst.ifEmpty { "Gedaan" }, jij)
                    return
                }
                opnieuwUitspreken = tekst != ruw
                antwoord = StringBuilder(tekst)
                status("denken", jij = jij, antwoord = tekst)
            }
            "tts-end" -> {
                val url = data.optJSONObject("tts_output")?.optString("url").orEmpty()
                HomeAssistant.Socket.vergeet(runId)
                runId = -1
                if (opnieuwUitspreken) zeg(jij, "uitvoeren") { antwoord.toString() } // HA las het Engels voor: met de vertaling opnieuw
                else if (url.isEmpty()) klaar(antwoord.toString(), jij) else spreek(if (url.startsWith("/")) Instellingen.haUrl + url else url, jij)
            }
            "run-end" -> if (fase == Fase.DENKEN) klaar(antwoord.toString(), jij)
            "error" -> mislukt(data.optString("message"))
        }
    }

    private fun spreek(url: String, jij: String) {
        // Stille tijd: niets voorlezen, het antwoord blijft alleen op het scherm staan
        if (Stilte.nu) {
            naSpreken(jij)
            return
        }
        fase = Fase.SPREKEN
        status("spreken", jij = jij, antwoord = antwoord.toString())
        val mijn = beurt
        val p = MediaPlayer()
        speler = p
        try {
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            p.setDataSource(url)
            p.setOnPreparedListener { it.start() }
            p.setOnCompletionListener { h.post { if (mijn == beurt) naSpreken(jij) } }
            p.setOnErrorListener { _, _, _ -> h.post { if (mijn == beurt) klaar(antwoord.toString(), jij) }; true }
            p.prepareAsync()
        } catch (_: Exception) {
            klaar(antwoord.toString(), jij)
        }
    }

    private fun naSpreken(jij: String) {
        stopSpeler()
        // Stelde de assistent een wedervraag? Dan meteen weer luisteren, zonder wekwoord.
        // Wedervraag: meteen weer luisteren, maar 's nachts nooit vanzelf
        if (doorpraten && !Stilte.nu) begin() else klaar(antwoord.toString(), jij)
    }

    private fun mislukt(melding: String?) {
        Geluid.fout()
        klaar(when {
            melding.isNullOrBlank() -> "Er ging iets mis."
            melding.contains("verbinding", true) || melding.contains("verbroken", true) -> "Geen verbinding met Home Assistant."
            melding.endsWith(".") -> melding
            else -> "Er ging iets mis: $melding"
        })
    }

    private fun klaar(antwoordTekst: String?, jij: String? = null) {
        wachtOpApparaat = null
        val was = fase
        fase = Fase.RUST
        beurt++ // late berichten van deze vraag tellen niet meer
        if (runId >= 0) HomeAssistant.Socket.vergeet(runId)
        runId = -1
        laatFocusLos()
        if (was != Fase.RUST || antwoordTekst != null) status("klaar", jij = jij, antwoord = antwoordTekst)
    }

    private fun stopSpeler() {
        speler?.let { runCatching { it.stop() }; it.release() }
        speler = null
    }

    /** Muziek gaat even op pauze terwijl je praat en Haard antwoordt. */
    private fun vraagFocus() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .build()
        audio.requestAudioFocus(f)
        focus = f
    }

    private fun laatFocusLos() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun status(fase: String, tekst: String? = null, jij: String? = null, antwoord: String? = null, stap: String? = null) {
        val o = JSONObject().put("fase", fase)
        stap?.let { o.put("stap", it) }
        jij?.let { o.put("jij", it) }
        (antwoord ?: tekst)?.let { o.put("antwoord", it) }
        Bus.stuur("assist", o)
    }
}

/** Opdrachten die de tablet zelf afhandelt, zonder Home Assistant: muziek en volume. */
object Opdrachten {

    /** Geeft (actie, wat Haard laat zien) terug, of null als HA het moet doen. Bij "volume" is het tweede het getal. */
    // Zinnen die Whisper verzint bij stilte of ruis (geleerd van ondertitelde video's)
    private val SPOOK = listOf(
        "ondertiteld", "ondertiteling", "ondertitels", "bedankt voor het kijken", "bedankt voor het luisteren",
        "dank je wel voor het kijken", "tot de volgende keer", "tot de volgende video", "abonneer", "amara",
        "tv gelderland", "omroep", "applaus", "♪", "[", "*", "lacht", "thank you", "subtitles",
    )

    /** True als de "zin" vrijwel zeker niet van iemand komt: dan doet Haard niets. */
    fun isSpook(zin: String, eenWoordMag: Boolean = false): Boolean {
        val t = zin.lowercase().trim()
        if (SPOOK.any { t.contains(it) }) return true
        val woorden = t.replace(Regex("[^\\p{L}0-9 ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        // Eén los woord is alleen goed als het een bekend commando is ("pauze", "volgende", "stop")
        return woorden.isEmpty() || (woorden.size == 1 && !eenWoordMag && herken(t) == null)
    }

    /**
     * Herschrijft zinnen die de ingebouwde zinnen van Home Assistant net niet snappen, zodat het daar
     * snel en zeker gebeurt in plaats van bij het taalmodel: "zet mijn lamp weer aan" → "zet mijn lamp aan",
     * "doe mijn lamp aan en zet hem op rood" → "zet mijn lamp op rood" (een kleur kiezen zet de lamp ook aan).
     */
    fun versimpel(zin: String): String {
        var t = zin.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            .replace(Regex(",? ?(alsjeblieft|alstublieft|graag)$", RegexOption.IGNORE_CASE), "")
        // Alleen bij opdrachten ("... weer aan"), want "weer" is ook het weer
        if (Regex("^(doe|zet|maak) .+ (aan|uit)$", RegexOption.IGNORE_CASE).matches(t))
            t = t.replace(Regex("\\b(weer|nog even|even|maar|eens) ", RegexOption.IGNORE_CASE), "")
        Regex("^(?:doe|zet) (.+?) aan,? en (?:doe|zet|maak) (?:hem|haar|die|het|deze) (?:op |in het |)([\\p{L}]+)$", RegexOption.IGNORE_CASE)
            .find(t)?.let { return "zet ${it.groupValues[1].replace(Regex(" (weer|nog even|even|maar)$"), "")} op ${it.groupValues[2]}" }
        // Home Assistant kent geen "feller"/"zachter": vaste stappen die altijd hetzelfde doen
        Regex("^(?:maak|zet|doe) (.+?) (?:wat |een beetje |iets |nog )?(feller|lichter|helderder|zachter|donkerder|minder fel|gedimd)$", RegexOption.IGNORE_CASE)
            .find(t)?.let { return "zet ${it.groupValues[1]} op ${if (it.groupValues[2].lowercase() in setOf("feller", "lichter", "helderder")) 100 else 30} procent" }
        Regex("^dim (.+)$", RegexOption.IGNORE_CASE).find(t)?.let { return "zet ${it.groupValues[1]} op 30 procent" }
        return t
    }

    /** Home Assistant zegt soms de Engelse toestand ("Nee, die is off"): naar gewoon Nederlands. */
    fun vertaalStaat(zin: String): String {
        val woorden = mapOf("off" to "uit", "on" to "aan", "unavailable" to "niet bereikbaar", "unknown" to "onbekend",
            "closed" to "dicht", "not_home" to "niet thuis", "home" to "thuis", "playing" to "aan het spelen", "paused" to "gepauzeerd",
            "idle" to "niet actief", "standby" to "stand-by", "heat" to "verwarmen", "cool" to "koelen", "locked" to "op slot", "unlocked" to "open")
        return Regex("\\b(not_home|unavailable|unknown|closed|playing|paused|standby|unlocked|locked|idle|heat|cool|home|off|on)\\b")
            .replace(zin) { woorden[it.value] ?: it.value }
    }

    /** "Speel Frenna", "speel het album Ye op de Sonos" → (wat, waar). Niet "speel verder": dat doet [herken]. */
    fun speelVerzoek(zin: String): Pair<String, String?>? {
        val t = zin.trim().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")
            .replace(Regex("^speelmuziek", RegexOption.IGNORE_CASE), "speel muziek") // Whisper plakt het soms aan elkaar
        val m = Regex("^(?:speel|spel|speelt|draai) (?:eens |even |maar )?(?:wat |iets )?(?:van )?(?:muziek van |nummers van |het nummer |het liedje |het album |de playlist |de afspeellijst |nummer |liedje |album |playlist |afspeellijst )?(.+?)(?: af)?(?: op (?:de |het )?(.+?))?$",
            RegexOption.IGNORE_CASE).find(t) ?: return null
        val wat = m.groupValues[1].trim()
        if (wat.isEmpty() || Regex("^(muziek|verder|door)$", RegexOption.IGNORE_CASE).matches(wat)) return null
        return wat to m.groupValues[2].trim().ifEmpty { null }
    }

    fun herken(zin: String): Pair<String, String>? {
        val t = zin.lowercase().replace(Regex("[^a-z0-9éëèïö ]"), " ").replace(Regex("\\s+"), " ").trim()
            .removePrefix("hey ").removePrefix("oké ").removePrefix("ok ").trim()
        val muziek = "(de )?(muziek|liedje|nummer|spotify)"
        Regex("^(zet )?(het )?(volume|geluid) (op|naar) (\\d{1,2})( procent)?$").find(t)?.let {
            var n = it.groupValues[5].toInt()
            if (it.groupValues[6].isNotEmpty() || n > 10) n = (n + 5) / 10
            return "volume" to n.coerceIn(0, 10).toString()
        }
        return when {
            t.matches(Regex("^(stop|stil|hou op|laat maar|annuleer|niks|niets|laat maar zitten)$")) -> "stop" to "Oké"
            t.matches(Regex("^(pauze|pauzeer|pauzeren|zet $muziek op pauze|$muziek (op )?pauze|stop $muziek|muziek uit)$")) -> "pauze" to "Gepauzeerd"
            t.matches(Regex("^(speel verder|ga verder|hervat|verder spelen|doorspelen|muziek aan|speel $muziek( verder)?|play)$")) -> "speel" to "Speelt verder"
            t.matches(Regex("^(volgende|volgend|skip|sla over|(het |de )?volgende (nummer|liedje)|volgend (nummer|liedje)|next)$")) -> "volgende" to "Volgend nummer"
            t.matches(Regex("^(vorige|vorig|(het |de )?vorige (nummer|liedje)|vorig (nummer|liedje)|previous)$")) -> "vorige" to "Vorig nummer"
            t.matches(Regex("^(harder|luider|(zet )?(het )?(volume|geluid|muziek) (harder|omhoog|hoger)|volume omhoog)$")) -> "harder" to "Harder"
            t.matches(Regex("^(zachter|stiller|(zet )?(het )?(volume|geluid|muziek) (zachter|omlaag|lager)|volume omlaag)$")) -> "zachter" to "Zachter"
            else -> null
        }
    }
}
