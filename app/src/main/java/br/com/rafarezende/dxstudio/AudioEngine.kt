package br.com.rafarezende.dxstudio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Audio FIXO do programa: uma unica entrada (microfone do tablet, interface
 * USB, audio da plaquinha quando o Android expoe como dispositivo de audio...)
 * escolhida pelo usuario. Nao muda quando troca a camera.
 *
 * A captura roda o tempo todo (pra alimentar o medidor de nivel). Quando a
 * gravacao comeca, um encoder AAC e ligado e os pacotes vao pro ProgramRecorder.
 */
class AudioEngine(
    private val context: Context,
    private val onLevel: (Int) -> Unit
) {
    data class InputOption(val label: String, val device: AudioDeviceInfo?)

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @Volatile
    var selectedDevice: AudioDeviceInfo? = null
        private set

    @Volatile
    private var encoding = false
    private var encoder: MediaCodec? = null
    private var formatSink: ((MediaFormat) -> Unit)? = null
    private var dataSink: ((ByteArray) -> Unit)? = null

    companion object {
        private const val TAG = "AudioEngine"
        const val SAMPLE_RATE = 44100
        private const val CHANNELS = 2
        private const val BIT_RATE = 128_000
    }

    fun inputOptions(): List<InputOption> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = mutableListOf(InputOption("Microfone do tablet", null))
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (d in devices) {
            when (d.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    val name = d.productName?.toString()?.trim().orEmpty()
                    val label = if (name.isEmpty()) "Dispositivo USB" else "USB · $name"
                    result.add(InputOption(label, d))
                }
            }
        }
        return result
    }

    fun setInput(device: AudioDeviceInfo?) {
        selectedDevice = device
        val rec = record
        if (running && rec != null) {
            // Recria a captura com o novo dispositivo preferido.
            try {
                rec.preferredDevice = device
            } catch (e: Exception) {
                Log.e(TAG, "preferredDevice falhou", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "dx-audio").apply { start() }
    }

    fun stop() {
        running = false
        encoding = false
        thread?.join(800)
        thread = null
    }

    fun startEncoding(onFormat: (MediaFormat) -> Unit, onData: (ByteArray) -> Unit) {
        formatSink = onFormat
        dataSink = onData
        encoding = true // o encoder e criado dentro da thread de audio
    }

    fun stopEncoding() {
        encoding = false
        formatSink = null
        dataSink = null
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return null
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        selectedDevice?.let {
            try {
                rec.preferredDevice = it
            } catch (e: Exception) {
                Log.e(TAG, "preferredDevice na criacao falhou", e)
            }
        }
        return rec
    }

    private fun createEncoder(): MediaCodec? {
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec
        } catch (e: Exception) {
            Log.e(TAG, "createEncoder falhou", e)
            null
        }
    }

    private fun loop() {
        val rec = createRecord()
        if (rec == null) {
            Log.e(TAG, "AudioRecord nao inicializou")
            running = false
            return
        }
        record = rec
        rec.startRecording()
        val framesPerChunk = 1024
        val shorts = ShortArray(framesPerChunk * CHANNELS)
        val bytes = ByteArray(shorts.size * 2)
        val bufferInfo = MediaCodec.BufferInfo()
        var encoderWanted = false

        while (running) {
            val read = rec.read(shorts, 0, shorts.size)
            if (read <= 0) continue

            // Medidor de nivel (pico do chunk)
            var peak = 0
            for (i in 0 until read) {
                val v = abs(shorts[i].toInt())
                if (v > peak) peak = v
            }
            onLevel((peak * 100) / 32767)

            // Liga/desliga o encoder AAC conforme o estado de gravacao
            if (encoding && !encoderWanted) {
                encoder = createEncoder()
                encoderWanted = encoder != null
            } else if (!encoding && encoderWanted) {
                try {
                    encoder?.stop()
                    encoder?.release()
                } catch (e: Exception) {
                    Log.e(TAG, "stop encoder falhou", e)
                }
                encoder = null
                encoderWanted = false
            }

            val codec = encoder
            if (encoding && codec != null) {
                // Shorts -> bytes little-endian
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(shorts, 0, read)
                val byteCount = read * 2
                val inIndex = codec.dequeueInputBuffer(0)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(bytes, 0, byteCount)
                        codec.queueInputBuffer(inIndex, 0, byteCount, System.nanoTime() / 1000, 0)
                    }
                }
                // Drena a saida
                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                while (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.get(data)
                        dataSink?.invoke(data)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    formatSink?.invoke(codec.outputFormat)
                }
            }
        }

        try {
            rec.stop()
        } catch (e: Exception) {
            Log.e(TAG, "rec stop falhou", e)
        }
        rec.release()
        record = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "encoder release final falhou", e)
        }
        encoder = null
    }
}
