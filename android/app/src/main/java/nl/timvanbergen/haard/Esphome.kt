package nl.timvanbergen.haard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Haard als apparaat in Home Assistant, net als een Voice PE of Nest Hub: de ESPHome-API (zonder versleuteling,
 * alleen in het eigen netwerk). Home Assistant ziet zo een spraakassistent met timers en omroepen, een mediaspeler
 * en een schakelaar om de microfoon uit te zetten. Het verstaan zelf blijft lopen via [Assistent] (met de
 * spookcontrole), maar elke vraag krijgt het apparaat-ID mee: zo weet Home Assistant in welke kamer je staat.
 *
 * 's Nachts (stille tijd) speelt niets geluid af: omroepen en timers verschijnen dan alleen op het scherm.
 */
object Esphome {

    private const val POORT = 6053
    private const val K_MEDIA = 1
    private const val K_MIC = 2
    // Spraakassistent + luidspreker + audio via de API + timers + omroepen
    private const val VA_FUNCTIES = 1 or 2 or 4 or 8 or 16

    private lateinit var ctx: Context
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var uit: OutputStream? = null
    private var gestart = false

    val naam = "haard"
    private val versie: String by lazy { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "1" }
    val mac: String by lazy {
        Instellingen.prefs.getString("esp_mac", null) ?: run {
            val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "haard"
            val h = MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            // Lokaal beheerd adres (02:…): Android geeft het echte wifi-adres niet meer prijs
            val m = (listOf(0x02) + (1..5).map { h[it].toInt() and 0xff }).joinToString(":") { "%02X".format(it) }
            Instellingen.prefs.edit().putString("esp_mac", m).apply(); m
        }
    }

    /** Het apparaat-ID in Home Assistant (na koppelen), voor elke vraag aan de assistent. */
    var apparaat: String?
        get() = Instellingen.prefs.getString("ha_apparaat", null)
        private set(v) { Instellingen.prefs.edit().putString("ha_apparaat", v).apply() }

    fun start(c: Context) {
        if (gestart) return
        gestart = true
        ctx = c.applicationContext
        Thread({ luister() }, "esphome").start()
        adverteer()
    }

    // ---------- Verbinding ----------

    private fun luister() {
        val server = runCatching { ServerSocket(POORT) }.getOrNull() ?: return
        while (true) {
            val s = runCatching { server.accept() }.getOrNull() ?: continue
            Thread({ verbinding(s) }, "esphome-ha").start()
        }
    }

    private fun verbinding(s: Socket) {
        s.tcpNoDelay = true
        val inp = DataInputStream(s.getInputStream().buffered())
        val o = s.getOutputStream()
        try {
            while (true) {
                if (inp.readUnsignedByte() != 0) return // alleen onversleuteld
                val lengte = varint(inp)
                val soort = varint(inp)
                val data = ByteArray(lengte).also { inp.readFully(it) }
                behandel(o, soort, Pb.lees(data))
            }
        } catch (_: Exception) {
        } finally {
            if (uit === o) uit = null
            runCatching { s.close() }
        }
    }

    private fun varint(i: DataInputStream): Int {
        var r = 0; var shift = 0
        while (true) {
            val b = i.readUnsignedByte()
            r = r or ((b and 0x7f) shl shift)
            if (b and 0x80 == 0) return r
            shift += 7
        }
    }

    // Netwerk mag niet op de hoofdthread: alles gaat via één verzendthread, in volgorde
    private val verzender = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun stuur(o: OutputStream?, soort: Int, data: ByteArray = ByteArray(0)) {
        o ?: return
        val kop = ByteArrayOutputStream().apply { write(0); Pb.varint(this, data.size.toLong()); Pb.varint(this, soort.toLong()) }
        verzender.execute { runCatching { o.write(kop.toByteArray() + data); o.flush() } }
    }

