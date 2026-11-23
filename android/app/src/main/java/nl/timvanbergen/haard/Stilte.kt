package nl.timvanbergen.haard

import android.content.Context
import android.media.AudioManager
import java.util.Calendar

/**
 * Stille tijd: 's nachts maakt de tablet geen enkel geluid. Alle geluidsstromen gaan op stil,
 * Haard speelt geen belletjes en leest niets voor (het antwoord staat dan alleen op het scherm).
 * Het wekwoord blijft werken. Standaard 23:00–08:00, in het weekend tot 10:00.
 */
object Stilte {

    private val STROMEN = intArrayOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM,
        AudioManager.STREAM_RING, AudioManager.STREAM_ALARM,
    )
    private var gedempt: Boolean? = null // onbekend na een herstart: dan altijd één keer instellen

    val nu: Boolean get() = isStil(Calendar.getInstance())

    fun isStil(c: Calendar): Boolean {
        val p = Instellingen.prefs
        if (!p.getBoolean("stil", true)) return false
        val min = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val van = minuten(p.getString("stil_van", "23:00"))
        val dag = c.get(Calendar.DAY_OF_WEEK)
        val weekend = dag == Calendar.SATURDAY || dag == Calendar.SUNDAY
        val tot = minuten(p.getString(if (weekend) "stil_tot_weekend" else "stil_tot", if (weekend) "10:00" else "08:00"))
        // 's Avonds vanaf "van", 's ochtends tot "tot" (die van de dag van vandaag: zaterdagochtend = weekend)
        return min >= van || min < tot
    }

    private fun minuten(s: String?): Int {
        val d = (s ?: "0:0").split(":")
        return (d.getOrNull(0)?.toIntOrNull() ?: 0) * 60 + (d.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** Elke minuut aanroepen: dempt bij het begin van de stille tijd en zet het geluid daarna terug. */
    fun controleer(ctx: Context) {
        val stil = nu
        if (!stil && gedempt == false) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        // Extra slot: Android's eigen "Niet storen" op totale stilte (ook wekkers en meldingen van andere apps)
        if (nm.isNotificationPolicyAccessGranted) runCatching {
            nm.setInterruptionFilter(if (stil) android.app.NotificationManager.INTERRUPTION_FILTER_NONE
                else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        }
        if (stil) {
            if (gedempt != true) Media.actie("pauze")
            // Ook elke minuut opnieuw: als iets het volume tussendoor omhoog zet, gaat het weer op stil
            for (s in STROMEN) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_MUTE, 0) }
        } else {
            for (s in STROMEN) runCatching { audio.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) }
        }
        gedempt = stil
    }
}
