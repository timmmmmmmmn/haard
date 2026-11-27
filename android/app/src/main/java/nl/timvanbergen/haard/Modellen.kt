package nl.timvanbergen.haard

import android.content.Context
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * De spraakmodellen die op de tablet zelf draaien: Vosk (Nederlandse spraakherkenning, voor commando's) en
 * Piper met de stem Ronnie. Ze zijn samen ~140 MB en staan daarom niet in de app, maar worden één keer
 * opgehaald uit een release op GitHub. Zo blijven updates van de app klein.
 */
object Modellen {

    private const val VERSIE = 1
    private const val URL = "https://github.com/timmmmmmmmn/haard/releases/download/modellen-$VERSIE/modellen-$VERSIE.zip"

    private lateinit var map: File
    val vosk: File get() = File(map, "vosk-nl")
    val stem: File get() = File(map, "ronnie")

    @Volatile var klaar = false
        private set
    private var bezig = false
    private val wachtenden = ArrayList<() -> Unit>()

    /** Zorgt dat de modellen er zijn (zo nodig downloaden) en roept daarna [dan] aan, op een achtergronddraad. */
    @Synchronized
    fun zorg(ctx: Context, dan: () -> Unit) {
        map = File(ctx.filesDir, "modellen")
        if (klaar) { Thread(dan).start(); return }
        wachtenden.add(dan)
        if (bezig) return
        bezig = true
        Thread({ haal() }, "modellen").start()
    }

    private fun haal() {
        val merk = File(map, "versie")
        var poging = 0
        while (merk.takeIf { it.exists() }?.readText()?.trim() != VERSIE.toString()) {
            status(false, 0, null)
            val fout = runCatching { download(merk) }.exceptionOrNull() ?: continue
            status(false, 0, "Downloaden mislukt: ${fout.message ?: "geen verbinding"}")
            // Later opnieuw proberen (wifi weg, GitHub even niet bereikbaar)
            Thread.sleep(minOf(10 * 60_000L, 15_000L shl minOf(poging++, 5)))
        }
        val dan = synchronized(this) { klaar = true; bezig = false; wachtenden.toList().also { wachtenden.clear() } }
        status(true, 100, null)
        for (d in dan) runCatching(d)
    }

    private fun download(merk: File) {
        map.deleteRecursively()
        val tmp = File(map.path + ".tmp").apply { deleteRecursively(); mkdirs() }
        val http = HomeAssistant.http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val body = r.body ?: error("leeg")
            val totaal = body.contentLength()
            var gelezen = 0L
            var laatst = -1
            val telt = object : java.io.FilterInputStream(body.byteStream()) {
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { n ->
                    if (n > 0) gelezen += n
                    val p = if (totaal > 0) (gelezen * 100 / totaal).toInt() else 0
                    if (p != laatst) { laatst = p; status(false, p, null) }
                }
            }
            ZipInputStream(telt.buffered()).use { zip ->
                val basis = tmp.canonicalPath + File.separator
                while (true) {
                    val e = zip.nextEntry ?: break
                    val f = File(tmp, e.name)
                    if (!f.canonicalPath.startsWith(basis)) error("ongeldig bestand in zip")
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zip.copyTo(it) } }
                }
            }
        }
        if (!tmp.renameTo(map)) error("uitpakken mislukt")
        merk.writeText(VERSIE.toString())
    }

    private fun status(klaar: Boolean, procent: Int, fout: String?) {
        Bus.stuur("modellen", JSONObject().put("klaar", klaar).put("procent", procent).put("fout", fout ?: ""))
    }
}