    private fun behandel(o: OutputStream, soort: Int, m: Map<Int, List<Any>>) {
        when (soort) {
            1 -> stuur(o, 2, Pb().u(1, 1).u(2, 12).s(3, "Haard").s(4, naam).bytes())
            3 -> stuur(o, 4)
            5 -> { stuur(o, 6); throw java.io.EOFException() }
            7 -> stuur(o, 8)
            9 -> stuur(o, 10, Pb().s(2, naam).s(3, mac).s(4, "2025.11.0").s(5, versie)
                .s(6, "Fire HD 8").s(8, "timvanbergen.haard").s(9, versie).s(12, "Tim")
                .s(13, "Haard").u(14, 3).u(17, VA_FUNCTIES).bytes())
            11 -> {
                val mp3 = Pb().s(1, "mp3").u(2, 48000).u(3, 2).bytes()
                val omroep = Pb().s(1, "mp3").u(2, 48000).u(3, 1).u(4, 1).bytes()
                stuur(o, 63, Pb().s(1, "haard_media").f(2, K_MEDIA).s(3, "").s(5, "mdi:speaker").b(8, true)
                    .m(9, mp3).m(9, omroep).bytes())
                stuur(o, 17, Pb().s(1, "microfoon_uit").f(2, K_MIC).s(3, "Microfoon uit").s(5, "mdi:microphone-off").u(8, 1).bytes())
                stuur(o, 19)
            }
            20 -> { uit = o; meldMedia(); meldMicrofoon(); zoekApparaat() }
            121 -> stuur(o, 122, Pb().m(1, Pb().s(1, "hey_fire").s(2, "Hey Fire").s(3, "nl").bytes())
                .s(2, "hey_fire").u(3, 1).bytes())
            33 -> if (Pb.int(m, 1) == K_MIC) {
                Instellingen.prefs.edit().putBoolean("microfoon_uit", Pb.bool(m, 2)).apply()
                HaardService.herlaad(ctx); meldMicrofoon()
            }
            65 -> mediaOpdracht(m)
            115 -> timer(m)
            119 -> omroep(o, Pb.str(m, 1), Pb.str(m, 2), Pb.str(m, 3))
        }
    }

    // ---------- Mediaspeler ----------

    private var speler: MediaPlayer? = null
    private var staat = 1 // 1 = rust, 2 = speelt, 3 = pauze, 4 = omroep

    private fun meldMedia() {
        val a = ctx.getSystemService(AudioManager::class.java)
        val vol = a.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        stuur(uit, 64, Pb().f(1, K_MEDIA).u(2, staat).fl(3, vol).bytes())
    }

    private fun meldMicrofoon() =
        stuur(uit, 26, Pb().f(1, K_MIC).b(2, Instellingen.prefs.getBoolean("microfoon_uit", false)).bytes())

