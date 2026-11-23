package nl.timvanbergen.haard

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** Alle instellingen van Haard; de pagina leest en schrijft ze via de brug. */
object Instellingen {

    private lateinit var p: SharedPreferences

    // Wat de pagina mag zien en aanpassen, met de standaardwaarde. Tokens staan hier bewust niet in.
    private val standaard = mapOf<String, Any>(
        "ha_url" to "https://ha.timvanbergen.nl",
        "pipeline" to "",
        "wekwoord" to true,
        "gevoeligheid" to "normaal",
        "thema" to "donker",
        "foto_interval" to 30,
        "nacht" to true,
        "nacht_van" to "23:00",
        "nacht_tot" to "07:00",
        "weer" to "",
        "stil" to true,
        "stil_van" to "23:00",
        "stil_tot" to "08:00",
        "stil_tot_weekend" to "10:00",
    )

    fun init(ctx: Context) {
        if (!::p.isInitialized) p = ctx.applicationContext.getSharedPreferences("haard", Context.MODE_PRIVATE)
    }

    val prefs: SharedPreferences get() = p

    fun alles(): JSONObject {
        val o = JSONObject()
        for ((k, d) in standaard) o.put(k, waarde(k, d))
        return o
    }

    private fun waarde(k: String, d: Any): Any = when (d) {
        is Boolean -> p.getBoolean(k, d)
        is Int -> p.getInt(k, d)
        else -> p.getString(k, d as String) ?: d
    }

    /** Waarde uit de pagina (altijd als tekst of JSON-waarde) opslaan in het juiste type. */
    fun zet(k: String, v: String) {
        val d = standaard[k] ?: return
        p.edit().apply {
            when (d) {
                is Boolean -> putBoolean(k, v == "true")
                is Int -> putInt(k, v.toIntOrNull() ?: d)
                else -> putString(k, v)
            }
        }.apply()
    }

    val haUrl: String get() = (p.getString("ha_url", null)?.takeIf { it.isNotBlank() } ?: standaard["ha_url"] as String).trimEnd('/')
    val pipeline: String get() = p.getString("pipeline", "") ?: ""
    val wekwoord: Boolean get() = p.getBoolean("wekwoord", true)

    /** Drempel voor het wekwoord: lager = reageert sneller, maar ook vaker per ongeluk. */
    val drempel: Float get() = when (p.getString("gevoeligheid", "normaal")) {
        "laag" -> 0.7f
        "hoog" -> 0.3f
        else -> 0.5f
    }
}
