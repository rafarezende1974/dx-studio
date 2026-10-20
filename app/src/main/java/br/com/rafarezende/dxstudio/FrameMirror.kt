package br.com.rafarezende.dxstudio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicYuvToRGB
import android.renderscript.Type

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
 */
class FrameMirror(context: Context, private val width: Int, private val height: Int) {

    private val rs: RenderScript = RenderScript.create(context)
    private val yuvScript: ScriptIntrinsicYuvToRGB =
        ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
    private val inAlloc: Allocation
    private val bitmaps: Array<Bitmap>
    private val outAllocs: Array<Allocation>
    private var flip = 0

    @Volatile
    var latest: Bitmap? = null
        private set

    init {
        val inType = Type.Builder(rs, Element.U8(rs))
            .setX(width * height * 3 / 2)
            .setYuvFormat(ImageFormat.NV21)
            .create()
        inAlloc = Allocation.createTyped(rs, inType, Allocation.USAGE_SCRIPT)
        bitmaps = Array(2) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
        outAllocs = Array(2) { i ->
            Allocation.createFromBitmap(
                rs,
                bitmaps[i],
                Allocation.MipmapControl.MIPMAP_NONE,
                Allocation.USAGE_SCRIPT
            )
        }
    }

    /** Chamado numa thread de fundo; devolve o bitmap convertido (ping-pong). */
    fun convert(nv21: ByteArray): Bitmap {
        inAlloc.copyFrom(nv21)
        yuvScript.setInput(inAlloc)
        val idx = flip
        flip = 1 - flip
        yuvScript.forEach(outAllocs[idx])
        outAllocs[idx].copyTo(bitmaps[idx])
        latest = bitmaps[idx]
        return bitmaps[idx]
    }

    fun destroy() {
        try {
            inAlloc.destroy()
            outAllocs.forEach { it.destroy() }
            yuvScript.destroy()
            rs.destroy()
        } catch (e: Exception) {
            // limpeza final: segue mesmo se o RenderScript ja tiver ido
        }
    }
}
