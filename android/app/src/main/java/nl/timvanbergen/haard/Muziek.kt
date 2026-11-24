package nl.timvanbergen.haard

import org.json.JSONArray
import org.json.JSONObject

/**
 * "Speel Frenna": muziek starten via Music Assistant in Home Assistant, op een apparaat naar keuze.
 * Haard vraagt eerst "Op welk apparaat?" (tenzij je het al zei: "speel Frenna op de Sonos").
 * Alles hier doet netwerkverkeer: alleen vanaf een achtergronddraad aanroepen.
 */
object Muziek {

    data class Speler(val id: String, val naam: String)

    private var spelers: List<Speler> = emptyList()
    private var opgehaald = 0L

    /** De spelers van Music Assistant (één per naam, beschikbare eerst); tien minuten bewaard. */
    fun spelers(): List<Speler> {
        if (spelers.isNotEmpty() && System.currentTimeMillis() - opgehaald < 10 * 60_000) return spelers
        val ids = HashSet<String>()
        val klaar = java.util.concurrent.CountDownLatch(1)
        HomeAssistant.Socket.stuur(JSONObject().put("type", "config/entity_registry/list_for_display")) { r ->
            val lijst = r.optJSONObject("result")?.optJSONArray("entities") ?: JSONArray()
            for (i in 0 until lijst.length()) {
                val e = lijst.getJSONObject(i)
                if (e.optString("pl") == "music_assistant" && e.optString("ei").startsWith("media_player.")) ids.add(e.optString("ei"))
            }
            klaar.countDown()
        }
        klaar.await(10, java.util.concurrent.TimeUnit.SECONDS)
        if (ids.isEmpty()) return spelers
        val (code, tekst) = HomeAssistant.vraag("GET", "/api/states", null)
        if (code != 200) return spelers
        val staten = JSONArray(tekst)
        val gevonden = LinkedHashMap<String, Speler>()
        val volgorde = (0 until staten.length()).map { staten.getJSONObject(it) }
            .filter { it.optString("entity_id") in ids }
            .sortedBy { if (it.optString("state") == "unavailable") 1 else 0 }
        for (s in volgorde) {
            val naam = s.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifEmpty { continue }
            if (naam.lowercase() !in gevonden) gevonden[naam.lowercase()] = Speler(s.optString("entity_id"), naam)
        }
        spelers = gevonden.values.toList()
        opgehaald = System.currentTimeMillis()
        return spelers
    }

    /** Welke speler bedoel je? "hier", "deze" en "Haard" zijn de Haard zelf. */
    fun kies(gezegd: String): Speler? {
        val lijst = spelers()
        val t = normaal(gezegd)
        if (Regex("\\b(hier|deze|dit|haard|tablet|jezelf|jou)\\b").containsMatchIn(t))
            lijst.firstOrNull { normaal(it.naam).contains("haard") }?.let { return it }
        val woorden = t.split(" ").filter { it.length > 2 }
        // Een woord telt ook als het er een letter naast zit ("zonos" = "Sonos")
        fun lijkt(a: String, b: String) = a == b || (a.length >= 4 && b.length >= 4 && afstand(a, b) <= if (a.length >= 7) 2 else 1)
        fun treffers(s: Speler) = normaal(s.naam).split(" ").count { n -> woorden.any { lijkt(it, n) } }
        return lijst.maxByOrNull { s ->
            val n = normaal(s.naam)
            if (n == t) 100 else treffers(s) * 10 - n.length / 10
        }?.takeIf { s -> treffers(s) > 0 || normaal(s.naam) == t }
    }

    /** Hoeveel letters je moet veranderen om van a naar b te komen (Levenshtein). */
    private fun afstand(a: String, b: String): Int {
        var vorige = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val nu = IntArray(b.length + 1); nu[0] = i
            for (j in 1..b.length) nu[j] = minOf(vorige[j] + 1, nu[j - 1] + 1, vorige[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            vorige = nu
        }
        return vorige[b.length]
    }

    private fun normaal(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ")
        .replace(Regex("\\b(de|het|op|in|een|speaker|luidspreker|apparaat|graag|maar)\\b"), " ").replace(Regex("\\s+"), " ").trim()

    /** Start het afspelen; geeft null terug als het lukte, anders wat er misging. */
    fun speel(zoek: String, speler: Speler): String? {
        val body = JSONObject().put("entity_id", speler.id).put("media_id", zoek).toString()
        val (code, tekst) = HomeAssistant.vraag("POST", "/api/services/music_assistant/play_media", body)
        return when {
            code in 200..299 -> null
            code == 0 -> "Geen verbinding met Home Assistant."
            tekst.contains("No playable", true) || tekst.contains("not found", true) || tekst.contains("provider", true) ->
                "Ik kon $zoek niet vinden. Is Spotify gekoppeld in Music Assistant?"
            else -> "Dat lukte niet op ${speler.naam}."
        }
    }

    /** Een zin uitspreken met dezelfde stem als de assistent: geeft het adres van het geluid. */
    fun stemUrl(tekst: String): String? {
        val klaar = java.util.concurrent.CountDownLatch(1)
        var tts: JSONObject? = null
        val vraag = JSONObject().put("type", "assist_pipeline/pipeline/get")
        Instellingen.pipeline.takeIf { it.isNotEmpty() }?.let { vraag.put("pipeline_id", it) }
        HomeAssistant.Socket.stuur(vraag) { r -> tts = r.optJSONObject("result"); klaar.countDown() }
        klaar.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val p = tts ?: return null
        val body = JSONObject().put("engine_id", p.optString("tts_engine")).put("message", tekst)
            .put("language", p.optString("tts_language"))
        p.optString("tts_voice").takeIf { it.isNotEmpty() && it != "null" }?.let { body.put("options", JSONObject().put("voice", it)) }
        val (code, antwoord) = HomeAssistant.vraag("POST", "/api/tts_get_url", body.toString())
        if (code != 200) return null
        return JSONObject(antwoord).optString("path").ifEmpty { JSONObject(antwoord).optString("url") }.ifEmpty { null }
    }
}
