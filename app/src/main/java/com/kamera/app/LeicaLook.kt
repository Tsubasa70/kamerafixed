package com.kamera.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.sqrt

enum class LookType { STANDARD, AUTHENTIC, VIBRANT }

/**
 * Renk profili (LUT benzeri simulasyon).
 *
 * Ayni parametreler hem canli onizleme/video (GlPipeline fragment shader) hem de
 * fotograf (asagidaki applyInPlace) tarafinda kullanilir; boylece uc cikti da ayni gorunur.
 *
 * Islem sirasi:  renk matrisi (kazanc, doygunluk, kontrast, parlaklik)  ->  orta ton egrisi  ->  vinyet
 */
class LeicaLook private constructor(
    val type: LookType,
    val title: String,
    saturation: Float,
    contrast: Float,
    brightness: Float,
    gain: FloatArray,
    /** Orta ton kaldirma (HDR benzeri parlak/acik ton etkisi). 0 = kapali */
    val mid: Float,
    /** Koselerde karartma siddeti. 0 = kapali */
    val vignette: Float
) {
    /** 3x3 renk matrisi, satir-major. Girdi/cikti 0..1 araliginda. */
    val matrix = FloatArray(9)
    val offset = FloatArray(3)
    val isIdentity: Boolean get() = type == LookType.STANDARD

    init {
        // M = kontrast * (doygunluk*I + (1-doygunluk)*W) * diag(kazanc)   (W: luma satirlari)
        val lw = floatArrayOf(0.299f, 0.587f, 0.114f)
        for (row in 0..2) {
            for (col in 0..2) {
                val s = (if (row == col) saturation else 0f) + (1f - saturation) * lw[col]
                matrix[row * 3 + col] = contrast * s * gain[col]
            }
        }
        val o = 0.5f - 0.5f * contrast + brightness
        offset[0] = o
        offset[1] = o
        offset[2] = o
    }

    /** OpenGL mat3 icin sutun-major dizi */
    fun glMatrix(): FloatArray = floatArrayOf(
        matrix[0], matrix[3], matrix[6],
        matrix[1], matrix[4], matrix[7],
        matrix[2], matrix[5], matrix[8]
    )

    private fun vignetteGain(d: Float): Float {
        val t = ((d - 0.55f) / 0.8f).coerceIn(0f, 1f)
        val s = t * t * (3f - 2f * t)          // smoothstep(0.55, 1.35, d)
        return 1f - vignette * s
    }

    /** Bitmap uzerinde (yerinde) filtreyi uygular. 50 MP icin seritler halinde calisir. */
    fun applyInPlace(bmp: Bitmap) {
        if (isIdentity) return
        val w = bmp.width
        val h = bmp.height
        val rowsPerStrip = min(256, h)
        val px = IntArray(w * rowsPerStrip)

        val lutSize = 2048
        val lut = FloatArray(lutSize) { i ->
            val d2 = i * 2f / (lutSize - 1)       // d^2, 0..2
            vignetteGain(sqrt(d2))
        }
        val lutScale = (lutSize - 1) / 2f

        val nx2 = FloatArray(w) {
            val nx = (it + 0.5f - w / 2f) / (w / 2f)
            nx * nx
        }
        val k = 1f / 255f
        val m0 = matrix[0] * k; val m1 = matrix[1] * k; val m2 = matrix[2] * k
        val m3 = matrix[3] * k; val m4 = matrix[4] * k; val m5 = matrix[5] * k
        val m6 = matrix[6] * k; val m7 = matrix[7] * k; val m8 = matrix[8] * k
        val o0 = offset[0]; val o1 = offset[1]; val o2 = offset[2]
        val md = mid

        var y = 0
        while (y < h) {
            val n = min(rowsPerStrip, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, n)
            for (r in 0 until n) {
                val ny = (y + r + 0.5f - h / 2f) / (h / 2f)
                val ny2 = ny * ny
                val base = r * w
                for (x in 0 until w) {
                    val p = px[base + x]
                    val ri = (p shr 16) and 0xFF
                    val gi = (p shr 8) and 0xFF
                    val bi = p and 0xFF

                    var rr = m0 * ri + m1 * gi + m2 * bi + o0
                    var gg = m3 * ri + m4 * gi + m5 * bi + o1
                    var bb = m6 * ri + m7 * gi + m8 * bi + o2

                    rr = if (rr < 0f) 0f else if (rr > 1f) 1f else rr
                    gg = if (gg < 0f) 0f else if (gg > 1f) 1f else gg
                    bb = if (bb < 0f) 0f else if (bb > 1f) 1f else bb

                    if (md != 0f) {
                        rr += md * rr * (1f - rr)
                        gg += md * gg * (1f - gg)
                        bb += md * bb * (1f - bb)
                    }

                    var idx = ((nx2[x] + ny2) * lutScale).toInt()
                    if (idx >= lutSize) idx = lutSize - 1
                    val vg = lut[idx] * 255f

                    var ro = (rr * vg + 0.5f).toInt()
                    var go = (gg * vg + 0.5f).toInt()
                    var bo = (bb * vg + 0.5f).toInt()
                    if (ro > 255) ro = 255
                    if (go > 255) go = 255
                    if (bo > 255) bo = 255
                    px[base + x] = (0xFF shl 24) or (ro shl 16) or (go shl 8) or bo
                }
            }
            bmp.setPixels(px, 0, w, 0, y, w, n)
            y += n
        }
    }

    /**
     * Kameradan gelen JPEG'i cozer, filtreler, tekrar JPEG'e cevirir ve EXIF yonunu ekler.
     * Bellek yetmezse cagiran taraf yakalayip orijinal JPEG'i kaydeder.
     */
    fun processJpeg(jpeg: ByteArray, orientationDeg: Int): ByteArray {
        if (isIdentity) return jpeg
        val opts = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts) ?: return jpeg
        applyInPlace(bmp)
        val bos = ByteArrayOutputStream(jpeg.size + 65536)
        bmp.compress(Bitmap.CompressFormat.JPEG, 96, bos)
        bmp.recycle()
        return injectExifOrientation(bos.toByteArray(), orientationDeg)
    }

    companion object {
        val STANDARD = LeicaLook(
            LookType.STANDARD, "Standart",
            saturation = 1f, contrast = 1f, brightness = 0f,
            gain = floatArrayOf(1f, 1f, 1f), mid = 0f, vignette = 0f
        )

        /** Dogal tonlar, yuksek kontrast, hafif vinyet, klasik (hafif sicak) palet */
        val AUTHENTIC = LeicaLook(
            LookType.AUTHENTIC, "Leica Authentic",
            saturation = 0.90f, contrast = 1.25f, brightness = -0.03f,
            gain = floatArrayOf(1.04f, 1.00f, 0.95f), mid = 0f, vignette = 0.38f
        )

        /** Canli, doygun renkler, HDR benzeri acik/parlak tonlar */
        val VIBRANT = LeicaLook(
            LookType.VIBRANT, "Leica Vibrant",
            saturation = 1.40f, contrast = 1.10f, brightness = 0.03f,
            gain = floatArrayOf(1.02f, 1.01f, 0.98f), mid = 0.28f, vignette = 0.08f
        )

        private fun exifCode(deg: Int): Int = when (((deg % 360) + 360) % 360) {
            90 -> 6
            180 -> 3
            270 -> 8
            else -> 1
        }

        /** JPEG'in basina (SOI'den hemen sonra) sadece Orientation etiketi olan minimal bir EXIF APP1 ekler. */
        fun injectExifOrientation(jpeg: ByteArray, deg: Int): ByteArray {
            if (jpeg.size < 2 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg
            val code = exifCode(deg)
            val app1 = byteArrayOf(
                0xFF.toByte(), 0xE1.toByte(), 0x00, 0x22,             // APP1, uzunluk = 34
                0x45, 0x78, 0x69, 0x66, 0x00, 0x00,                   // "Exif\0\0"
                0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,       // TIFF baslik (big endian), IFD0 ofseti = 8
                0x00, 0x01,                                           // 1 giris
                0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,       // Orientation, SHORT, adet 1
                0x00, code.toByte(), 0x00, 0x00,                      // deger
                0x00, 0x00, 0x00, 0x00                                // sonraki IFD yok
            )
            val out = ByteArray(jpeg.size + app1.size)
            out[0] = jpeg[0]
            out[1] = jpeg[1]
            System.arraycopy(app1, 0, out, 2, app1.size)
            System.arraycopy(jpeg, 2, out, 2 + app1.size, jpeg.size - 2)
            return out
        }
    }
}
