package nl.timvanbergen.haard

import org.json.JSONObject

/** De pijplijn "Haard snel" in Home Assistant (Speech-to-Phrase + eigen afhandeling, zonder taalmodel). */
object SnellePijplijn {

    /** Het ID, of null als die pijplijn er (nog) niet is: dan gaat alles via Whisper. */
    val id: String? get() = Instellingen.prefs.getString("pipeline_snel", null)

    /** Opzoeken op naam; bij elke (her)verbinding opnieuw, zodat een gewijzigde pijplijn vanzelf meekomt. */
    fun zoek() {
        HomeAssistant.Socket.stuur(JSONObject().put("type", "assist_pipeline/pipeline/list")) { r ->
            val lijst = r.optJSONObject("result")?.optJSONArray("pipelines") ?: return@stuur
            val snel = (0 until lijst.length()).map { lijst.getJSONObject(it) }
                .firstOrNull { it.optString("name").equals("Haard snel", ignoreCase = true) }
            Instellingen.prefs.edit().putString("pipeline_snel", snel?.optString("id")).apply()
        }
    }
}
