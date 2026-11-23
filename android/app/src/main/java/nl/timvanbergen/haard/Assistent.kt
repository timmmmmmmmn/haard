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
        // Een gesprek loopt door als je binnen vijf minuten weer iets vraagt ("en morgen?")
        if (System.currentTimeMillis() - gesprekTijd > 5 * 60_000) gesprek = null
        vraagFocus()
        Geluid.luister()
        status("luisteren")

        val msg = pijplijn("stt", "stt").put("input", JSONObject().put("sample_rate", 16000))
        runId = HomeAssistant.Socket.stuur(msg) { ev -> h.post { if (mijn == beurt) bijLuisteren(ev) } }

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

    private fun pijplijn(van: String, tot: String): JSONObject {
        val m = JSONObject().put("type", "assist_pipeline/run").put("start_stage", van).put("end_stage", tot)
        Instellingen.pipeline.takeIf { it.isNotEmpty() }?.let { m.put("pipeline", it) }
        gesprek?.let { m.put("conversation_id", it) }
        return m
    }

    private fun stuurGeluid(pcm: ByteArray) {
        if (fase != Fase.LUISTEREN || gestopt) return
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
        if (fase == Fase.LUISTEREN) {
            fase = Fase.DENKEN
            status("denken")
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
                if (tekst.isEmpty()) niksVerstaan() else verwerk(tekst)
            }
            "error" -> {
                if (data.optString("code") == "stt-no-text-recognized") niksVerstaan()
                else mislukt(data.optString("message"))
            }
        }
    }

    private fun niksVerstaan() {
        Geluid.fout()
        klaar("Ik heb je niet verstaan.")
    }

    /** Eerst kijken of de tablet het zelf kan; anders door naar Home Assistant. */
    private fun verwerk(tekst: String) {
        status("denken", jij = tekst)
        val zelf = Opdrachten.herken(tekst)
        if (zelf != null) {
            when (zelf.first) {
                "stop" -> Unit
                "volume" -> Media.zetVolume(ctx, zelf.second.toInt())
                else -> Media.actie(zelf.first)
            }
            Geluid.klaar()
            fase = Fase.RUST
            laatFocusLos()
            status("klaar", jij = tekst, antwoord = zelf.second.takeIf { zelf.first != "volume" } ?: "Volume op ${zelf.second}")
            return
        }
        val mijn = beurt
        val msg = pijplijn("intent", "tts").put("input", JSONObject().put("text", tekst))
        runId = HomeAssistant.Socket.stuur(msg) { ev -> h.post { if (mijn == beurt) bijAntwoord(ev, tekst) } }
        // Het taalmodel draait op een CPU en mag even nadenken, maar niet eindeloos
        h.postDelayed({ if (mijn == beurt && fase == Fase.DENKEN) mislukt("Het antwoord duurde te lang.") }, 120_000)
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
                    status("denken", jij = jij, antwoord = antwoord.toString())
                }
            }
            "intent-end" -> {
                val out = data.optJSONObject("intent_output") ?: JSONObject()
                out.optString("conversation_id").takeIf { it.isNotEmpty() }?.let { gesprek = it }
                gesprekTijd = System.currentTimeMillis()
                doorpraten = out.optBoolean("continue_conversation", false)
                val tekst = out.optJSONObject("response")?.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty()
                antwoord = StringBuilder(tekst)
                status("denken", jij = jij, antwoord = tekst)
            }
            "tts-end" -> {
                val url = data.optJSONObject("tts_output")?.optString("url").orEmpty()
                HomeAssistant.Socket.vergeet(runId)
                runId = -1
                if (url.isEmpty()) klaar(antwoord.toString(), jij) else spreek(if (url.startsWith("/")) Instellingen.haUrl + url else url, jij)
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
        if (doorpraten) begin() else klaar(antwoord.toString(), jij)
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

    private fun status(fase: String, tekst: String? = null, jij: String? = null, antwoord: String? = null) {
        val o = JSONObject().put("fase", fase)
        jij?.let { o.put("jij", it) }
        (antwoord ?: tekst)?.let { o.put("antwoord", it) }
        Bus.stuur("assist", o)
    }
}

/** Opdrachten die de tablet zelf afhandelt, zonder Home Assistant: muziek en volume. */
object Opdrachten {

    /** Geeft (actie, wat Haard laat zien) terug, of null als HA het moet doen. Bij "volume" is het tweede het getal. */
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