    private fun mediaOpdracht(m: Map<Int, List<Any>>) = main.post {
        if (Pb.bool(m, 4)) {
            val a = ctx.getSystemService(AudioManager::class.java)
            val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (!Stilte.nu) a.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Pb.float(m, 5) * max), 0)
        }
        if (Pb.bool(m, 6)) speel(Pb.str(m, 7), if (Pb.bool(m, 9)) 4 else 2) {}
        if (Pb.bool(m, 2)) when (Pb.int(m, 3)) {
            0 -> speler?.let { it.start(); staat = 2 }
            1 -> speler?.let { it.pause(); staat = 3 }
            2 -> stop()
            5 -> speler?.let { if (it.isPlaying) { it.pause(); staat = 3 } else { it.start(); staat = 2 } }
            6 -> Media.actie("harder")
            7 -> Media.actie("zachter")
        }
        meldMedia()
    }

    /** Speelt een adres af; 's nachts nooit (dan meteen klaar). */
    private fun speel(url: String, alsStaat: Int, klaar: () -> Unit) {
        stop()
        if (Stilte.nu || url.isEmpty()) { klaar(); return }
        val p = MediaPlayer()
        speler = p
        staat = alsStaat
        try {
            p.setAudioAttributes(AudioAttributes.Builder()
                .setUsage(if (alsStaat == 4) AudioAttributes.USAGE_ASSISTANT else AudioAttributes.USAGE_MEDIA)
                .setContentType(if (alsStaat == 4) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC).build())
            p.setDataSource(volledig(url))
            p.setOnPreparedListener { it.start(); meldMedia() }
            p.setOnCompletionListener { stop(); meldMedia(); klaar() }
            p.setOnErrorListener { _, _, _ -> stop(); meldMedia(); klaar(); true }
            p.prepareAsync()
        } catch (_: Exception) { stop(); klaar() }
    }

    private fun stop() {
        speler?.let { runCatching { it.release() } }
        speler = null
        staat = 1
    }

    private fun volledig(url: String) = if (url.startsWith("/")) Instellingen.haUrl.trimEnd('/') + url else url

    // ---------- Omroepen ----------

    private fun omroep(o: OutputStream, media: String, tekst: String, voor: String) = main.post {
        if (tekst.isNotEmpty()) Bus.stuur("assist", JSONObject().put("fase", "klaar").put("antwoord", tekst).put("omroep", true))
        var gemeld = false
        val klaar = { if (!gemeld) { gemeld = true; stuur(o, 120, Pb().b(1, true).bytes()); meldMedia() } }
        // Vangnet: als afspelen blijft hangen, toch op tijd "klaar" melden
        main.postDelayed({ if (!gemeld) { stop(); klaar() } }, 90_000)
        // De tekst zelf uitspreken met de stem op de tablet; anders het geluid dat Home Assistant maakte
        val zeg = {
            if (tekst.isNotEmpty() && Stem.klaar && !Stilte.nu) {
                stop(); staat = 4; meldMedia()
                Stem.spreek(tekst) { main.post { staat = 1; klaar() } }
            } else speel(media, 4, klaar)
        }
        if (voor.isNotEmpty() && !Stilte.nu) speel(voor, 4) { zeg() } else zeg()
    }

    // ---------- Timers ----------

    private val timers = LinkedHashMap<String, JSONObject>()
    private var bel: Runnable? = null

    private fun timer(m: Map<Int, List<Any>>) = main.post {
        val id = Pb.str(m, 2)
        val t = JSONObject().put("id", id).put("naam", Pb.str(m, 3)).put("totaal", Pb.int(m, 4))
            .put("over", Pb.int(m, 5)).put("loopt", Pb.bool(m, 6)).put("sinds", System.currentTimeMillis())
        when (Pb.int(m, 1)) {
            0, 1 -> timers[id] = t
            2 -> timers.remove(id)
            3 -> { timers.remove(id); afgelopen(t) }
        }
        Bus.stuur("timers", JSONObject().put("lijst", org.json.JSONArray(timers.values.toList())))
    }

    /** Een timer is klaar: op het scherm tot je hem wegtikt; overdag ook een belletje, hooguit een minuut lang. */
    private fun afgelopen(t: JSONObject) {
        Bus.stuur("timerKlaar", t)
        bel?.let { main.removeCallbacks(it) }
        var keer = 0
        val r = object : Runnable {
            override fun run() {
                if (keer++ >= 20) { stopBel(); return }
                Geluid.klaar() // slaat zichzelf over in de stille tijd
                main.postDelayed(this, 3000)
            }
        }
        bel = r
        main.post(r)
    }

    fun stopBel() = main.post {
        bel?.let { main.removeCallbacks(it) }
        bel = null
        Bus.stuur("timerKlaar", JSONObject().put("weg", true))
    }

    // ---------- Vindbaar maken en koppelen ----------

    private fun adverteer() {
        val nsd = ctx.getSystemService(NsdManager::class.java) ?: return
        val info = NsdServiceInfo().apply {
            serviceName = naam
            serviceType = "_esphomelib._tcp"
            port = POORT
            setAttribute("mac", mac.replace(":", "").lowercase())
            setAttribute("version", "2025.11.0")
            setAttribute("platform", "Android")
            setAttribute("board", "Fire HD 8")
            setAttribute("network", "wifi")
            setAttribute("friendly_name", "Haard")
        }
        runCatching {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            })
        }
    }

    /** Zoekt ons apparaat in Home Assistant op (op MAC-adres) en onthoudt het ID. */
    private fun zoekApparaat() {
        HomeAssistant.Socket.stuur(JSONObject().put("type", "config/device_registry/list")) { r ->
            val lijst = r.optJSONArray("result") ?: return@stuur
            val zoek = mac.lowercase()
            for (i in 0 until lijst.length()) {
                val d = lijst.getJSONObject(i)
                val c = d.optJSONArray("connections") ?: continue
                for (j in 0 until c.length()) {
                    if (c.getJSONArray(j).optString(1).lowercase() == zoek) { apparaat = d.getString("id"); return@stuur }
                }
            }
        }
    }
}

