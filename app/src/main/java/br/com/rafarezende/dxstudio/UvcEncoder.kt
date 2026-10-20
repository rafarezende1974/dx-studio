package br.com.rafarezende.dxstudio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue

/**
 * Encoder H.264 proprio para as cameras UVC.
 *
 * A AUSBC 3.2.7 entrega os frames do preview em NV21 pelo
 * IPreviewDataCallBack; o encoder interno dela (captureVideoStart) nao pode
 * ser usado porque exige WRITE_EXTERNAL_STORAGE, permissao que nao existe
 * mais no Android 13+. Aqui os frames NV21 sao convertidos pra NV12 e
 * codificados com MediaCodec (entrada por buffer), nos mesmos parametros da
 * camera interna: 720p, 8 Mbps, keyframe a cada 1s.
 *
 * Uma unica thread alimenta o codec e drena a saida pro EncodedVideoSink.
 */
class UvcEncoder(
    private val width: Int,
    private val height: Int,
    private val sourceId: String,
    private val sink: EncodedVideoSink
) {
    private var codec: MediaCodec? = null
    @Volatile
    private var running = false
    private var worker: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(3)
    private var startNanos = 0L

    /** Eventos pro diagnostico do app (a MainActivity liga isto no diag()). */
    var onEvent: ((String) -> Unit)? = null
    private var mismatchReported = false
    private var framesSent = 0
    private var loopErrorsReported = 0

    companion object {
        private const val TAG = "UvcEncoder"
        private const val BIT_RATE = 8_000_000
    }

    fun start() {
        if (running) return
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                // SPS/PPS dentro de cada keyframe: o gravador extrai os
                // parametros dele (o avcC do MP4 depende disso).
                setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 1)
            }
            val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
            codec = c
            requestSyncFrame()
            startNanos = System.nanoTime()
            running = true
            worker = Thread({ loop() }, "dx-uvc-enc-$sourceId").apply { start() }
            Log.i(TAG, "Encoder UVC ligado ($sourceId)")
        } catch (e: Exception) {
            Log.e(TAG, "start falhou ($sourceId)", e)
            running = false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        worker?.join(800)
        worker = null
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stop falhou ($sourceId)", e)
        }
        codec = null
        queue.clear()
    }

    /** Pede um keyframe imediato ao codec (util na abertura e na troca de programa). */
    fun requestSyncFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            codec?.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "requestSyncFrame falhou ($sourceId)", e)
        }
    }

    /** Chamado na thread da camera: nao bloqueia; descarta o frame mais antigo se encher. */
    fun offerNv21(data: ByteArray) {
        if (!running) return
        if (data.size != width * height * 3 / 2) {
            if (!mismatchReported) {
                mismatchReported = true
                onEvent?.invoke(
                    "REC encoder $sourceId: quadro de ${data.size} bytes NAO bate com " +
                        "${width}x$height (${width * height * 3 / 2} bytes) — quadros descartados"
                )
            }
            return
        }
        if (!queue.offer(data)) {
            queue.poll()
            queue.offer(data)
        }
    }

    private fun loop() {
        val c = codec ?: return
        val info = MediaCodec.BufferInfo()
        val nv12 = ByteArray(width * height * 3 / 2)
        while (running) {
            try {
                val frame = queue.poll()
                if (frame != null) {
                    val inIndex = c.dequeueInputBuffer(0)
                    if (inIndex >= 0) {
                        nv21ToNv12(frame, nv12)
                        val input = c.getInputBuffer(inIndex)
                        if (input != null) {
                            input.clear()
                            input.put(nv12)
                            val pts = (System.nanoTime() - startNanos) / 1000
                            c.queueInputBuffer(inIndex, 0, nv12.size, pts, 0)
                        }
                    }
                }
                var outIndex = c.dequeueOutputBuffer(info, 5_000)
                while (outIndex >= 0) {
                    val buffer = c.getOutputBuffer(outIndex)
                    if (buffer != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        if (framesSent == 0) {
                            onEvent?.invoke("REC encoder $sourceId: primeiro quadro codificado (keyframe=$isKey)")
                        }
                        framesSent++
                        sink.onVideoFrame(sourceId, data, isKey)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    outIndex = c.dequeueOutputBuffer(info, 0)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Passa o formato do encoder como ele veio: quem usa e
                    // o gravador (dimensoes e, como reserva, o csd dele).
                    sink.onVideoConfig(sourceId, c.outputFormat)
                }
            } catch (e: Exception) {
                Log.e(TAG, "loop falhou ($sourceId)", e)
                if (loopErrorsReported < 3) {
                    loopErrorsReported++
                    onEvent?.invoke("REC encoder $sourceId: erro no loop (${e.message})")
                }
                try {
                    Thread.sleep(10)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    /** NV21 (Y + VU intercalado) -> NV12 (Y + UV intercalado), que e o que o encoder espera. */
    private fun nv21ToNv12(src: ByteArray, out: ByteArray) {
        val ySize = width * height
        System.arraycopy(src, 0, out, 0, ySize)
        var i = ySize
        while (i + 1 < src.size) {
            out[i] = src[i + 1]
            out[i + 1] = src[i]
            i += 2
        }
    }
}
