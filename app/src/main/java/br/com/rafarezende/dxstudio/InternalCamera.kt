package br.com.rafarezende.dxstudio

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView

/**
 * Camera interna do tablet como terceira fonte (CAM TABLET).
 *
 * Preview via Camera2 no TextureView da fonte. Quando a gravacao comeca, um
 * encoder H.264 por superficie e adicionado como segundo destino da sessao e
 * os pacotes vao pro mesmo EncodedVideoSink das cameras UVC.
 */
class InternalCamera(
    private val context: Context,
    private val textureView: TextureView,
    private val onOpenedChanged: (Boolean) -> Unit
) {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 0

    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var encoderThread: Thread? = null
    @Volatile
    private var encoderRunning = false
    private var sink: EncodedVideoSink? = null

    companion object {
        private const val TAG = "InternalCamera"
        const val SOURCE_ID = "tablet"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val BIT_RATE = 8_000_000
    }

    fun start() {
        thread = HandlerThread("dx-internal-cam").apply { start() }
        handler = Handler(thread!!.looper)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                st.setDefaultBufferSize(WIDTH, HEIGHT)
                previewSurface = Surface(st)
                applyPreviewTransform(width, height)
                openCamera()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyPreviewTransform(width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                closeSession()
                previewSurface?.release()
                previewSurface = null
                // Nao destroi a SurfaceTexture: a mesma view muda de container
                // (slot <-> previa <-> programa) e volta a ser usada.
                return false
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (textureView.isAvailable) {
            val st = textureView.surfaceTexture
            if (st != null) {
                st.setDefaultBufferSize(WIDTH, HEIGHT)
                previewSurface = Surface(st)
                applyPreviewTransform(textureView.width, textureView.height)
                openCamera()
            }
        }
    }

    fun stop() {
        stopEncoder()
        closeSession()
        try {
            cameraDevice?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close camera falhou", e)
        }
        cameraDevice = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    /** Ajusta rotacao do preview quando o sensor e montado "em pe" (caso comum). */
    private fun applyPreviewTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        if (sensorOrientation != 90 && sensorOrientation != 270) return
        val matrix = Matrix()
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        matrix.postRotate(if (sensorOrientation == 90) -90f else 90f, cx, cy)
        textureView.setTransform(matrix)
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            var chosenId: String? = null
            for (id in manager.cameraIdList) {
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosenId = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    break
                }
                if (chosenId == null) {
                    chosenId = id
                    sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                }
            }
            val cameraId = chosenId ?: return
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    onOpenedChanged(true)
                    createSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                    onOpenedChanged(false)
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    onOpenedChanged(false)
                    Log.e(TAG, "Camera2 erro $error")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "openCamera falhou", e)
            onOpenedChanged(false)
        }
    }

    private fun createSession() {
        val device = cameraDevice ?: return
        val preview = previewSurface ?: return
        val targets = mutableListOf(preview)
        encoderSurface?.let { targets.add(it) }
        try {
            device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(newSession: CameraCaptureSession) {
                    session = newSession
                    try {
                        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        builder.addTarget(preview)
                        encoderSurface?.let { builder.addTarget(it) }
                        builder.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                        newSession.setRepeatingRequest(builder.build(), null, handler)
                    } catch (e: Exception) {
                        Log.e(TAG, "setRepeatingRequest falhou", e)
                    }
                }

                override fun onConfigureFailed(newSession: CameraCaptureSession) {
                    Log.e(TAG, "createCaptureSession falhou")
                }
            }, handler)
        } catch (e: Exception) {
            Log.e(TAG, "createSession falhou", e)
        }
    }

    private fun closeSession() {
        try {
            session?.close()
        } catch (e: Exception) {
            Log.e(TAG, "close session falhou", e)
        }
        session = null
    }

    // ---------- Encoder H.264 (so durante a gravacao) ----------

    fun startEncoder(videoSink: EncodedVideoSink) {
        if (encoderRunning) return
        sink = videoSink
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoderSurface = codec.createInputSurface()
            codec.start()
            encoder = codec
            encoderRunning = true
            encoderThread = Thread({ drainLoop() }, "dx-internal-enc").apply { start() }
            // Recria a sessao incluindo a superficie do encoder
            closeSession()
            createSession()
            Log.i(TAG, "Encoder da camera interna ligado")
        } catch (e: Exception) {
            Log.e(TAG, "startEncoder falhou", e)
            encoderRunning = false
        }
    }

    /** Pede um keyframe imediato ao encoder (util na troca de programa). */
    fun requestSyncFrame() {
        try {
            val params = android.os.Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            encoder?.setParameters(params)
        } catch (e: Exception) {
            Log.e(TAG, "requestSyncFrame falhou", e)
        }
    }

    fun stopEncoder() {
        if (!encoderRunning) return
        encoderRunning = false
        encoderThread?.join(800)
        encoderThread = null
        try {
            encoder?.stop()
            encoder?.release()
        } catch (e: Exception) {
            Log.e(TAG, "stop encoder falhou", e)
        }
        encoder = null
        encoderSurface?.release()
        encoderSurface = null
        sink = null
        // Volta a sessao so com preview
        closeSession()
        createSession()
    }

    private fun drainLoop() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (encoderRunning) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                Log.e(TAG, "dequeue falhou", e)
                break
            }
            when {
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null && info.size > 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(data)
                        val isKey = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                        sink?.onVideoFrame(SOURCE_ID, data, isKey)
                    }
                    try {
                        codec.releaseOutputBuffer(index, false)
                    } catch (e: Exception) {
                        Log.e(TAG, "releaseOutputBuffer falhou", e)
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    val csd0 = format.getByteBuffer("csd-0")
                    val csd1 = format.getByteBuffer("csd-1")
                    if (csd0 != null && csd1 != null) {
                        val sps = ByteArray(csd0.remaining()).apply { csd0.get(this) }
                        val pps = ByteArray(csd1.remaining()).apply { csd1.get(this) }
                        sink?.onVideoConfig(
                            SOURCE_ID,
                            AnnexB.stripStartCode(sps),
                            AnnexB.stripStartCode(pps),
                            WIDTH,
                            HEIGHT
                        )
                    }
                }
            }
        }
    }

    fun previewSize(): Size = Size(WIDTH, HEIGHT)
}
