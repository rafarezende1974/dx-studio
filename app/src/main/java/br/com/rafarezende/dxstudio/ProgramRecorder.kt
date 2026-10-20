package br.com.rafarezende.dxstudio

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.FileDescriptor
import java.nio.ByteBuffer

/** Sink comum de video codificado (H.264) usado pelas fontes UVC e pela camera interna. */
interface EncodedVideoSink {
    /** Formato de saida do encoder da fonte (traz dimensoes e csd-0/csd-1). */
    fun onVideoConfig(sourceId: String, format: MediaFormat)
    fun onVideoFrame(sourceId: String, data: ByteArray, isKeyFrame: Boolean)
}

/**
 * Gravador do barramento PROGRAMA.
 *
 * Recebe o H.264 ja codificado de todas as fontes, mas so escreve no MP4 os
 * quadros da fonte que esta no programa. Na troca de fonte, espera o proximo
 * keyframe da nova fonte (o encoder usa intervalo de keyframe de 1s).
 * O audio vem de um encoder AAC separado (fonte fixa) e e escrito na mesma
 * trilha de tempo, com timestamps gerados pela chegada dos pacotes.
 */
class ProgramRecorder(fd: FileDescriptor) : EncodedVideoSink {

    private var muxer: MediaMuxer? = MediaMuxer(fd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var started = false
    private var finished = false

    private var videoTrack = -1
    private var audioTrack = -1

    private val videoFormats = HashMap<String, MediaFormat>()

    // Primeiro keyframe de cada fonte: os encoders sao configurados pra
    // embutir SPS/PPS em todo keyframe (KEY_PREPEND_HEADER_TO_SYNC_FRAMES),
    // e o gravador extrai os parametros DELE. Motivo: montar o formato da
    // trilha na mao a partir do csd do encoder gerou um MP4 com o avcC
    // VAZIO (0 SPS, 0 PPS) — arquivo com todos os quadros mas intocavel
    // ("non-existing PPS" em qualquer player), provado por ffprobe no
    // arquivo de teste de 2026-10-20.
    private val keyFrameStash = HashMap<String, ByteArray>()
    private var audioFormat: MediaFormat? = null

    /** Eventos pro diagnostico do app (a MainActivity liga isto no diag()). */
    var onEvent: ((String) -> Unit)? = null

    // Contadores pro diagnostico: onde a cadeia da gravacao quebrou aparece
    // comparando recebidos x escritos de cada trilha.
    @Volatile var videoReceived = 0; private set
    @Volatile var videoWritten = 0; private set
    @Volatile var audioWritten = 0; private set
    private var firstVideoLogged = false

    @Volatile
    private var programId: String? = null
    private var needKeyFrame = true

    private var startNanos = 0L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L

    companion object {
        private const val TAG = "ProgramRecorder"
        const val WIDTH = 1280
        const val HEIGHT = 720
    }

    @Synchronized
    fun start() {
        startNanos = System.nanoTime()
    }

    @Synchronized
    fun setProgram(sourceId: String) {
        if (programId == sourceId) return
        programId = sourceId
        needKeyFrame = true
        Log.i(TAG, "Programa agora e $sourceId (aguardando keyframe)")
    }

    @Synchronized
    override fun onVideoConfig(sourceId: String, format: MediaFormat) {
        videoFormats[sourceId] = format
        val w = try { format.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { 0 }
        val h = try { format.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { 0 }
        val csd0 = csdSize(format, "csd-0")
        val csd1 = csdSize(format, "csd-1")
        onEvent?.invoke("REC: config de video recebida de $sourceId (${w}x$h, csd0=${csd0}B csd1=${csd1}B)")
        tryStartMuxerLocked()
    }

    private fun csdSize(format: MediaFormat, key: String): Int {
        return try {
            format.getByteBuffer(key)?.remaining() ?: 0
        } catch (e: Exception) {
            0
        }
    }

    @Synchronized
    fun setAudioFormat(format: MediaFormat) {
        audioFormat = format
        onEvent?.invoke("REC: formato de audio recebido")
        tryStartMuxerLocked()
    }

    @Synchronized
    override fun onVideoFrame(sourceId: String, data: ByteArray, isKeyFrame: Boolean) {
        if (finished) return
        if (isKeyFrame && !keyFrameStash.containsKey(sourceId)) {
            keyFrameStash[sourceId] = data
            AnnexB.splitSpsPps(data)?.let { (s, p) ->
                onEvent?.invoke("REC: keyframe de $sourceId traz SPS/PPS (${s.size}B/${p.size}B)")
            }
        }
        if (sourceId != programId) return
        videoReceived++
        if (!started) {
            tryStartMuxerLocked()
            if (!started) return
        }
        if (needKeyFrame) {
            if (!isKeyFrame) return
            needKeyFrame = false
        }
        val pts = nextVideoPts()
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = pts
            flags = if (isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        }
        try {
            muxer?.writeSampleData(videoTrack, ByteBuffer.wrap(data), info)
            videoWritten++
            if (!firstVideoLogged) {
                firstVideoLogged = true
                onEvent?.invoke("REC: primeiro quadro de video gravado")
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData video falhou", e)
            onEvent?.invoke("REC: escrever quadro de video falhou (${e.message})")
        }
    }

    @Synchronized
    fun onAudioFrame(data: ByteArray) {
        if (finished || !started || audioTrack < 0) return
        val pts = nextAudioPts()
        val info = MediaCodec.BufferInfo().apply {
            offset = 0
            size = data.size
            presentationTimeUs = pts
            flags = 0
        }
        try {
            muxer?.writeSampleData(audioTrack, ByteBuffer.wrap(data), info)
            audioWritten++
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData audio falhou", e)
        }
    }

    private fun tryStartMuxerLocked() {
        if (started || finished) return
        val m = muxer ?: return
        val pid = programId ?: return
        val fmt = videoFormats[pid] ?: return
        // Espera o audio por ate 1,5s; depois disso grava so video.
        val waitedMs = (System.nanoTime() - startNanos) / 1_000_000
        if (audioFormat == null && waitedMs < 1500) return

        // Fonte dos SPS/PPS pro avcC do MP4, em ordem de confianca:
        // 1) extraidos do primeiro keyframe da fonte (os encoders embutem
        //    SPS/PPS em todo keyframe);
        // 2) o formato original do encoder, sem remontar nada na mao.
        // Sem nenhuma das duas, espera (o keyframe chega em <=1s); so
        // depois de 3s aceita o formato mesmo vazio, pra nao travar o REC.
        val keyCsd = keyFrameStash[pid]?.let { AnnexB.splitSpsPps(it) }
        val fmtCsd0 = csdSize(fmt, "csd-0")
        if (keyCsd == null && fmtCsd0 == 0 && waitedMs < 3000) return

        val trackFormat: MediaFormat
        val csdSource: String
        if (keyCsd != null) {
            val w = try { fmt.getInteger(MediaFormat.KEY_WIDTH) } catch (e: Exception) { WIDTH }
            val h = try { fmt.getInteger(MediaFormat.KEY_HEIGHT) } catch (e: Exception) { HEIGHT }
            trackFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(keyCsd.first))
                setByteBuffer("csd-1", ByteBuffer.wrap(keyCsd.second))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            }
            csdSource = "keyframe (${keyCsd.first.size}B/${keyCsd.second.size}B)"
        } else {
            trackFormat = fmt
            csdSource = "formato do encoder (csd0=${fmtCsd0}B)"
        }

        try {
            videoTrack = m.addTrack(trackFormat)
            audioFormat?.let { audioTrack = m.addTrack(it) }
            m.start()
        } catch (e: Exception) {
            Log.e(TAG, "iniciar o muxer falhou", e)
            onEvent?.invoke("REC: FALHA ao iniciar o muxer (${e.message})")
            return
        }
        started = true
        // Recomeca a contagem de tempo na abertura real do arquivo.
        startNanos = System.nanoTime()
        lastVideoPtsUs = -1
        lastAudioPtsUs = -1
        Log.i(TAG, "Muxer iniciado (videoTrack=$videoTrack audioTrack=$audioTrack)")
        onEvent?.invoke("REC: muxer iniciado (trilha de video=$videoTrack, audio=$audioTrack, SPS/PPS via $csdSource)")
    }

    private fun nextVideoPts(): Long {
        var pts = (System.nanoTime() - startNanos) / 1000
        if (pts <= lastVideoPtsUs) pts = lastVideoPtsUs + 1000
        lastVideoPtsUs = pts
        return pts
    }

    private fun nextAudioPts(): Long {
        var pts = (System.nanoTime() - startNanos) / 1000
        if (pts <= lastAudioPtsUs) pts = lastAudioPtsUs + 1000
        lastAudioPtsUs = pts
        return pts
    }

    fun elapsedMs(): Long {
        if (startNanos == 0L) return 0
        return (System.nanoTime() - startNanos) / 1_000_000
    }

    fun isStarted(): Boolean = started

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        try {
            if (started) muxer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "muxer stop falhou", e)
        }
        try {
            muxer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "muxer release falhou", e)
        }
        muxer = null
        Log.i(TAG, "Gravacao finalizada (iniciou=$started)")
        onEvent?.invoke(
            "REC: fim — muxer iniciou=$started, quadros de video recebidos=$videoReceived " +
                "escritos=$videoWritten, pacotes de audio escritos=$audioWritten"
        )
    }
}

