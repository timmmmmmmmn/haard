package nl.timvanbergen.haard

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import kotlin.math.abs
import kotlin.math.log10
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.UUID

/**
 * Het scherm van Haard: klok, weer, fotolijst, muziek en de assistent, als pagina in de app (assets/www)
 * onder een eigen adres. Altijd aan, schermvullend, zoals een Nest Hub.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var paginaKlaar = false
    private var loginStaat: String? = null

    private val vraagRechten = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) HaardService.herlaad(this)
        if (r.any { it.key.contains("IMAGES") || it.key.contains("STORAGE") }) Thread { Fotos.ververs(); stuurFotos() }.start()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Instellingen.init(this)
        inFront = true

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT

        web = WebView(this)
        setContentView(web)
        verbergBalken()

        web.setBackgroundColor(getColor(R.color.page_bg))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            userAgentString = "$userAgentString HaardApp/${Updater.currentName(this@MainActivity)}"
        }
        web.addJavascriptInterface(Brug(), "HaardNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = Client()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!(web.url ?: "").startsWith(THUIS)) {
                    web.loadUrl(THUIS) // terug van het inlogscherm van Home Assistant
                    return
                }
                web.evaluateJavascript("window.haardTerug ? haardTerug() : false") { }
            }
        })

        Bus.luisteraar = { soort, data ->
            if (paginaKlaar) web.evaluateJavascript("window.haard && haard.bericht(${JSONObject.quote(soort)}, $data)", null)
        }

        web.loadUrl(THUIS)
        vraagToestemming()
        HaardService.start(this)
        Media.start(this)
        Thread { Fotos.ververs(); stuurFotos() }.start()
        Updater.checkQuietly(this)
    }

    private fun vraagToestemming() {
        val nodig = mutableListOf(Manifest.permission.RECORD_AUDIO)
        nodig += if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 33) nodig += Manifest.permission.POST_NOTIFICATIONS
        val mist = nodig.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (mist.isNotEmpty()) vraagRechten.launch(mist.toTypedArray())
    }

    private fun verbergBalken() {
        WindowCompat.getInsetsController(window, web).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) verbergBalken()
    }

    // ---------- Helderheid volgt het licht in de kamer (de Fire heeft een lichtsensor) ----------
    private var vasteHelderheid: Float? = null
    private var helderheidDoel = 0.5f
    private var helderheidNu = -1f
    private val lichtSensor = object : SensorEventListener {
        // Donkere kamer ≈ 0–5 lux → heel zacht; lamp aan ≈ 100 lux → tweederde; daglicht → vol
        override fun onSensorChanged(e: SensorEvent) { helderheidDoel = (0.04f + 0.32f * log10(e.values[0] + 1f)).coerceIn(0.04f, 1f) }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }
    /** Rustig meebewegen (geen geflikker als er even iemand voor de sensor langs loopt). */
    private val volgLicht = object : Runnable {
        override fun run() {
            web.removeCallbacks(this)
            if (vasteHelderheid != null || !inFront) return
            val n = if (helderheidNu < 0) helderheidDoel else helderheidNu + (helderheidDoel - helderheidNu) * 0.08f
            if (abs(n - helderheidNu) > 0.004f) { helderheidNu = n; zetHelderheid(n) }
            web.postDelayed(this, 250)
        }
    }
    private fun zetHelderheid(w: Float) {
        window.attributes = window.attributes.apply { screenBrightness = w }
    }

    override fun onResume() {
        super.onResume()
        getSystemService(SensorManager::class.java)?.let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(lichtSensor, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        web.post(volgLicht)
        inFront = true
        Updater.resume(this)
        Media.start(this)
    }

    override fun onPause() {
        getSystemService(SensorManager::class.java)?.unregisterListener(lichtSensor)
        inFront = false
        super.onPause()
    }

    override fun onStop() {
        Updater.installIfReady(this)
        super.onStop()
    }

    override fun onDestroy() {
        Bus.luisteraar = null
        super.onDestroy()
    }

    /** Updates gaan meteen, tenzij Haard net met iemand praat: dan even later. */
    fun updateReady(@Suppress("UNUSED_PARAMETER") versie: String) {
        web.postDelayed(object : Runnable {
            override fun run() {
                if (HaardService.assistent?.fase == Assistent.Fase.RUST && !Stilte.nu) Updater.installIfReady(this@MainActivity)
                else web.postDelayed(this, if (Stilte.nu) 10 * 60_000L else 30_000L)
            }
        }, 5_000)
    }

    private fun stuurFotos() {
        Bus.stuur("fotos", JSONObject().put("aantal", Fotos.aantal).put("map", "Pictures/Haard"))
    }

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (url.startsWith(HomeAssistant.TERUG)) {
                val code = request.url.getQueryParameter("code")
                val staat = request.url.getQueryParameter("state")
                view.loadUrl(THUIS)
                if (code != null && staat == loginStaat) {
                    loginStaat = null
                    Thread {
                        val ok = HomeAssistant.ruilCode(code)
                        js("window.haard && haard.ingelogd(${ok})")
                    }.start()
                }
                return true
            }
            // Gewone links (zoals Broncode) openen in de browser
            if (url.startsWith(THUIS) || url.startsWith(Instellingen.haUrl)) return false
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }
            return true
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            if (url.host != HOST) return null
            val pad = url.path ?: "/"
            return when {
                pad.startsWith("/__foto/") -> {
                    val n = pad.removePrefix("/__foto/").toIntOrNull() ?: return leeg()
                    val breed = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                    Fotos.jpeg(n, breed)?.let { afbeelding(it) } ?: leeg()
                }
                pad.startsWith("/__hoes") -> Media.hoes?.let { afbeelding(it) } ?: leeg()
                // Plaatjes uit Home Assistant via de app: dan zijn ze van dezelfde herkomst (kleuren uit de hoes halen mag)
                pad.startsWith("/__ha/") -> HomeAssistant.plaatje(request.url.toString().substringAfter("/__ha"))?.let { afbeelding(it) } ?: leeg()
                pad == "/" || pad.isEmpty() -> Bundel.serve(this@MainActivity, "index.html", null)
                else -> Bundel.serve(this@MainActivity, pad, request.requestHeaders["Range"]) ?: leeg()
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            paginaKlaar = url.startsWith(THUIS)
            if (paginaKlaar) Bus.herhaal()
        }
    }

    private fun afbeelding(bytes: ByteArray) = WebResourceResponse("image/jpeg", null, 200, "OK",
        mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))

    private fun leeg() = WebResourceResponse("text/plain", "utf-8", 404, "Niet gevonden", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** Wat de pagina aan de app kan vragen (window.HaardNative). */
    inner class Brug {
        @JavascriptInterface fun versie(): String = Updater.currentName(this@MainActivity)

        @JavascriptInterface fun instellingen(): String = Instellingen.alles()
            .put("ingelogd", HomeAssistant.ingelogd)
            .put("token", Instellingen.prefs.getString("token", null) != null)
            .put("media_toegang", Media.heeftToegang(this@MainActivity))
            .put("gemini", Gemini.sleutel != null)
            .toString()

        @JavascriptInterface fun zet(sleutel: String, waarde: String) {
            val oud = Instellingen.haUrl
            Instellingen.zet(sleutel, waarde)
            when (sleutel) {
                "wekwoord" -> HaardService.herlaad(this@MainActivity)
                "ha_url" -> if (Instellingen.haUrl != oud) HomeAssistant.Socket.opnieuw()
                "stil", "stil_van", "stil_tot", "stil_tot_weekend" -> Stilte.controleer(this@MainActivity)
            }
        }

        /** Inloggen bij Home Assistant op de eigen inlogpagina van HA. */
        @JavascriptInterface fun login() = runOnUiThread {
            val staat = UUID.randomUUID().toString()
            loginStaat = staat
            web.loadUrl(HomeAssistant.loginUrl(staat))
        }

        @JavascriptInterface fun token(token: String) = HomeAssistant.zetToken(token)

        @JavascriptInterface fun uitloggen() = HomeAssistant.uitloggen()

        /** De sleutel voor Gemini (leeg = wissen). De pagina kan hem alleen zetten, nooit teruglezen. */
        @JavascriptInterface fun gemini(sleutel: String) {
            Instellingen.prefs.edit().apply { if (sleutel.isBlank()) remove("gemini_sleutel") else putString("gemini_sleutel", sleutel.trim()) }.apply()
        }

        /** API-verzoek naar Home Assistant; het antwoord komt terug via haard.antwoord(id, status, tekst). */
        @JavascriptInterface fun ha(id: Int, methode: String, pad: String, body: String) {
            Thread {
                val (status, tekst) = HomeAssistant.vraag(methode, pad, body.ifEmpty { null })
                js("haard.antwoord($id, $status, ${JSONObject.quote(tekst)})")
            }.start()
        }

        /** Bericht over de websocket (bijv. de lijst met spraakassistenten); eerste antwoord gaat terug. */
        @JavascriptInterface fun ws(id: Int, bericht: String) {
            var klaar = false
            val msg = runCatching { JSONObject(bericht) }.getOrElse { return }
            var wsId = 0
            wsId = HomeAssistant.Socket.stuur(msg) { m ->
                if (!klaar && m.optString("type") == "result") {
                    klaar = true
                    HomeAssistant.Socket.vergeet(wsId)
                    js("haard.antwoord($id, ${if (m.optBoolean("success")) 200 else 500}, ${JSONObject.quote(m.toString())})")
                }
            }
        }

        @JavascriptInterface fun luister() {
            HaardService.assistent?.start() ?: HaardService.start(this@MainActivity)
        }

        @JavascriptInterface fun stop() {
            HaardService.assistent?.stop()
        }

        @JavascriptInterface fun media(actie: String) = Media.actie(actie)

        @JavascriptInterface fun timerStop() = Esphome.stopBel()

        @JavascriptInterface fun mediaToegang() = runOnUiThread {
            runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        @JavascriptInterface fun meten(aan: Boolean) {
            HaardService.meten = aan
        }

        @JavascriptInterface fun fotos() {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED ||
                Build.VERSION.SDK_INT < 33 && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread { vraagToestemming() }
                return
            }
            Thread { Fotos.ververs(); stuurFotos() }.start()
        }

        /** Nachtstand: het scherm zo donker als het kan; anders (-1) volgt het het licht in de kamer. */
        @JavascriptInterface fun helderheid(waarde: Float) = runOnUiThread {
            vasteHelderheid = if (waarde < 0) null else waarde.coerceIn(0.01f, 1f)
            vasteHelderheid?.let { zetHelderheid(it) } ?: run { helderheidNu = -1f; volgLicht.run() }
        }

        @JavascriptInterface fun update() = runOnUiThread { Updater.check(this@MainActivity, manual = true) }

        @JavascriptInterface fun open(url: String) = runOnUiThread {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    companion object {
        private const val HOST = "haard.timvanbergen.nl"
        private const val THUIS = "https://$HOST/"

        @Volatile var inFront = false
    }
}
