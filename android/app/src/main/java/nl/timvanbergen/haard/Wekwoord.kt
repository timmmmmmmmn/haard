package nl.timvanbergen.haard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import java.io.File
import java.nio.FloatBuffer

/**
 * openWakeWord op de tablet zelf: er gaat pas geluid naar de server nadat het wekwoord is gehoord.
 * Zelfde stappen als openwakeword/utils.py (AudioFeatures, streaming):
 *  per 80 ms (1280 samples) → melspectrogram (8 frames × 32) → elke 76 frames een embedding (96)
 *  → de laatste 16 embeddings → het wekwoordmodel → kans 0..1.
 */
class Wekwoord(ctx: Context, model: File?) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val opties = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(1)
        setInterOpNumThreads(1)
    }
    private val mel = sessie(ctx.assets.open("ww/melspectrogram.onnx").use { it.readBytes() })
    private val emb = sessie(ctx.assets.open("ww/embedding_model.onnx").use { it.readBytes() })
    private val ww = sessie(model?.readBytes() ?: ctx.assets.open("ww/$STANDAARD").use { it.readBytes() })

    /** Naam van het actieve model, voor in de instellingen. */
    val naam: String = model?.nameWithoutExtension ?: STANDAARD.substringBefore(".onnx")

    private val nFeatures = ((ww.inputInfo.values.first().info as TensorInfo).shape[1]).toInt()

    // De laatste 480 samples van het vorige blok: het melspectrogram heeft wat overlap nodig.
    private val vorige = FloatArray(OVERLAP)
    private val melBuf = ArrayDeque<FloatArray>()
    private val features = ArrayDeque<FloatArray>()
    private val invoer = FloatArray(BLOK + OVERLAP)

    // Zuinig bij stilte: het melspectrogram (goedkoop) loopt altijd door, maar de embedding (duur) wordt
    // pas uitgerekend als er geluid is, en dan meteen voor alle overgeslagen blokken. De uitkomst is
    // daardoor precies hetzelfde als altijd rekenen, maar in een stille kamer kost het bijna niets.
    private var framesTotaal = 0L                    // hoeveel mel-frames er ooit zijn binnengekomen
    private val wachtend = ArrayDeque<Long>()        // einde (in frames) van blokken zonder embedding
    private var ruis = 200f                          // geschat achtergrondgeluid (RMS)
    private var luidTot = 0                          // na geluid nog even blijven rekenen (blokken)
    private val melMax get() = MEL_VENSTER + 10 * nFeatures

    private fun sessie(bytes: ByteArray) = env.createSession(bytes, opties)

    /** Eén blok van precies 1280 samples (16 kHz, mono). Geeft de kans op het wekwoord terug. */
    fun verwerk(pcm: ShortArray): Float {
        require(pcm.size == BLOK)
        System.arraycopy(vorige, 0, invoer, 0, OVERLAP)
        var som = 0.0
        for (i in 0 until BLOK) { val s = pcm[i].toFloat(); invoer[OVERLAP + i] = s; som += s * s }
        System.arraycopy(invoer, BLOK, vorige, 0, OVERLAP)
        val rms = Math.sqrt(som / BLOK).toFloat()
        ruis = if (rms < ruis) ruis * 0.9f + rms * 0.1f else ruis * 0.998f + rms * 0.002f

        // Melspectrogram: [1, samples] → [1, 1, frames, 32], daarna x/10 + 2 zoals in openWakeWord
        OnnxTensor.createTensor(env, FloatBuffer.wrap(invoer), longArrayOf(1, invoer.size.toLong())).use { t ->
            mel.run(mapOf(mel.inputNames.first() to t)).use { r ->
                val uit = (r[0] as OnnxTensor).floatBuffer
                val frames = uit.remaining() / 32
                for (f in 0 until frames) {
                    val rij = FloatArray(32)
                    for (j in 0 until 32) rij[j] = uit.get() / 10f + 2f
                    melBuf.addLast(rij)
                }
                framesTotaal += frames
            }
        }
        while (melBuf.size > melMax) melBuf.removeFirst()
        if (melBuf.size < MEL_VENSTER) return 0f

        // Stil (niet duidelijk boven het achtergrondgeluid): embedding uitstellen
        if (rms > ruis * 2f + 40f) luidTot = 20 else if (luidTot > 0) luidTot--
        wachtend.addLast(framesTotaal)
        if (luidTot == 0) {
            if (wachtend.size > nFeatures) { wachtend.removeFirst(); features.clear() }
            return 0f
        }
        // Geluid: alle uitgestelde blokken (in volgorde) en dit blok uitrekenen
        val eerste = framesTotaal - melBuf.size
        while (wachtend.isNotEmpty()) {
            val eind = wachtend.removeFirst()
            val begin = (eind - MEL_VENSTER - eerste).toInt()
            if (begin < 0) continue
            embedding(begin)
        }
        while (features.size > nFeatures) features.removeFirst()
        if (features.size < nFeatures) return 0f

        val x = FloatArray(nFeatures * 96)
        features.forEachIndexed { i, f -> System.arraycopy(f, 0, x, i * 96, 96) }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, nFeatures.toLong(), 96)).use { t ->
            ww.run(mapOf(ww.inputNames.first() to t)).use { r ->
                return (r[0] as OnnxTensor).floatBuffer.get()
            }
        }
    }

    /** Embedding van 76 mel-frames vanaf positie [begin] in de buffer: [1, 76, 32, 1] → 96 getallen. */
    private fun embedding(begin: Int) {
        val venster = FloatArray(MEL_VENSTER * 32)
        for (i in 0 until MEL_VENSTER) System.arraycopy(melBuf[begin + i], 0, venster, i * 32, 32)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(venster), longArrayOf(1, MEL_VENSTER.toLong(), 32, 1)).use { t ->
            emb.run(mapOf(emb.inputNames.first() to t)).use { r ->
                val uit = (r[0] as OnnxTensor).floatBuffer
                features.addLast(FloatArray(96) { uit.get() })
            }
        }
    }

    /** Na een detectie opnieuw beginnen, zodat hetzelfde woord niet twee keer telt. */
    fun leeg() {
        features.clear()
        wachtend.clear()
    }

    override fun close() {
        mel.close()
        emb.close()
        ww.close()
    }

    companion object {
        const val BLOK = 1280
        private const val OVERLAP = 480
        private const val MEL_VENSTER = 76

        // Tot "Hey Fire" getraind is (server/wekwoord/train.sh) luistert Haard naar "Hey Jarvis".
        const val STANDAARD = "hey_jarvis_v0.1.onnx"

        /** Een zelf getraind model in de app-map (bijv. hey_fire.onnx) gaat voor het ingebouwde. */
        fun eigenModel(ctx: Context): File? =
            File(ctx.filesDir, "wekwoord.onnx").takeIf { it.exists() }
    }
}