/** Utilidades Annex B (H.264 com start codes). */
object AnnexB {
    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                if (data[i + 2] == 1.toByte()) return i
                if (i + 4 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLen(data: ByteArray, at: Int): Int {
        return if (at + 3 < data.size && data[at + 2] == 0.toByte() && data[at + 3] == 1.toByte()) 4 else 3
    }

    /** Extrai (SPS, PPS) de um pacote que contem SPS+PPS em Annex B. Retorna null se nao achar. */
    fun splitSpsPps(data: ByteArray): Pair<ByteArray, ByteArray>? {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        var pos = findStartCode(data, 0)
        while (pos >= 0) {
            val headerAt = pos + startCodeLen(data, pos)
            if (headerAt >= data.size) break
            val nalType = data[headerAt].toInt() and 0x1F
            val next = findStartCode(data, headerAt)
            val end = if (next < 0) data.size else next
            val nal = data.copyOfRange(headerAt, end)
            if (nalType == 7) sps = nal
            if (nalType == 8) pps = nal
            pos = next
        }
        val s = sps ?: return null
        val p = pps ?: return null
        return Pair(s, p)
    }

    /** Remove um eventual start code do inicio do buffer. */
    fun stripStartCode(data: ByteArray): ByteArray {
        if (data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte() && data[3] == 1.toByte()) {
            return data.copyOfRange(4, data.size)
        }
        if (data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 1.toByte()) {
            return data.copyOfRange(3, data.size)
        }
        return data
    }
}
