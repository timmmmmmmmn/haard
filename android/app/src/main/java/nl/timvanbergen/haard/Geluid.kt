package nl.timvanbergen.haard

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Korte belletjes, ter plekke gemaakt: geen geluidsbestanden nodig. */
object Geluid {

    private const val RATE = 22050

    private val luister by lazy { maak(listOf(660.0 to 0.09, 990.0 to 0.16)) }
    private val klaar by lazy { maak(listOf(880.0 to 0.08, 660.0 to 0.14)) }
    private val fout by lazy { maak(listOf(330.0 to 0.12, 247.0 to 0.2)) }

    // Een belletje mag nooit de app laten vallen: lukt het maken of afspelen niet, dan gewoon geen belletje
    fun luister() { runCatching { speel(luister) } }
    fun klaar() { runCatching { speel(klaar) } }
    fun fout() { runCatching { speel(fout) } }

    /** Opeenvolgende tonen (frequentie, duur), elk met een zachte aanzet en uitsterven. */
    private fun maak(tonen: List<Pair<Double, Double>>): ShortArray {
        // Vaste buffer (hooguit een halve seconde per toon): geen lijst die kan blijven groeien
        val lengtes = tonen.map { (RATE * it.second).toInt().coerceIn(0, RATE / 2) }
        val uit = ShortArray(lengtes.sum())
        var k = 0
        tonen.forEachIndexed { j, (f, _) ->
            for (i in 0 until lengtes[j]) {
                val t = i.toDouble() / RATE
                val aanzet = minOf(1.0, i / (RATE * 0.008))
                val v = sin(2 * PI * f * t) * 0.6 + sin(4 * PI * f * t) * 0.12
                uit[k++] = (v * aanzet * exp(-t * 9) * 0.45 * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return uit
    }

    private fun speel(pcm: ShortArray) {
        if (Stilte.nu) return
        Thread {
            runCatching {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                track.write(pcm, 0, pcm.size)
                track.play()
                Thread.sleep(pcm.size * 1000L / RATE + 100)
                track.release()
            }
        }.start()
    }
}
