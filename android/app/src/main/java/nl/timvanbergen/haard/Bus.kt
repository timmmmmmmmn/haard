package nl.timvanbergen.haard

import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * Berichten van de achtergronddelen (assistent, muziek, Home Assistant) naar de pagina.
 * Het laatste bericht per soort wordt bewaard, zodat een pagina die (opnieuw) laadt meteen bij is.
 */
object Bus {
    private val main = Handler(Looper.getMainLooper())
    private val laatste = LinkedHashMap<String, JSONObject>()
    // Losse gebeurtenissen: na het herladen van de pagina niet opnieuw tonen
    private val LOS = setOf("assist", "meter")
    @Volatile var luisteraar: ((String, JSONObject) -> Unit)? = null

    fun stuur(soort: String, data: JSONObject) {
        main.post {
            if (soort !in LOS) laatste[soort] = data
            luisteraar?.invoke(soort, data)
        }
    }

    /** Alles opnieuw sturen, bijvoorbeeld als de pagina net geladen is (op de hoofddraad aanroepen). */
    fun herhaal() {
        val l = luisteraar ?: return
        for ((soort, data) in laatste.toList()) l(soort, data)
    }
}
