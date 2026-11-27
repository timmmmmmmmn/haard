package nl.timvanbergen.haard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Eén vraag-en-antwoord, zonder server: de tablet doet het zelf, Home Assistant bedient het huis en Gemini
 * helpt bij wat moeilijk is.
 *  1. luisteren: Vosk op de tablet luistert mee; de tablet hoort zelf wanneer je klaar bent met praten;
 *  2. een vast commando ("doe het licht uit") gaat meteen naar Home Assistant, met een belletje als het gelukt is;
 *  3. anders schrijft Gemini uit wat je zei; muziek, volume, agenda en weer regelt de tablet zelf,
 *     opdrachten gaan naar Home Assistant en echte vragen naar Gemini;
 *  4. het antwoord spreekt Piper (stem Ronnie) op de tablet zelf uit.
 * Alles gebeurt op één eigen draad, zodat er nooit twee dingen tegelijk aan de toestand zitten.
 */
class Assistent(private val ctx: Context) {

    enum class Fase { RUST, LUISTEREN, DENKEN, SPREKEN }

    companion object {
        /** Het geluidsniveau van de kamer zonder spraak; de microfoonlus houdt het bij (-1 = nog onbekend). */
        @Volatile var achtergrond = -1f
    }

    private val draad = HandlerThread("assistent").apply { start() }
    private val h = Handler(draad.looper)
    private val audio = ctx.getSystemService(AudioManager::class.java)

    @Volatile var fase = Fase.RUST
        private set

    private var beurt = 0 // elke nieuwe vraag krijgt een nummer; late berichten van een oude vraag tellen niet
    private val opname = ByteArrayOutputStream() // wat je zei, voor Gemini
    private var gestopt = false
    private var twijfel = false // geen duidelijke spraak gemeten: niets verstaan = stil verdwijnen, zonder foutgeluid
    private val eerder = ArrayList<Pair<String, String>>() // dit gesprek met Gemini ("en morgen?")
    private var gesprekTijd = 0L
    private var speler: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var antwoord = StringBuilder()
    private var doorpraten = false
    private var wachtOpApparaat: String? = null // "speel Frenna" → wacht op het antwoord op "Op welk apparaat?"

    // Zelf horen dat je klaar bent met praten: eerst duidelijk spraak, daarna ~0,56 s stilte ten opzichte van
    // het stilste stukje (ook van vlak voor het wekwoord). Zonder duidelijke spraak doet Haard niets (spookcontrole).
    private var stilsteBlok = Float.MAX_VALUE
    private var spraakBlokken = 0
    private var hardste = 0f // voor het logboek: hoe hard was het hardste stukje
    private var stilteBlokken = 0

    /** Wekwoord gehoord of op de microfoon getikt. */
    fun start() = h.post { begin() }

    /** Weg met de vraag of het antwoord (tik op het scherm, of "stop"). */
    fun stop() = h.post {
        beurt++
        stopSpeler()
        Stem.stop()
        klaar(null)
    }

    /** 80 ms geluid van de microfoon (16 kHz, 16 bit, mono); alleen nodig tijdens het luisteren. */
    fun geluid(pcm: ByteArray) {
        if (fase != Fase.LUISTEREN) return
        h.post { stuurGeluid(pcm) }
    }

    private fun begin() {
        if (!HomeAssistant.ingelogd && Gemini.sleutel == null) {
            Geluid.fout()
            status("fout", "Haard is nog niet verbonden met Home Assistant. Open de instellingen om in te loggen.")
            return
        }
        if (!Verstaan.klaar && Gemini.sleutel == null) {
            Geluid.fout()
            status("fout", "Haard haalt de spraakmodellen nog op. Even geduld.")
            return
        }
        stopSpeler()
        Stem.stop()
        val mijn = ++beurt
        fase = Fase.LUISTEREN
        opname.reset()
        gestopt = false
        antwoord = StringBuilder()
        doorpraten = false
        // Een gesprek loopt door als je binnen vijf minuten weer iets vraagt ("en morgen?")
        if (System.currentTimeMillis() - gesprekTijd > 5 * 60_000) eerder.clear()
        stilsteBlok = achtergrond.takeIf { it >= 0 }?.coerceAtLeast(30f) ?: Float.MAX_VALUE; spraakBlokken = 0; stilteBlokken = 0; hardste = 0f
        vraagFocus()
        Geluid.luister()
        status("luisteren")
        Verstaan.begin()
        Huis.vooraf()
        // Niemand zegt iets: na 6 s stoppen. Langer dan 15 s praten: ook stoppen.
        h.postDelayed({ if (mijn == beurt && fase == Fase.LUISTEREN && spraakBlokken < 4) eindeGeluid() }, 6_000)
        h.postDelayed({ if (mijn == beurt && fase == Fase.LUISTEREN) eindeGeluid() }, 15_000)
    }

