package nl.timvanbergen.haard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * De stem van Haard (Piper, stem Ronnie) op de tablet zelf, zonder server. Piper maakt het geluid zin voor zin:
 * de eerste zin klinkt al terwijl de volgende nog gemaakt wordt. 's Nachts zegt hij nooit iets.
 */
object Stem {

    private val draad = HandlerThread("stem").apply { start() }
    private val h = Handler(draad.looper)
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var beurt = 0
    @Volatile private var track: AudioTrack? = null

    val klaar: Boolean get() = tts != null

    /** Het model laden (een paar seconden); daarna kan [spreek]. */
    fun laad(map: File) = h.post {
        if (tts != null) return@post
        val onnx = map.listFiles()?.firstOrNull { it.name.endsWith(".onnx") } ?: return@post
        tts = runCatching {
            OfflineTts(config = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(map, "tokens.txt").path,
                        dataDir = File(map, "espeak-ng-data").path),
                    // Vier kernen, maar één laten we vrij voor het wekwoord
                    numThreads = 3,
                ),
                maxNumSentences = 1,
            ))
        }.onFailure { android.util.Log.e("Haard", "Stem laden mislukt", it) }.getOrNull()
        // Eén keer opwarmen (zonder geluid): de eerste zin is anders merkbaar trager
        tts?.let { t -> runCatching { t.generate("Hallo.") } }
    }

    /** Zegt de tekst; [klaar] volgt als alles gezegd is, gestopt werd of het niet lukte (op de stem-draad). */
    fun spreek(tekst: String, klaar: () -> Unit) {
        val mijn = ++beurt
        h.post {
            val t = tts
            if (t == null || Stilte.nu || mijn != beurt || tekst.isBlank()) return@post klaar()
            val sr = t.sampleRate()
            val buf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val tr = runCatching {
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT).build())
                    .setBufferSizeInBytes(maxOf(buf, sr * 4 / 2))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }.getOrNull() ?: return@post klaar()
            track = tr
            // Piper maakt de zinnen, een tweede draad speelt ze af: zo wacht het maken nooit op het afspelen
            val rij = LinkedBlockingQueue<FloatArray>()
            var frames = 0
            val speler = Thread({
                runCatching {
                    tr.play()
                    while (mijn == beurt) {
                        val stuk = rij.take()
                        if (stuk.isEmpty()) break
                        tr.write(stuk, 0, stuk.size, AudioTrack.WRITE_BLOCKING)
                        frames += stuk.size
                    }
                    // Wachten tot het laatste stukje echt uit de luidspreker is
                    val tot = System.currentTimeMillis() + 3000
                    while (mijn == beurt && tr.playbackHeadPosition < frames && System.currentTimeMillis() < tot) Thread.sleep(40)
                }
            }, "stem-speler").apply { start() }
            val begin = System.currentTimeMillis()
            var gemaakt = 0
            runCatching {
                // Een echt object, geen lambda: de native code zoekt precies invoke(float[]): Integer
                t.generateWithCallback(uitspraak(tekst), callback = object : (FloatArray) -> Int {
                    override fun invoke(stuk: FloatArray): Int {
                        if (mijn != beurt || Stilte.nu) return 0
                        rij.put(stuk.copyOf())
                        gemaakt += stuk.size
                        return 1
                    }
                })
            }
            android.util.Log.i("Haard", "Stem: ${"%.1f".format(gemaakt / sr.toFloat())} s spraak gemaakt in ${"%.1f".format((System.currentTimeMillis() - begin) / 1000f)} s")
            rij.put(FloatArray(0))
            speler.join(120_000)
            runCatching { tr.stop() }
            tr.release()
            if (track === tr) track = null
            klaar()
        }
    }

    /** Meteen stil (tik op het scherm, "stop", of een nieuwe vraag). */
    fun stop() {
        beurt++
        track?.let { runCatching { it.pause(); it.flush(); it.stop() } }
    }

    /** Tekens die Piper anders raar uitspreekt. */
    fun uitspraak(s: String): String = s
        .replace(Regex("(\\d)\\s*°\\s*C\\b"), "$1 graden")
        .replace(Regex("(\\d)\\s*°"), "$1 graden")
        .replace(Regex("(\\d)\\s*%"), "$1 procent")
        .replace(Regex("(\\d)\\s*km/u\\b"), "$1 kilometer per uur")
        .replace(Regex("(\\d)\\s*km\\b"), "$1 kilometer")
        .replace(Regex("(\\d)[:.](\\d\\d)\\s*uur\\b"), "$1 uur $2")
        .replace("&", " en ")
        .replace(Regex("[*_#`~>|]"), "")
}
