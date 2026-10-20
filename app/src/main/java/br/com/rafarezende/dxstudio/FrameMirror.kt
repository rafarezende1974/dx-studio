package br.com.rafarezende.dxstudio

import android.graphics.Bitmap

/**
 * Converte os frames NV21 do preview UVC em Bitmap pra exibicao nos
 * monitores (Previa/Programa).
 *
 * Por que existe: a superficie nativa da AUSBC morre quando a TextureView da
 * camera troca de container (slot -> monitor), embora os frames continuem
 * chegando pelo callback. Entao a view nativa da camera USB fica parada no
 * slot (sempre viva) e os monitores mostram este espelho, alimentado pelos
 * mesmos frames do callback. Trocar de monitor vira so trocar o espelho:
 * instantaneo, sem reabrir a camera.
 *
 * A conversao e Kotlin puro, em meia resolucao (o monitor nao precisa de
 * 720p): uma primeira versao usava RenderScript, que derrubava o app neste
 * aparelho (tecnologia depreciada, instavel em Android novos). Sem codigo
 * nativo, sem dependencias.
 */
class FrameMirror(private val width: Int, private val height: Int) {

    val outWidth: Int = width / 2
    val outHeight: Int = height / 2

    private val bitmaps: Array<Bitmap> =
        Array(2) { Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888) }
    private val pixels = IntArray(outWidth * outHeight)
    private var flip = 0

    @Volatile
    var latest: Bitmap? = null
        private set

    /** Chamado numa thread de fundo; devolve o bitmap convertido (ping-pong). */
    fun convert(nv21: ByteArray): Bitmap {
        val ySize = width * height
        var outIdx = 0
        for (oy in 0 until outHeight) {
            val sy = oy shl 1
            val yOff = sy * width
            val cOff = ySize + (sy shr 1) * width
            for (ox in 0 until outWidth) {
                val sx = ox shl 1
                val yRaw = nv21[yOff + sx].toInt() and 0xFF
                // BT.601 faixa limitada: Y 16-235 -> 0-255
                val yy = if (yRaw <= 16) 0 else ((yRaw - 16) * 1192) shr 10
                val ci = cOff + (sx and -2)
                val v = (nv21[ci].toInt() and 0xFF) - 128
                val u = (nv21[ci + 1].toInt() and 0xFF) - 128
                val r = yy + ((v * 409) shr 8)
                val g = yy - ((u * 100) shr 8) - ((v * 208) shr 8)
                val b = yy + ((u * 516) shr 8)
                pixels[outIdx++] = (0xFF shl 24) or
                    (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        }
        val idx = flip
        flip = 1 - flip
        bitmaps[idx].setPixels(pixels, 0, outWidth, 0, 0, outWidth, outHeight)
        latest = bitmaps[idx]
        return bitmaps[idx]
    }

    fun destroy() {
        latest = null
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun clamp(x: Int): Int = if (x < 0) 0 else if (x > 255) 255 else x
}
