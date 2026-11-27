package nl.timvanbergen.haard

import org.json.JSONArray

/**
 * De echte toestand in huis voor Gemini ("hoe warm is het binnen?"): alleen wat Home Assistant aan de assistent
 * laat zien, als korte regels "naam: waarde". Een minuut bewaard; [vooraf] haalt het al op tijdens het praten.
 */
object Huis {

    private val DOMEINEN = setOf("sensor", "binary_sensor", "climate", "weather", "light", "switch", "fan", "cover",
        "media_player", "person", "lock", "input_boolean", "vacuum")
    @Volatile private var tekst = ""
    @Volatile private var tijd = 0L

    fun vooraf() { if (System.currentTimeMillis() - tijd > 60_000) Thread { runCatching { nu() } }.start() }

    /** Niet op de hoofddraad aanroepen. */
    fun nu(): String {
        if (System.currentTimeMillis() - tijd < 60_000) return tekst
        val ids = Verstaan.zichtbaar
        if (ids.isEmpty() || !HomeAssistant.ingelogd) return ""
        val (code, json) = HomeAssistant.vraag("GET", "/api/states", null)
        if (code != 200) return tekst
        val staten = JSONArray(json)
        val regels = ArrayList<String>()
        for (i in 0 until staten.length()) {
            val s = staten.getJSONObject(i)
            val id = s.optString("entity_id")
            if (id !in ids || id.substringBefore('.') !in DOMEINEN) continue
            val a = s.optJSONObject("attributes")
            val naam = a?.optString("friendly_name").orEmpty().ifEmpty { id }
            var waarde = Opdrachten.vertaalStaat(s.optString("state"))
            a?.optString("unit_of_measurement")?.takeIf { it.isNotEmpty() }?.let { waarde += " $it" }
            if (id.startsWith("climate.")) a?.opt("current_temperature")?.let { waarde += ", binnen ${it}°" }
            if (id.startsWith("weather.")) a?.opt("temperature")?.let { waarde += ", ${it}°" }
            regels.add("$naam: $waarde")
            if (regels.size >= 120) break
        }
        tekst = regels.joinToString("\n")
        tijd = System.currentTimeMillis()
        return tekst
    }
}
