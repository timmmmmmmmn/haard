package nl.timvanbergen.haard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draait altijd op de achtergrond: houdt de microfoon open, luistert op de tablet zelf naar het
 * wekwoord en geeft het geluid na het wekwoord door aan de assistent.
 */
class HaardService : Service() {

    @Volatile private var aan = false
    private var lus: Thread? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Instellingen.init(this)
        assistent = Assistent(applicationContext)
        voorgrond()
        wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "haard:mic").apply {
            setReferenceCounted(false)
            acquire()
        }
        HomeAssistant.Socket.start()
        SnellePijplijn.zoek()
        Esphome.start(this)
        start()
        stilteKlok.run()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == HERLAAD) {
            stopLus()
            start()
        }
        return START_STICKY
    }

    private val klok = android.os.Handler(android.os.Looper.getMainLooper())
    private val stilteKlok = object : Runnable {
        override fun run() {
            Stilte.controleer(this@HaardService)
            klok.postDelayed(this, 60_000)
        }
    }

    override fun onDestroy() {
        klok.removeCallbacks(stilteKlok)
        stopLus()
        wake?.release()
        assistent = null
        super.onDestroy()
    }

    private fun voorgrond() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(KANAAL, "Luisteren", NotificationManager.IMPORTANCE_MIN).apply {
            setShowBadge(false)
        })
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, KANAAL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Haard luistert")
            .setContentText("Zeg het wekwoord om iets te vragen")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else startForeground(1, n)
    }

    private fun start() {
        // Microfoon uit (schakelaar in Home Assistant): helemaal niet opnemen
        if (Instellingen.prefs.getBoolean("microfoon_uit", false)) {
            Bus.stuur("wekwoord", JSONObject().put("aan", false).put("fout", "Microfoon staat uit"))
            return
        }
        aan = true
        lus = Thread({ luister() }, "microfoon").apply { start() }
    }

    private fun stopLus() {
        aan = false
        lus?.interrupt()
        lus?.join(1500)
        lus = null
    }

    @SuppressLint("MissingPermission")
    private fun luister() {
        val ww = if (Instellingen.wekwoord) runCatching { Wekwoord(this, Wekwoord.eigenModel(this)) }
            .onFailure { Bus.stuur("wekwoord", JSONObject().put("fout", it.message ?: "Wekwoord kon niet laden")) }
            .getOrNull() else null
        Bus.stuur("wekwoord", JSONObject().put("aan", ww != null).put("model", ww?.naam ?: ""))

        val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        var rec: AudioRecord? = null
        for (bron in listOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)) {
            val r = runCatching { AudioRecord(bron, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Wekwoord.BLOK * 8)) }.getOrNull()
            if (r?.state == AudioRecord.STATE_INITIALIZED) { rec = r; break }
            r?.release()
        }
        if (rec == null) {
            Bus.stuur("wekwoord", JSONObject().put("aan", false).put("fout", "Geen toegang tot de microfoon"))
            ww?.close()
            return
        }

        val pcm = ShortArray(Wekwoord.BLOK)
        val bytes = ByteBuffer.allocate(Wekwoord.BLOK * 2).order(ByteOrder.LITTLE_ENDIAN)
        var rustTot = 0L
        var reeks = 0
        var piek = 0f
        var piekTijd = 0L
        rec.startRecording()
        try {
            while (aan) {
                var n = 0
                while (n < pcm.size && aan) {
                    val r = rec.read(pcm, n, pcm.size - n)
                    if (r <= 0) break
                    n += r
                }
                if (n < pcm.size) {
                    if (aan) SystemClock.sleep(50)
                    continue
                }
                val a = assistent ?: break
                if (a.fase == Assistent.Fase.LUISTEREN) {
                    bytes.clear()
                    bytes.asShortBuffer().put(pcm)
                    a.geluid(bytes.array().copyOf())
                }
                if (ww == null) continue
                val kans = runCatching { ww.verwerk(pcm) }.getOrDefault(0f)
                val nu = SystemClock.elapsedRealtime()
                // De hoogste waarde van de laatste seconde gaat naar de instellingen, om de gevoeligheid te testen
                if (kans > piek || nu - piekTijd > 1000) {
                    piek = kans
                    piekTijd = nu
                    if (meten) Bus.stuur("meter", JSONObject().put("kans", kans.toDouble()))
                }
                // Het wekwoord telt bij twee blokken achter elkaar boven de drempel, of één heel duidelijke piek
                // ("Hey Fire" geeft korte pieken). 's Nachts allebei strenger, zodat een los geluidje nooit genoeg is.
                val stil = Stilte.nu
                val drempel = if (stil) maxOf(Instellingen.drempel, 0.7f) else Instellingen.drempel
                val piek = if (stil) 0.9f else maxOf(Instellingen.drempel + 0.2f, 0.7f)
                reeks = if (kans >= drempel) reeks + 1 else 0
                if ((reeks >= 2 || kans >= piek) && nu > rustTot &&
                    (a.fase == Assistent.Fase.RUST || a.fase == Assistent.Fase.SPREKEN)) {
                    reeks = 0
                    rustTot = nu + 2000
                    ww.leeg()
                    a.start()
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            ww?.close()
        }
    }

    companion object {
        private const val KANAAL = "luisteren"
        private const val HERLAAD = "nl.timvanbergen.haard.HERLAAD"

        @Volatile var assistent: Assistent? = null
            private set

        /** De instellingen-pagina wil zien hoe goed het wekwoord herkend wordt. */
        @Volatile var meten = false

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, HaardService::class.java))
        }

        /** Wekwoord aan/uit of een nieuw model: de microfoonlus opnieuw opstarten. */
        fun herlaad(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, HaardService::class.java).setAction(HERLAAD))
        }
    }
}
