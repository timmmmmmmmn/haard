package nl.timvanbergen.haard

import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Gemini (Google) voor wat de tablet zelf niet kan: uitschrijven wat je zei als het geen vast commando was,
 * en antwoord geven op echte vragen (met Google Zoeken voor wat actueel is). Er gaat pas iets naar Google
 * na het wekwoord. Alles hier doet netwerkverkeer: alleen vanaf een achtergronddraad aanroepen.
 */
object Gemini {

    // De "-latest"-namen schuiven vanzelf mee met nieuwe versies van Google
    private const val SNEL = "gemini-flash-lite-latest"
    private const val SLIM = "gemini-flash-latest"
    private const val API = "https://generativelanguage.googleapis.com/v1beta/models/"

    class Fout(melding: String) : Exception(melding)

    private val http = HomeAssistant.http.newBuilder().readTimeout(40, TimeUnit.SECONDS).pingInterval(0, TimeUnit.SECONDS).build()
    private var zonderDenkInstelling = false // nieuwere modellen willen soms geen thinkingBudget: dan zonder

    val sleutel: String? get() = Instellingen.prefs.getString("gemini_sleutel", null)?.takeIf { it.isNotBlank() }

    /** Wat er gezegd is (16 kHz, 16 bit mono). Leeg als er niets verstaanbaars in zit. */
    fun uitschrijven(pcm: ByteArray, namen: Collection<String>): String {
        val prompt = "Schrijf letterlijk uit wat er in deze opname gezegd wordt. Het is Nederlands: een vraag of opdracht aan " +
            "een spraakassistent in huis. Geef alleen de gezegde tekst, zonder aanhalingstekens of uitleg. " +
            "Is er geen verstaanbare spraak (stilte, ruis, muziek of tv op de achtergrond), geef dan alleen: -" +
            (if (namen.isEmpty()) "" else "\nNamen in huis die kunnen voorkomen: " + namen.take(60).joinToString(", "))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", Base64.encodeToString(wav(pcm), Base64.NO_WRAP))))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", config(0.0, 200))
        val tekst = vraagModel(SNEL, body, null).trim().trim('"', '“', '”').trim()
        return if (tekst == "-" || tekst.isEmpty()) "" else tekst
    }

    /**
     * Een vraag beantwoorden. [eerder] = de vorige vragen en antwoorden van dit gesprek (voor "en morgen?"),
     * [huis] = de echte waarden uit Home Assistant. [stuk] krijgt het antwoord tot nu toe, terwijl het binnenkomt.
     */
    fun antwoord(vraag: String, eerder: List<Pair<String, String>>, huis: String, stuk: (String) -> Unit): String {
        val nu = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale("nl", "NL")).format(Date())
        val systeem = """
            Je bent Haard, een spraakassistent op een tablet in de slaapkamer van Tim, in Nederland. Het is nu $nu.
            Je antwoord wordt voorgelezen. Antwoord daarom in gewoon gesproken Nederlands en kort: één tot drie zinnen,
            tenzij er om meer gevraagd wordt. Geen opmaak, geen lijstjes, geen emoji's, geen links of bronnen noemen.
            Zoek actuele dingen (nieuws, uitslagen, openingstijden, prijzen) op met Google Zoeken.
            Stel alleen een wedervraag als je echt iets moet weten om te kunnen antwoorden.
            Je kunt zelf geen apparaten bedienen; Home Assistant doet dat. Komt een opdracht toch bij jou, zeg dan kort
            dat je dat apparaat niet kent of dat het niet lukte. Verzin geen meetwaarden: gebruik alleen deze gegevens uit huis.
        """.trimIndent() + if (huis.isEmpty()) "" else "\n\nGegevens uit huis (Home Assistant):\n$huis"
        val inhoud = JSONArray()
        for ((v, a) in eerder) {
            inhoud.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", v))))
            inhoud.put(JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", a))))
        }
        inhoud.put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", vraag))))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systeem))))
            .put("contents", inhoud)
            .put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            .put("generationConfig", config(0.4, 500))
        return vraagModel(SLIM, body, stuk).trim()
    }

    private fun config(temp: Double, max: Int): JSONObject {
        val c = JSONObject().put("temperature", temp).put("maxOutputTokens", max)
        // Niet eerst "nadenken": dat maakt het antwoord seconden trager en is voor korte vragen niet nodig
        if (!zonderDenkInstelling) c.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        return c
    }

    /** Eén verzoek, als stroom (SSE), zodat het antwoord al verschijnt terwijl het geschreven wordt. */
    private fun vraagModel(model: String, body: JSONObject, stuk: ((String) -> Unit)?): String {
        val key = sleutel ?: throw Fout("Er is nog geen Gemini-sleutel ingesteld.")
        val req = Request.Builder().url("$API$model:streamGenerateContent?alt=sse")
            .header("x-goog-api-key", key)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        val tekst = StringBuilder()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) {
                    val fout = r.body?.string().orEmpty()
                    if (r.code == 400 && !zonderDenkInstelling && fout.contains("thinking", true)) {
                        zonderDenkInstelling = true
                        body.optJSONObject("generationConfig")?.remove("thinkingConfig")
                        return vraagModel(model, body, stuk)
                    }
                    throw Fout(when (r.code) {
                        400, 401, 403 -> if (fout.contains("API key", true) || fout.contains("API_KEY", true)) "De Gemini-sleutel klopt niet." else "Gemini snapte de vraag niet."
                        429 -> "Gemini heeft even te veel vragen gehad. Probeer het zo nog eens."
                        else -> "Gemini is nu niet bereikbaar."
                    })
                }
                val bron = r.body?.source() ?: throw Fout("Gemini gaf geen antwoord.")
                while (true) {
                    val regel = bron.readUtf8Line() ?: break
                    if (!regel.startsWith("data:")) continue
                    val o = runCatching { JSONObject(regel.substring(5).trim()) }.getOrNull() ?: continue
                    val delen = o.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until delen.length()) {
                        val d = delen.getJSONObject(i)
                        if (d.optBoolean("thought")) continue
                        tekst.append(d.optString("text"))
                    }
                    stuk?.invoke(tekst.toString())
                }
            }
        } catch (e: Fout) {
            throw e
        } catch (e: Exception) {
            throw Fout("Geen verbinding met Gemini.")
        }
        return tekst.toString()
    }

    private fun wav(pcm: ByteArray): ByteArray {
        val kop = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        kop.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            .put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size)
        return ByteArrayOutputStream(44 + pcm.size).apply { write(kop.array()); write(pcm) }.toByteArray()
    }
}
