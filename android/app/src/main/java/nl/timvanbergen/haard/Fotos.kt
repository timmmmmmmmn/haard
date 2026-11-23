package nl.timvanbergen.haard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import java.io.ByteArrayOutputStream
import java.io.File

/** Fotolijst: alle foto's uit Pictures/Haard (ook submappen), in willekeurige volgorde. */
object Fotos {

    val map: File get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Haard")

    @Volatile private var lijst: List<File> = emptyList()

    /** Opnieuw kijken welke foto's er zijn; geeft het aantal terug. */
    fun ververs(): Int {
        val ext = setOf("jpg", "jpeg", "png", "webp", "heic")
        lijst = map.walkTopDown().filter { it.isFile && it.extension.lowercase() in ext }.toList().shuffled()
        return lijst.size
    }

    val aantal: Int get() = lijst.size

    /** Foto nummer n, verkleind tot ongeveer de schermgrootte (de originelen zijn vaak 12 MP). */
    fun jpeg(n: Int, breed: Int): ByteArray? {
        val f = lijst.getOrNull(n) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var schaal = 1
        while (maxOf(o.outWidth, o.outHeight) / (schaal * 2) >= breed) schaal *= 2
        val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = schaal }) ?: return null
        val draai = draaiing(f)
        val goed = if (draai == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height,
            android.graphics.Matrix().apply { postRotate(draai.toFloat()) }, true)
        return ByteArrayOutputStream().also { goed.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    private fun draaiing(f: File): Int = runCatching {
        when (android.media.ExifInterface(f.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            6 -> 90
            3 -> 180
            8 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
