package nl.timvanbergen.haard

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * "Wat voor weer wordt het morgen?": met de echte verwachting uit Home Assistant (zoals in het weerpaneel),
 * in plaats van het taalmodel, dat de verwachting niet kent en anders iets verzint.
 * Netwerk: alleen vanaf een achtergronddraad aanroepen.
 */
object Weer {

    data class Vraag(val dagenVooruit: Int, val wanneer: String)

    private val DAGEN = listOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag")
    private val TEKST = mapOf(
        "sunny" to "zonnig", "clear-night" to "helder", "partlycloudy" to "half bewolkt", "cloudy" to "bewolkt",
        "rainy" to "regenachtig", "pouring" to "erg nat met veel regen", "lightning" to "onweerachtig", "lightning-rainy" to "onweerachtig met regen",
        "snowy" to "sneeuwachtig", "snowy-rainy" to "natte sneeuw", "fog" to "mistig", "hail" to "hagelachtig",
        "windy" to "winderig", "windy-variant" to "winderig en bewolkt", "exceptional" to "uitzonderlijk weer")

    /** Alleen vragen over een dag in de toekomst; "wat voor weer is het" (nu) gaat gewoon naar Home Assistant. */
    fun herken(zin: String): Vraag? {
        val t = zin.lowercase(Locale("nl")).trim().trimEnd('.', '!', '?')
        if (!Regex("\\b(weer|regen|regenen|sneeuw|sneeuwen|koud|warm|graden|temperatuur|zon|zonnig)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(agenda|afspraak|lamp|licht)\\b").containsMatchIn(t)) return null
        return when {
            Regex("\\bovermorgen\\b").containsMatchIn(t) -> Vraag(2, "overmorgen")
            Regex("\\bmorgen\\b").containsMatchIn(t) -> Vraag(1, "morgen")
            else -> DAGEN.indexOfFirst { Regex("\\b$it\\b").containsMatchIn(t) }.takeIf { it >= 0 }?.let { d ->
                val vandaag = Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1
                val verschil = (d - vandaag + 7) % 7
                if (verschil == 0) null else Vraag(verschil, "op ${DAGEN[d]}")
            }
        }
    }

    fun antwoord(v: Vraag): String {
        val id = entiteit() ?: return "Ik kan het weer nu niet ophalen."
        val (code, tekst) = HomeAssistant.vraag("POST", "/api/services/weather/get_forecasts?return_response",
            JSONObject().put("entity_id", id).put("type", "daily").toString())
        if (code != 200) return "Ik kan het weer nu niet ophalen."
        val lijst = JSONObject(tekst).optJSONObject("service_response")?.optJSONObject(id)?.optJSONArray("forecast") ?: JSONArray()
        val doel = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, v.dagenVooruit) }
        val dag = (0 until lijst.length()).map { lijst.getJSONObject(it) }.firstOrNull { f ->
            val c = Calendar.getInstance().apply { time = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(f.optString("datetime").take(10)) ?: return@firstOrNull false }
            c.get(Calendar.YEAR) == doel.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == doel.get(Calendar.DAY_OF_YEAR)
        } ?: return "Zo ver vooruit heb ik geen verwachting."
        val soort = TEKST[dag.optString("condition")] ?: "wisselend"
        val hoog = graden(dag.opt("temperature")); val laag = graden(dag.opt("templow"))
        val regen = dag.optDouble("precipitation_probability", Double.NaN)
        val zin = StringBuilder("${v.wanneer.replaceFirstChar { it.uppercase() }} wordt het $soort")
        if (laag != null && hoog != null) zin.append(", tussen $laag en $hoog graden") else if (hoog != null) zin.append(", tot $hoog graden")
        if (!regen.isNaN() && regen >= 30) zin.append(", met ${Math.round(regen)} procent kans op regen")
        return zin.append(".").toString()
    }

    private fun graden(v: Any?): String? {
        val d = (v as? Number)?.toDouble() ?: return null
        val r = Math.round(d).toInt()
        return if (r < 0) "min ${-r}" else "$r"
    }

    /** Het weer uit de instellingen, anders de eerste weersverwachting in Home Assistant. */
    private fun entiteit(): String? {
        Instellingen.prefs.getString("weer", "")?.takeIf { it.startsWith("weather.") }?.let { return it }
        val (code, tekst) = HomeAssistant.vraag("GET", "/api/states", null)
        if (code != 200) return null
        val a = JSONArray(tekst)
        return (0 until a.length()).map { a.getJSONObject(it).optString("entity_id") }.firstOrNull { it.startsWith("weather.") }
    }
}
