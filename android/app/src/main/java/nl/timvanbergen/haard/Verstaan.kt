package nl.timvanbergen.haard

import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spraakherkenning op de tablet zelf (Vosk, klein Nederlands model). Twee herkenners luisteren tegelijk:
 *  - "vast" mag alleen kiezen uit de commando's van [Zinnen]: snel en zeker voor "doe het licht uit";
 *  - "vrij" schrijft alles uit; minder goed, alleen als terugval als Gemini er niet is.
 * Alles op één eigen draad; een uitkomst komt pas als het laatste geluid verwerkt is.
 */
object Verstaan {

    data class Uitkomst(val vrij: String, val commando: String?)

    private val draad = HandlerThread("verstaan").apply { start() }
    private val h = Handler(draad.looper)
    private var model: Model? = null
    @Volatile private var vrij: Recognizer? = null
    private var vast: Recognizer? = null
    @Volatile private var zinnen: Map<String, String> = emptyMap()

    /** Wat Home Assistant aan de assistent laat zien: de entiteiten en hun namen (ook voor [Gemini] en [Huis]). */
    @Volatile var zichtbaar: Set<String> = emptySet()
        private set
    @Volatile var namen: List<String> = emptyList()
        private set
    private var zinnenTijd = 0L

    val klaar: Boolean get() = vrij != null

    fun laad(map: File) = h.post {
        if (model != null) return@post
        runCatching {
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            val m = Model(map.path)
            model = m
            vrij = Recognizer(m, 16000f).apply { setWords(true) }
            maakVast()
            android.util.Log.i("Haard", "Vosk geladen")
        }.onFailure { android.util.Log.e("Haard", "Vosk laden mislukt", it) }
        ververs()
    }

    private fun maakVast() {
        val m = model ?: return
        if (zinnen.isEmpty()) return
        vast?.close()
        vast = runCatching { Recognizer(m, 16000f, JSONArray(zinnen.keys.toList() + "[unk]").toString()).apply { setWords(true) } }.getOrNull()
    }

    /** Begin van een vraag: allebei schoon beginnen. */
    fun begin() = h.post { vrij?.reset(); vast?.reset(); piek = 400f }

    fun geluid(pcm: ByteArray) = h.post {
        val luid = harder(pcm)
        vrij?.acceptWaveForm(luid, luid.size)
        vast?.acceptWaveForm(luid, luid.size)
    }

    // De microfoon van de Fire is zacht (spraak ~150-600): voor Vosk tot zes keer harder. Het hardste stukje tot nu
    // toe bepaalt hoeveel, zodat luide spraak niet over de rand gaat (dan klinkt het vervormd en verstaat Vosk het niet).
    private var piek = 400f

    private fun harder(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        var som = 0.0
        for (i in 0 until n) { val x = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort().toDouble(); som += x * x }
        piek = maxOf(piek, Math.sqrt(som / n).toFloat())
        val keer = (2500f / piek).coerceIn(1f, 6f)
        if (keer <= 1f) return pcm
        val uit = ByteArray(pcm.size)
        for (i in 0 until n) {
            val x = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort()
            val y = (x * keer).toInt().coerceIn(-32768, 32767)
            uit[2 * i] = y.toByte(); uit[2 * i + 1] = (y shr 8).toByte()
        }
        return uit
    }

    /** Klaar met praten: wat is er gezegd? [dan] komt op de verstaan-draad. */
    fun einde(dan: (Uitkomst) -> Unit) = h.post {
        val v = vrij?.finalResult?.let { JSONObject(it).optString("text").trim() }.orEmpty()
        val c = vast?.finalResult?.let { r ->
            val o = JSONObject(r)
            val zeker = o.optJSONArray("result")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optDouble("conf", 0.0) } }.orEmpty()
            android.util.Log.i("Haard", "Vosk vast: \"${o.optString("text")}\" ${zeker.map { (it * 100).toInt() }}")
            Zinnen.herken(o.optString("text"), zeker, zinnen)
        }
        dan(Uitkomst(v, c))
        // Af en toe de namen in Home Assistant opnieuw ophalen (een nieuwe lamp of kamer komt zo vanzelf mee)
        if (System.currentTimeMillis() - zinnenTijd > 30 * 60_000) ververs()
    }

    /** De namen van apparaten en kamers uit Home Assistant halen en de commando's opnieuw maken. */
    fun ververs() {
        zinnenTijd = System.currentTimeMillis()
        Thread {
            val z = runCatching { haalZinnen() }.getOrNull() ?: return@Thread
            h.post { if (z.isNotEmpty() && z != zinnen) { zinnen = z; maakVast(); android.util.Log.i("Haard", "${z.size} vaste zinnen, vast=${vast != null}") } }
        }.start()
    }

    private fun ws(type: String): JSONObject? {
        val klaar = CountDownLatch(1)
        var uit: JSONObject? = null
        HomeAssistant.Socket.stuur(JSONObject().put("type", type)) { r -> uit = r.optJSONObject("result"); klaar.countDown() }
        klaar.await(15, TimeUnit.SECONDS)
        return uit
    }

    private fun wsLijst(type: String): JSONArray? {
        val klaar = CountDownLatch(1)
        var uit: JSONArray? = null
        HomeAssistant.Socket.stuur(JSONObject().put("type", type)) { r -> uit = r.optJSONArray("result"); klaar.countDown() }
        klaar.await(15, TimeUnit.SECONDS)
        return uit
    }

    private fun haalZinnen(): Map<String, String> {
        if (!HomeAssistant.ingelogd) return emptyMap()
        val lijst = ws("homeassistant/expose_entity/list")?.optJSONObject("exposed_entities") ?: return emptyMap()
        val ids = lijst.keys().asSequence().filter { lijst.optJSONObject(it)?.optBoolean("conversation") == true }.toSet()
        this.zichtbaar = ids
        val aliassen = HashMap<String, List<String>>()
        wsLijst("config/entity_registry/list")?.let { l ->
            for (i in 0 until l.length()) {
                val e = l.getJSONObject(i)
                val a = e.optJSONArray("aliases") ?: continue
                aliassen[e.optString("entity_id")] = (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() && s != "null" } }
            }
        }
        val (code, tekst) = HomeAssistant.vraag("GET", "/api/states", null)
        if (code != 200) return emptyMap()
        val staten = JSONArray(tekst)
        val apparaten = (0 until staten.length()).map { staten.getJSONObject(it) }.filter { it.optString("entity_id") in ids }.map {
            val id = it.optString("entity_id")
            val naam = it.optJSONObject("attributes")?.optString("friendly_name").orEmpty()
            Zinnen.Apparaat(listOf(naam) + aliassen[id].orEmpty(), id.substringBefore('.'))
        }
        val kamers = ArrayList<String>()
        wsLijst("config/area_registry/list")?.let { l ->
            for (i in 0 until l.length()) {
                val a = l.getJSONObject(i)
                kamers.add(a.optString("name"))
                a.optJSONArray("aliases")?.let { al -> for (j in 0 until al.length()) kamers.add(al.optString(j)) }
            }
        }
        namen = (apparaten.flatMap { it.namen } + kamers).filter { it.isNotBlank() }.distinct()
        return Zinnen.bouw(apparaten, kamers)
    }
}