    private fun stuurGeluid(pcm: ByteArray) {
        if (fase != Fase.LUISTEREN || gestopt) return
        opname.write(pcm)
        Verstaan.geluid(pcm)
        eigenEinde(pcm)
    }

    private fun eigenEinde(pcm: ByteArray) {
        var som = 0.0
        val n = pcm.size / 2
        for (i in 0 until n) { val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toDouble(); som += s * s }
        val rms = Math.sqrt(som / n).toFloat()
        stilsteBlok = minOf(stilsteBlok, rms)
        hardste = maxOf(hardste, rms)
        // Vooral ten opzichte van de stilte in de kamer: de microfoon van de Fire is zacht (spraak op een paar meter ~150-600)
        if (rms > maxOf(stilsteBlok * 3f, 120f)) { spraakBlokken++; stilteBlokken = 0 }
        else if (rms < maxOf(stilsteBlok * 2f, 80f)) stilteBlokken++
        else stilteBlokken = 0
        // Minstens ~0,3 s gepraat, daarna 7 stille blokken van 80 ms
        if (spraakBlokken >= 4 && stilteBlokken >= 7) eindeGeluid()
    }

    private fun eindeGeluid() {
        if (gestopt) return
        gestopt = true
        fase = Fase.DENKEN
        status("denken", stap = "verstaan")
        val mijn = beurt
        val spraak = spraakBlokken >= 4
        Verstaan.einde { u -> h.post { if (mijn == beurt) naVerstaan(u, spraak) } }
    }

    private fun naVerstaan(u: Verstaan.Uitkomst, spraak: Boolean) {
        android.util.Log.i("Haard", "Verstaan: vrij=\"${u.vrij}\" commando=${u.commando} spraak=$spraak " +
            "(blokken=$spraakBlokken, hardste=${hardste.toInt()}, stilste=${stilsteBlok.toInt()}, kamer=${achtergrond.toInt()}, vosk=${Verstaan.klaar})")
        // Een vast commando alleen bij duidelijke spraak (nooit een lamp door ruis)
        val commando = u.commando
        if (commando != null && spraak && wachtOpApparaat == null) return snelUitvoeren(commando, u)
        // Twijfel of er wel iets gezegd is: 's nachts niets doen; overdag mag Gemini kijken, en hoort die niets, dan stil weg
        if (!spraak && (Stilte.nu || Gemini.sleutel == null)) return stilWeg()
        twijfel = !spraak
        langzaam(u)
    }

    /** Geen vast commando: Gemini schrijft uit wat je zei (lukt dat niet, dan wat Vosk vrij verstond). */
    private fun langzaam(u: Verstaan.Uitkomst) {
        if (Gemini.sleutel == null) return naTekst(u.vrij, null)
        status("denken", stap = "verstaan")
        val mijn = beurt
        val pcm = opname.toByteArray()
        Thread {
            val r = runCatching { Gemini.uitschrijven(pcm, Verstaan.namen) }
            android.util.Log.i("Haard", "Gemini hoorde: \"${r.getOrNull()}\" ${r.exceptionOrNull()?.message ?: ""}")
            h.post { if (mijn == beurt) naTekst(r.getOrNull() ?: u.vrij, r.exceptionOrNull()?.message) }
        }.start()
    }

    private fun naTekst(tekst: String, fout: String?) {
        when {
            tekst.isEmpty() && twijfel -> stilWeg()
            tekst.isEmpty() && fout != null -> mislukt(fout)
            tekst.isEmpty() -> niksVerstaan()
            Opdrachten.isSpook(tekst, eenWoordMag = wachtOpApparaat != null) -> stilWeg()
            else -> verwerk(tekst)
        }
    }

    /** Een vast commando: de tablet zelf, of Home Assistant zonder AI. Snapt HA het toch niet, dan alsnog via Gemini. */
    private fun snelUitvoeren(tekst: String, u: Verstaan.Uitkomst) {
        if (Opdrachten.herken(tekst) != null) return verwerk(tekst)
        if (!HomeAssistant.Socket.klaar) return mislukt("Geen verbinding met Home Assistant.")
        status("denken", jij = tekst, stap = "uitvoeren")
        vraagHA(tekst, tekst) { langzaam(u) }
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

    /** Eerst kijken of de tablet het zelf kan; dan Home Assistant; wat HA niet snapt gaat naar Gemini. */
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
        if (HomeAssistant.Socket.klaar) vraagHA(tekst, tekst) { vraagGemini(tekst) } else vraagGemini(tekst)
    }

