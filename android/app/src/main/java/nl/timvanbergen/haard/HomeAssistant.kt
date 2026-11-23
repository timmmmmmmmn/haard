package nl.timvanbergen.haard

import android.os.Handler
import android.os.HandlerThread
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Verbinding met Home Assistant: inloggen (zoals de officiële app, met een eigen client-id),
 * gewone API-verzoeken en één blijvende websocket voor de spraakassistent.
 */
object HomeAssistant {

    // Bestaat niet echt als website: de app vangt dit adres zelf af. Home Assistant accepteert het
    // omdat de terugkeer-URL op hetzelfde domein staat als de client-id.
    const val CLIENT = "https://haard.timvanbergen.nl/"
    const val TERUG = "https://haard.timvanbergen.nl/auth"

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val prefs get() = Instellingen.prefs
    private val draad = HandlerThread("ha").apply { start() }
    private val h = Handler(draad.looper)

    val ingelogd: Boolean get() = prefs.getString("refresh", null) != null || prefs.getString("token", null) != null

    fun loginUrl(staat: String): String =
        "${Instellingen.haUrl}/auth/authorize?response_type=code&client_id=${enc(CLIENT)}&redirect_uri=${enc(TERUG)}&state=$staat"

    /** De code uit de terugkeer-URL inruilen voor tokens. Niet op de hoofddraad aanroepen. */
    fun ruilCode(code: String): Boolean {
        val body = FormBody.Builder().add("grant_type", "authorization_code").add("code", code).add("client_id", CLIENT).build()
        val json = tokenVerzoek(body) ?: return false
        prefs.edit()
            .putString("refresh", json.optString("refresh_token"))
            .putString("access", json.getString("access_token"))
            .putLong("verloopt", System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000)
            .remove("token")
            .apply()
        Socket.opnieuw()
        return true
    }

    /** Een langlevend token uit het profiel van Home Assistant, als alternatief voor inloggen. */
    fun zetToken(token: String) {
        prefs.edit().putString("token", token.trim()).remove("refresh").remove("access").apply()
        Socket.opnieuw()
    }

    fun uitloggen() {
        prefs.edit().remove("token").remove("refresh").remove("access").apply()
        Socket.sluit()
        Bus.stuur("ha", JSONObject().put("verbonden", false).put("ingelogd", false))
    }

    /** Geldig toegangstoken, zo nodig vernieuwd. Niet op de hoofddraad aanroepen. */
    @Synchronized
    fun token(): String? {
        prefs.getString("token", null)?.let { return it }
        val refresh = prefs.getString("refresh", null) ?: return null
        val access = prefs.getString("access", null)
        if (access != null && System.currentTimeMillis() < prefs.getLong("verloopt", 0) - 60_000) return access
        val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", CLIENT).build()
        val json = tokenVerzoek(body) ?: return null
        val nieuw = json.getString("access_token")
        prefs.edit().putString("access", nieuw)
            .putLong("verloopt", System.currentTimeMillis() + json.optLong("expires_in", 1800) * 1000).apply()
        return nieuw
    }

    private fun tokenVerzoek(body: FormBody): JSONObject? = try {
        http.newCall(Request.Builder().url("${Instellingen.haUrl}/auth/token").post(body).build()).execute().use { r ->
            if (r.code == 400 || r.code == 403) {
                // Token ingetrokken in Home Assistant: opnieuw inloggen nodig
                if (body.size > 0 && body.value(0) == "refresh_token") prefs.edit().remove("refresh").remove("access").apply()
                null
            } else if (r.isSuccessful) JSONObject(r.body!!.string()) else null
        }
    } catch (_: Exception) {
        null
    }

