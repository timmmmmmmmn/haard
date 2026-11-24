package nl.timvanbergen.haard

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Bestaat alleen zodat Haard mag zien wat Spotify (of een andere app) nu afspeelt. */
class MediaLuisteraar : NotificationListenerService() {
    override fun onListenerConnected() {
        Media.start(this)
    }
}

/** "Wat speelt er nu": titel, artiest en hoes van de app die muziek afspeelt, plus bediening. */
object Media {

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private var ctrl: MediaController? = null
    private lateinit var audio: AudioManager

    @Volatile var hoes: ByteArray? = null
        private set
    private var hoesVan: Any? = null
    private var versie = 0

    fun heeftToegang(ctx: Context) = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    private val sessiesVeranderd = MediaSessionManager.OnActiveSessionsChangedListener { kies(it.orEmpty()) }

    private val terugroep = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = stuur()
        override fun onMetadataChanged(metadata: MediaMetadata?) = stuur()
        override fun onSessionDestroyed() {
            kies(manager?.getActiveSessions(ComponentName(appCtx ?: return, MediaLuisteraar::class.java)).orEmpty())
        }
    }

    private var appCtx: Context? = null

    fun start(ctx: Context) = main.post {
        val app = ctx.applicationContext
        appCtx = app
        audio = app.getSystemService(AudioManager::class.java)
        if (!heeftToegang(app)) {
            Bus.stuur("media", JSONObject().put("toegang", false))
            return@post
        }
        val comp = ComponentName(app, MediaLuisteraar::class.java)
        val m = app.getSystemService(MediaSessionManager::class.java)
        if (manager == null) {
            runCatching { m.addOnActiveSessionsChangedListener(sessiesVeranderd, comp, main) }
            manager = m
        }
        kies(runCatching { m.getActiveSessions(comp) }.getOrDefault(emptyList()))
    }

    /** De sessie die speelt heeft voorrang; anders de bovenste (meest recente). */
    private fun kies(sessies: List<MediaController>) {
        val eigen = appCtx?.packageName
        val lijst = sessies.filter { it.packageName != eigen }
        val nieuw = lijst.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: lijst.firstOrNull()
        if (nieuw?.sessionToken != ctrl?.sessionToken) {
            ctrl?.unregisterCallback(terugroep)
            ctrl = nieuw
            nieuw?.registerCallback(terugroep, main)
        }
        stuur()
    }

    private fun stuur() {
        val c = ctrl
        val o = JSONObject().put("toegang", true)
        if (c == null) {
            Bus.stuur("media", o.put("actief", false))
            return
        }
        val md = c.metadata
        val st = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp !== hoesVan) {
            hoesVan = bmp
            hoes = bmp?.let { b ->
                ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 88, it) }.toByteArray()
            }
            versie++
        }
        val app = runCatching {
            val pm = appCtx!!.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString()
        }.getOrDefault(c.packageName)
        o.put("actief", md != null && st != PlaybackState.STATE_NONE && st != PlaybackState.STATE_STOPPED)
            .put("speelt", st == PlaybackState.STATE_PLAYING || st == PlaybackState.STATE_BUFFERING)
            .put("titel", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artiest", md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
            .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("app", app)
            .put("hoes", if (hoes != null) versie else 0)
        // Voor de voortgangsbalk: positie op een wandkloktijd, zodat de pagina zelf kan doortellen
        c.playbackState?.let { ps ->
            val verstreken = android.os.SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime
            val pos = if (st == PlaybackState.STATE_PLAYING) ps.position + (verstreken * ps.playbackSpeed).toLong() else ps.position
            o.put("positie", pos.coerceAtLeast(0)).put("tijdstip", System.currentTimeMillis())
        }
        o.put("duur", md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0)
        Bus.stuur("media", o)
    }

    val speelt: Boolean get() = ctrl?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** Bediening vanaf het scherm of met de stem. */
    fun actie(wat: String) = main.post {
        val t = ctrl?.transportControls
        when (wat) {
            "speel" -> t?.play()
            "pauze" -> t?.pause()
            "wissel" -> if (speelt) t?.pause() else t?.play()
            "volgende" -> t?.skipToNext()
            "vorige" -> t?.skipToPrevious()
            "harder" -> volume(+2)
            "zachter" -> volume(-2)
        }
    }

    private fun volume(stappen: Int) {
        if (!::audio.isInitialized) return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val nu = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (nu + stappen).coerceIn(0, max), AudioManager.FLAG_SHOW_UI)
    }

    /** Volume op een schaal van 0 tot 10, zoals je het zegt: "volume op 4". */
    fun zetVolume(ctx: Context, tiende: Int) = main.post {
        val a = ctx.getSystemService(AudioManager::class.java)
        val max = a.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        a.setStreamVolume(AudioManager.STREAM_MUSIC, (max * tiende.coerceIn(0, 10) + 5) / 10, AudioManager.FLAG_SHOW_UI)
    }
}