/** Heel kleine protobuf: precies genoeg voor de ESPHome-berichten. */
class Pb {
    private val b = ByteArrayOutputStream()

    fun u(veld: Int, v: Int) = apply { if (v != 0) { varint(b, (veld shl 3).toLong()); varint(b, v.toLong() and 0xffffffffL) } }
    fun b(veld: Int, v: Boolean) = apply { if (v) u(veld, 1) }
    fun s(veld: Int, v: String) = apply { if (v.isNotEmpty()) m(veld, v.toByteArray()) }
    fun m(veld: Int, v: ByteArray) = apply { varint(b, ((veld shl 3) or 2).toLong()); varint(b, v.size.toLong()); b.write(v) }
    fun f(veld: Int, v: Int) = apply { varint(b, ((veld shl 3) or 5).toLong()); repeat(4) { b.write((v shr (it * 8)) and 0xff) } }
    fun fl(veld: Int, v: Float) = f(veld, java.lang.Float.floatToIntBits(v))
    fun bytes(): ByteArray = b.toByteArray()

    companion object {
        fun varint(o: java.io.OutputStream, v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) { o.write(((x and 0x7f) or 0x80).toInt()); x = x ushr 7 }
            o.write(x.toInt())
        }

        /** Veldnummer → waarden (varint als Long, tekst/bytes als ByteArray, fixed32 als Int). */
        fun lees(d: ByteArray): Map<Int, List<Any>> {
            val r = HashMap<Int, MutableList<Any>>()
            var i = 0
            fun vi(): Long { var x = 0L; var s = 0; while (true) { val c = d[i++].toInt() and 0xff; x = x or ((c and 0x7f).toLong() shl s); if (c and 0x80 == 0) return x; s += 7 } }
            while (i < d.size) {
                val k = vi().toInt()
                val v: Any = when (k and 7) {
                    0 -> vi()
                    2 -> { val n = vi().toInt(); d.copyOfRange(i, i + n).also { i += n } }
                    5 -> ((d[i].toInt() and 0xff) or ((d[i + 1].toInt() and 0xff) shl 8) or ((d[i + 2].toInt() and 0xff) shl 16) or ((d[i + 3].toInt() and 0xff) shl 24)).also { i += 4 }
                    1 -> { i += 8; 0L }
                    else -> return r
                }
                r.getOrPut(k shr 3) { mutableListOf() }.add(v)
            }
            return r
        }

        fun int(m: Map<Int, List<Any>>, veld: Int): Int = when (val v = m[veld]?.firstOrNull()) { is Long -> v.toInt(); is Int -> v; else -> 0 }
        fun bool(m: Map<Int, List<Any>>, veld: Int) = int(m, veld) != 0
        fun str(m: Map<Int, List<Any>>, veld: Int) = (m[veld]?.firstOrNull() as? ByteArray)?.let { String(it) } ?: ""
        fun float(m: Map<Int, List<Any>>, veld: Int) = java.lang.Float.intBitsToFloat(int(m, veld))
    }
}