    /** Een API-verzoek (pad zoals /api/states). Geeft status en tekst terug; 0 = geen verbinding. */
    fun vraag(methode: String, pad: String, body: String?): Pair<Int, String> {
        val token = token() ?: return 401 to """{"message":"Niet ingelogd"}"""
        val b = Request.Builder().url(Instellingen.haUrl + pad).header("Authorization", "Bearer $token")
        if (methode == "GET") b.get() else b.method(methode, (body ?: "").toRequestBody("application/json".toMediaType()))
        return try {
            http.newCall(b.build()).execute().use { it.code to (it.body?.string() ?: "") }
        } catch (e: Exception) {
            0 to JSONObject().put("message", e.message ?: "Geen verbinding").toString()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /**
     * De websocket. Blijft open zolang de app draait en verbindt vanzelf opnieuw.
     * Berichten krijgen een id; de antwoorden en gebeurtenissen gaan naar de bijbehorende handler.
     */
    object Socket {
        private var ws: WebSocket? = null
        @Volatile var klaar = false
            private set
        private var volgende = 1
        private val handlers = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
        private val wachtrij = ArrayList<JSONObject>()
        private var pogingen = 0
        private var generatie = 0

        fun start() = h.post { if (ws == null) verbind() }

        fun opnieuw() = h.post {
            sluitIntern()
            verbind()
        }

        fun sluit() = h.post { sluitIntern() }

        private fun sluitIntern() {
            generatie++
            ws?.close(1000, null)
            ws = null
            klaar = false
            faalAlles("Verbinding gesloten")
        }

        private fun verbind() {
            if (!ingelogd) return
            val gen = ++generatie
            val url = Instellingen.haUrl.replaceFirst("http", "ws") + "/api/websocket"
            ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    h.post { if (gen == generatie) ontvang(webSocket, JSONObject(text)) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    h.post { if (gen == generatie) verloren() }
                }

                // HA sluit de verbinding (herstart, update): meteen zelf ook sluiten en opnieuw verbinden
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                    h.post { if (gen == generatie) verloren() }
                }
            })
        }

        private fun ontvang(socket: WebSocket, m: JSONObject) {
            when (m.optString("type")) {
                "auth_required" -> {
                    val token = token()
                    if (token == null) {
                        status(false, "Opnieuw inloggen nodig")
                        sluitIntern()
                    } else socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
                }
                "auth_ok" -> {
                    klaar = true
                    pogingen = 0
                    status(true, null)
                    for (msg in wachtrij) socket.send(msg.toString())
                    wachtrij.clear()
                }
                "auth_invalid" -> {
                    // Het toegangstoken is net verlopen of ingetrokken: vernieuwen en nog één keer proberen
                    prefs.edit().remove("access").apply()
                    status(false, "Inloggen geweigerd")
                    verloren()
                }
                else -> {
                    val id = m.optInt("id", -1)
                    handlers[id]?.invoke(m)
                }
            }
        }

        private fun verloren() {
            ws = null
            klaar = false
            faalAlles("Verbinding met Home Assistant verbroken")
            status(false, null)
            val wacht = minOf(30_000L, 1_000L shl minOf(pogingen++, 5))
            val gen = generatie
            h.postDelayed({ if (gen == generatie && ws == null) verbind() }, wacht)
        }

        private fun faalAlles(reden: String) {
            val fout = JSONObject().put("type", "result").put("success", false)
                .put("error", JSONObject().put("code", "verbinding").put("message", reden))
            for ((_, handler) in handlers) handler(fout)
            handlers.clear()
            wachtrij.clear()
        }

        private fun status(verbonden: Boolean, melding: String?) {
            Bus.stuur("ha", JSONObject().put("verbonden", verbonden).put("ingelogd", ingelogd).put("melding", melding ?: ""))
        }

        /** Bericht sturen; handler krijgt het antwoord en eventuele gebeurtenissen. Geeft het id terug. */
        fun stuur(msg: JSONObject, handler: (JSONObject) -> Unit): Int {
            val id = synchronized(this) { volgende++ }
            msg.put("id", id)
            handlers[id] = handler
            h.post {
                val socket = ws
                if (klaar && socket != null) socket.send(msg.toString())
                else {
                    wachtrij.add(msg)
                    if (socket == null) verbind()
                }
            }
            return id
        }

        /** Niet meer geïnteresseerd: ook niet meer versturen als hij nog op een verbinding wacht. */
        fun vergeet(id: Int) {
            handlers.remove(id)
            h.post { wachtrij.removeAll { it.optInt("id") == id } }
        }

        fun binair(bytes: ByteArray) {
            h.post { if (klaar) ws?.send(bytes.toByteString()) }
        }
    }
}