    /**
     * De eigen afhandeling van Home Assistant (geen AI), met het apparaat erbij: dan weet HA de kamer en kunnen timers.
     * [nietBegrepen] als HA de zin niet kent.
     */
    private fun vraagHA(tekst: String, jij: String, nietBegrepen: () -> Unit) {
        val mijn = beurt
        var gehoord = false
        val msg = JSONObject().put("type", "conversation/process").put("text", Opdrachten.versimpel(tekst))
            .put("language", "nl").put("agent_id", "conversation.home_assistant")
        Esphome.apparaat?.let { msg.put("device_id", it) }
        HomeAssistant.Socket.stuur(msg) { r -> h.post {
            if (mijn != beurt || gehoord) return@post
            gehoord = true
            if (!r.optBoolean("success", false)) {
                return@post if (r.optJSONObject("error")?.optString("code") == "verbinding") mislukt("Geen verbinding met Home Assistant.") else nietBegrepen()
            }
            val resp = r.optJSONObject("result")?.optJSONObject("response") ?: return@post nietBegrepen()
            val soort = resp.optString("response_type")
            val zin = Opdrachten.vertaalStaat(resp.optJSONObject("speech")?.optJSONObject("plain")?.optString("speech").orEmpty())
            when {
                // Gedaan (lamp, timer): meteen een belletje, geen voorgelezen "Aangezet"
                soort == "action_done" && Opdrachten.isBevestiging(resp, zin) -> {
                    Geluid.klaar()
                    klaar(zin.ifEmpty { "Gedaan" }, jij)
                }
                // Een antwoord (hoe laat is het, is de lamp aan): voorlezen
                soort == "action_done" || soort == "query_answer" -> {
                    antwoord = StringBuilder(zin)
                    spreekTekst(zin, jij)
                }
                // HA kent de zin niet: dan is het een vraag (of iets wat Gemini beter verstaat)
                resp.optJSONObject("data")?.optString("code") == "no_intent_match" || zin.isEmpty() -> nietBegrepen()
                // HA snapte het wel, maar het lukte niet ("geen apparaat met die naam"): dat zeggen
                else -> {
                    Geluid.fout()
                    antwoord = StringBuilder(zin)
                    spreekTekst(zin, jij)
                }
            }
        } }
        h.postDelayed({ if (mijn == beurt && !gehoord) { gehoord = true; mislukt("Home Assistant antwoordt niet.") } }, 10_000)
    }

    /** Een echte vraag: Gemini, met de echte waarden uit huis. Het antwoord verschijnt terwijl het geschreven wordt. */
    private fun vraagGemini(tekst: String) {
        if (Gemini.sleutel == null) return klaar("Dat weet ik niet. Zet een Gemini-sleutel in de instellingen, dan kan ik ook echte vragen beantwoorden.", tekst)
        status("denken", jij = tekst, stap = "ai")
        val mijn = beurt
        val vorige = eerder.toList()
        Thread {
            val r = runCatching {
                Gemini.antwoord(tekst, vorige, Huis.nu()) { s -> h.post { if (mijn == beurt) status("denken", jij = tekst, antwoord = schoon(s), stap = "ai") } }
            }
            h.post {
                if (mijn != beurt) return@post
                val a = schoon(r.getOrNull().orEmpty())
                if (a.isEmpty()) return@post mislukt(r.exceptionOrNull()?.message ?: "Gemini gaf geen antwoord.")
                eerder.add(tekst to a)
                while (eerder.size > 6) eerder.removeAt(0)
                gesprekTijd = System.currentTimeMillis()
                // Stelt Gemini een wedervraag? Dan daarna meteen weer luisteren
                doorpraten = a.endsWith("?")
                antwoord = StringBuilder(a)
                spreekTekst(a, tekst)
            }
        }.start()
        // Niet eindeloos wachten
        h.postDelayed({ if (mijn == beurt && fase == Fase.DENKEN) mislukt("Het antwoord duurde te lang.") }, 45_000)
    }

    private fun schoon(s: String) = s.replace(Regex("\\*\\*|__|`"), "").replace(Regex("(?m)^\\s*[*-]\\s+"), "").trim()

    /** Een antwoord dat Haard zelf maakt (zoals uit de agenda) uitspreken. */
    private fun zeg(jij: String, stap: String = "zoeken", maak: () -> String) {
        status("denken", jij = jij, stap = stap)
        val mijn = beurt
        Thread {
            val tekst = runCatching(maak).getOrElse { "Er ging iets mis." }
            h.post {
                if (mijn != beurt) return@post
                antwoord = StringBuilder(tekst)
                spreekTekst(tekst, jij)
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
            h.post {
                if (mijn != beurt) return@post
                wachtOpApparaat = zoek
                doorpraten = true
                antwoord = StringBuilder("Op welk apparaat? " + namen.take(4).joinToString(", ") + (if (namen.size > 4) " …" else ""))
                spreekTekst("Op welk apparaat?", jij)
            }
        }.start()
    }

    /** Uitspreken met de stem op de tablet; is die er (nog) niet, dan via Home Assistant. 's Nachts nooit. */
    private fun spreekTekst(tekst: String, jij: String) {
        if (Stilte.nu || tekst.isBlank()) return naSpreken(jij)
        val mijn = beurt
        if (Stem.klaar) {
            fase = Fase.SPREKEN
            status("spreken", jij = jij, antwoord = antwoord.toString())
            Stem.spreek(tekst) { h.post { if (mijn == beurt) naSpreken(jij) } }
            return
        }
        Thread {
            val url = Muziek.stemUrl(tekst)
            h.post { if (mijn == beurt) { if (url == null) klaar(antwoord.toString(), jij) else spreek(Instellingen.haUrl + url, jij) } }
        }.start()
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
        // Wedervraag: meteen weer luisteren, zonder wekwoord, maar 's nachts nooit vanzelf
        if (doorpraten && !Stilte.nu) begin() else klaar(antwoord.toString(), jij)
    }

    private fun mislukt(melding: String?) {
        Geluid.fout()
        klaar(when {
            melding.isNullOrBlank() -> "Er ging iets mis."
            melding.endsWith(".") -> melding
            else -> "Er ging iets mis: $melding"
        })
    }

    private fun klaar(antwoordTekst: String?, jij: String? = null) {
        wachtOpApparaat = null
        val was = fase
        fase = Fase.RUST
        beurt++ // late berichten van deze vraag tellen niet meer
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

    /**
     * Is dit antwoord van Home Assistant alleen een bevestiging ("Aangezet", "Timer gezet")? Dan volstaat een belletje.
     * HA noemt ook "Het is 20:08" "gedaan", dus: er moet iets veranderd zijn, of het is een bekende bevestiging.
     */
    fun isBevestiging(resp: JSONObject, zin: String): Boolean {
        val gelukt = resp.optJSONObject("data")?.optJSONArray("success")?.length() ?: 0
        return gelukt > 0 || isBevestigingZin(zin)
    }

    fun isBevestigingZin(zin: String): Boolean {
        return Regex("^(aangezet|uitgezet|gedaan|ok[eé]?|timer (gezet|geannuleerd|gepauzeerd|hervat|aangepast)|.*\\b(aangepast|geopend|gesloten|gepauzeerd|hervat|gestopt|ingesteld))\\b",
            RegexOption.IGNORE_CASE).containsMatchIn(zin.trim())
    }

    /**
     * De AI schrijft eerst "EN: <kern in het Engels>" (daar zit zijn kennis het best) en dan "NL: <antwoord>".
     * Alleen het Nederlandse deel wordt getoond en voorgelezen. Tijdens het schrijven (klaar = false) is het leeg
     * zolang de Nederlandse regel nog niet begonnen is.
     */
    fun alleenNederlands(s: String, klaar: Boolean): String {
        Regex("(?i)\\bNL\\s*:").findAll(s).lastOrNull()?.let { return s.substring(it.range.last + 1).trim() }
        if (Regex("(?i)^\\s*EN\\s*:").containsMatchIn(s)) {
            if (!klaar) return ""
            // Geen NL-regel gekomen: dan alles behalve de Engelse regel
            return s.lines().filterNot { Regex("(?i)^\\s*EN\\s*:").containsMatchIn(it) }.joinToString(" ").trim()
        }
        return s.trim()
    }

    /** Home Assistant zegt soms de Engelse toestand ("Nee, die is off"): naar gewoon Nederlands. */
    fun vertaalStaat(zin: String): String {
        val woorden = mapOf("off" to "uit", "on" to "aan", "unavailable" to "niet bereikbaar", "unknown" to "onbekend",
            "closed" to "dicht", "not_home" to "niet thuis", "home" to "thuis", "playing" to "aan het spelen", "paused" to "gepauzeerd",
            "idle" to "niet actief", "standby" to "stand-by", "heat" to "verwarmen", "cool" to "koelen", "locked" to "op slot", "unlocked" to "open")
        return Regex("\\b(not_home|unavailable|unknown|closed|playing|paused|standby|unlocked|locked|idle|heat|cool|home|off|on)\\b")
            .replace(zin) { woorden[it.value] ?: it.value }
            // "0.1 graden" klinkt raar: hele graden
            .replace(Regex("(-?\\d+)[.,](\\d+)(?= graden)")) { Math.round(it.value.replace(',', '.').toDouble()).toString() }
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
