package br.com.rafarezende.dxstudio

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IEncodeDataCallBack
import com.jiangdg.ausbc.camera.CameraUVC
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.jiangdg.usb.USBMonitor
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), ICameraStateCallBack {

    // ---------- Views ----------
    private lateinit var statusText: TextView
    private lateinit var audioSpinner: Spinner
    private lateinit var vuMeter: ProgressBar
    private lateinit var recIndicator: TextView
    private lateinit var recTimer: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var programContainer: FrameLayout
    private lateinit var previewSourceName: TextView
    private lateinit var programSourceName: TextView
    private lateinit var onAirBadge: TextView
    private lateinit var transitionButton: Button
    private lateinit var recButton: Button
    private lateinit var fadeToggle: Button
    private lateinit var recInfo: TextView
    private lateinit var slotContainers: List<FrameLayout>
    private lateinit var slotLabels: List<TextView>

    // ---------- Fontes ----------
    private inner class Source(
        val id: String,
        val baseLabel: String,
        val view: View,
        val slotIndex: Int // 0=CAM1, 1=CAM2, 2=TABLET
    ) {
        var uvc: MultiCameraClient.ICamera? = null
        var deviceId: Int = -1
        var opened: Boolean = false
    }

    private val sources = mutableListOf<Source>()
    private var programId: String? = null
    private var previewId: String? = null
    private var fadeEnabled = false

    // ---------- USB / UVC ----------
    private var cameraClient: MultiCameraClient? = null
    private val pendingCameras = HashMap<Int, MultiCameraClient.ICamera>()

    // ---------- Camera interna / audio / gravacao ----------
    private var internalCamera: InternalCamera? = null
    private lateinit var audioEngine: AudioEngine
    private var audioOptions: List<AudioEngine.InputOption> = emptyList()

    @Volatile
    private var recorder: ProgramRecorder? = null
    private var recording = false
    private var currentRecordingUri: Uri? = null
    private var currentRecordingPfd: ParcelFileDescriptor? = null
    private var recStartElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private var vuPostPending = false
    @Volatile
    private var lastLevel = 0

    private var lastTapSourceId: String? = null
    private var lastTapTime = 0L

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!recording) return
            val elapsed = System.currentTimeMillis() - recStartElapsed
            recTimer.text = formatDuration(elapsed)
            recIndicator.alpha = if ((elapsed / 500) % 2L == 0L) 1f else 0.25f
            uiHandler.postDelayed(this, 250)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startEngines() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.statusText)
        audioSpinner = findViewById(R.id.audioSpinner)
        vuMeter = findViewById(R.id.vuMeter)
        recIndicator = findViewById(R.id.recIndicator)
        recTimer = findViewById(R.id.recTimer)
        previewContainer = findViewById(R.id.previewContainer)
        programContainer = findViewById(R.id.programContainer)
        previewSourceName = findViewById(R.id.previewSourceName)
        programSourceName = findViewById(R.id.programSourceName)
        onAirBadge = findViewById(R.id.onAirBadge)
        transitionButton = findViewById(R.id.transitionButton)
        recButton = findViewById(R.id.recButton)
        fadeToggle = findViewById(R.id.fadeToggle)
        recInfo = findViewById(R.id.recInfo)
        slotContainers = listOf(
            findViewById(R.id.slotContainer1),
            findViewById(R.id.slotContainer2),
            findViewById(R.id.slotContainer3)
        )
        slotLabels = listOf(
            findViewById(R.id.slotLabel1),
            findViewById(R.id.slotLabel2),
            findViewById(R.id.slotLabel3)
        )

        audioEngine = AudioEngine(this) { level ->
            lastLevel = level
            if (!vuPostPending) {
                vuPostPending = true
                uiHandler.post {
                    vuPostPending = false
                    vuMeter.progress = lastLevel
                    vuMeter.progressTintList = android.content.res.ColorStateList.valueOf(
                        when {
                            lastLevel > 85 -> Color.parseColor("#E5322D")
                            lastLevel > 65 -> Color.parseColor("#F5B301")
                            else -> Color.parseColor("#2FBF71")
                        }
                    )
                }
            }
        }
        setupAudioSpinner()

        // Fonte TABLET (camera interna) - a view vive no slot 3 ate ser promovida
        val tabletView = android.view.TextureView(this)
        val tabletSource = Source(InternalCamera.SOURCE_ID, "TABLET", tabletView, 2)
        sources.add(tabletSource)
        slotContainers[2].addView(
            tabletView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        tabletView.setOnClickListener { onSourceTapped(tabletSource.id) }
        internalCamera = InternalCamera(this, tabletView) { opened ->
            uiHandler.post {
                tabletSource.opened = opened
                if (opened) onSourceOpened(tabletSource) else onSourceClosed(tabletSource)
            }
        }

        transitionButton.setOnClickListener { doTransition() }
        recButton.setOnClickListener { if (recording) stopRecording() else startRecording() }
        fadeToggle.setOnClickListener {
            fadeEnabled = !fadeEnabled
            fadeToggle.text = if (fadeEnabled) "FADE: SIM" else "FADE: NÃO"
        }
        findViewById<Button>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (recording) {
                    toast("Pare a gravação antes de sair")
                } else {
                    finish()
                }
            }
        })

        setupUsbClient()
        ensurePermissions()
        refreshAssignments()
    }

    // ---------- Permissoes / motores ----------

    private fun ensurePermissions() {
        val needed = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isEmpty()) {
            startEngines()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private var enginesStarted = false
    private fun startEngines() {
        if (enginesStarted) return
        enginesStarted = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            audioEngine.start()
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            internalCamera?.start()
        } else {
            slotLabels[2].text = "TABLET · sem permissão de câmera"
        }
    }

    private fun setupAudioSpinner() {
        audioOptions = audioEngine.inputOptions()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            audioOptions.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSpinner.adapter = adapter
        audioSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val option = audioOptions.getOrNull(position) ?: return
                audioEngine.setInput(option.device)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    override fun onResume() {
        super.onResume()
        // Dispositivos USB de audio podem ter sido ligados depois da abertura
        val selectedBefore = audioOptions.getOrNull(audioSpinner.selectedItemPosition)?.label
        audioOptions = audioEngine.inputOptions()
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            audioOptions.map { it.label }
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        audioSpinner.adapter = adapter
        val idx = audioOptions.indexOfFirst { it.label == selectedBefore }
        if (idx >= 0) audioSpinner.setSelection(idx)
    }

    // ---------- USB UVC ----------

    private fun setupUsbClient() {
        cameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                if (pendingCameras.containsKey(device.deviceId)) return
                val camera = CameraUVC(this@MainActivity, device)
                pendingCameras[device.deviceId] = camera
                cameraClient?.requestPermission(device)
            }

            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                pendingCameras.remove(device.deviceId)
                findSourceByDevice(device.deviceId)?.let { teardownSource(it) }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                val camera = pendingCameras[device.deviceId] ?: return
                camera.setUsbControlBlock(ctrlBlock)
                attachCameraToSlot(camera, device)
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                findSourceByDevice(device.deviceId)?.let { teardownSource(it) }
            }

            override fun onCancelDev(device: UsbDevice?) {
                // Permissao negada pelo usuario: nada a fazer nesta versao
            }
        })
        cameraClient?.register()
    }

    private fun attachCameraToSlot(camera: MultiCameraClient.ICamera, device: UsbDevice) {
        if (findSourceByDevice(device.deviceId) != null) return
        val usedSlots = sources.map { it.slotIndex }.toSet()
        val slotIndex = listOf(0, 1).firstOrNull { it !in usedSlots }
        if (slotIndex == null) {
            toast("A v1 usa no máximo 2 câmeras USB — a terceira foi ignorada")
            return
        }
        val id = if (slotIndex == 0) "uvc1" else "uvc2"
        val label = if (slotIndex == 0) "CAM 1" else "CAM 2"
        val view = AspectRatioTextureView(this)
        val source = Source(id, label, view, slotIndex)
        source.uvc = camera
        source.deviceId = device.deviceId
        sources.add(source)
        slotContainers[slotIndex].addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        view.setOnClickListener { onSourceTapped(id) }
        camera.setCameraStateCallBack(this)
        camera.setEncodeDataCallBack(encodeCallbackFor(id))
        camera.openCamera(view, uvcRequest())
        refreshAssignments()
    }

    private fun uvcRequest(): CameraRequest {
        return CameraRequest.Builder()
            .setPreviewWidth(ProgramRecorder.WIDTH)
            .setPreviewHeight(ProgramRecorder.HEIGHT)
            .setRenderMode(CameraRequest.RenderMode.OPENGL)
            .setPreviewFormat(CameraRequest.PreviewFormat.FORMAT_MJPEG)
            .setAudioSource(CameraRequest.AudioSource.NONE)
            .setAspectRatioShow(true)
            .create()
    }

    private fun teardownSource(source: Source) {
        val wasProgram = source.id == programId
        if (wasProgram && recording) {
            stopRecording()
            toast("A câmera do programa foi removida — gravação encerrada")
        }
        try {
            source.uvc?.closeCamera()
        } catch (e: Exception) {
            // segue a limpeza mesmo se a biblioteca reclamar
        }
        (source.view.parent as? ViewGroup)?.removeView(source.view)
        sources.remove(source)
        if (programId == source.id) {
            programId = previewId
            previewId = null
        } else if (previewId == source.id) {
            previewId = null
        }
        refreshAssignments()
    }

    private fun findSourceByDevice(deviceId: Int): Source? {
        return sources.firstOrNull { it.deviceId == deviceId }
    }

    // Callback de estado das cameras UVC (ICameraStateCallBack)
    override fun onCameraState(
        self: MultiCameraClient.ICamera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        val source = findSourceByDevice(self.getUsbDevice().deviceId) ?: return
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                source.opened = true
                onSourceOpened(source)
            }
            ICameraStateCallBack.State.CLOSED -> {
                source.opened = false
                onSourceClosed(source)
            }
            ICameraStateCallBack.State.ERROR -> {
                source.opened = false
                toast("${source.baseLabel}: erro ao abrir (${msg ?: "desconhecido"})")
                refreshAssignments()
            }
        }
    }

    private fun onSourceOpened(source: Source) {
        if (recording && source.uvc != null) {
            // Camera plugada no meio da gravacao: liga o stream codificado dela
            try {
                source.uvc?.captureStreamStart()
            } catch (e: Exception) {
                // segue sem o stream desta fonte
            }
        }
        if (programId == null) {
            programId = source.id
            recorder?.setProgram(source.id)
        } else if (previewId == null && source.id != programId) {
            previewId = source.id
        }
        refreshAssignments()
    }

    private fun onSourceClosed(source: Source) {
        refreshAssignments()
    }

    // ---------- Mesa de corte ----------

    private fun onSourceTapped(sourceId: String) {
        val now = System.currentTimeMillis()
        if (lastTapSourceId == sourceId && now - lastTapTime < 350) {
            // Toque duplo: corte direto pro programa
            if (sourceId != programId) {
                previewId = sourceId
                doTransition()
            }
            lastTapSourceId = null
            return
        }
        lastTapSourceId = sourceId
        lastTapTime = now
        if (sourceId == programId) return
        previewId = sourceId
        refreshAssignments()
    }

    private fun doTransition() {
        val newProgram = previewId ?: return
        val oldProgram = programId
        if (newProgram == oldProgram) return
        val applySwap = {
            programId = newProgram
            previewId = oldProgram
            recorder?.setProgram(newProgram)
            refreshAssignments()
        }
        if (fadeEnabled) {
            programContainer.animate().alpha(0f).setDuration(150).withEndAction {
                applySwap()
                programContainer.animate().alpha(1f).setDuration(220).start()
                previewContainer.animate().alpha(1f).setDuration(220).start()
            }.start()
            previewContainer.animate().alpha(0f).setDuration(150).start()
        } else {
            applySwap()
        }
    }

    /** Move as views das fontes pros containers certos e atualiza rotulos/bordas. */
    private fun refreshAssignments() {
        for (source in sources) {
            val target = when (source.id) {
                programId -> programContainer
                previewId -> previewContainer
                else -> slotContainers[source.slotIndex]
            }
            if (source.view.parent != target) {
                (source.view.parent as? ViewGroup)?.removeView(source.view)
                target.addView(
                    source.view,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            val role = when (source.id) {
                programId -> " · PROGRAMA"
                previewId -> " · PRÉVIA"
                else -> ""
            }
            val state = if (source.opened) "" else " · sem sinal"
            slotLabels[source.slotIndex].text = "${source.baseLabel}$role$state"
            slotLabels[source.slotIndex].setTextColor(
                when (source.id) {
                    programId -> Color.parseColor("#E5322D")
                    previewId -> Color.parseColor("#2FBF71")
                    else -> Color.parseColor("#9AA7B8")
                }
            )
        }
        previewSourceName.text = sources.firstOrNull { it.id == previewId }?.baseLabel ?: "—"
        programSourceName.text = sources.firstOrNull { it.id == programId }?.baseLabel ?: "—"
        transitionButton.isEnabled = previewId != null && programId != null && previewId != programId
        recButton.isEnabled = recording || programId != null
        val openedCount = sources.count { it.opened }
        statusText.text = when {
            openedCount == 0 -> "Conecte as câmeras pelo hub USB (alimentado)"
            recording -> "Gravando o programa · $openedCount fonte(s) ativa(s)"
            else -> "$openedCount fonte(s) ativa(s) · toque numa fonte pra prévia"
        }
    }

    // ---------- Gravacao do programa ----------

    private fun encodeCallbackFor(sourceId: String): IEncodeDataCallBack {
        return object : IEncodeDataCallBack {
            override fun onEncodeData(
                type: IEncodeDataCallBack.DataType,
                buffer: ByteBuffer,
                offset: Int,
                size: Int,
                timestamp: Long
            ) {
                val rec = recorder ?: return
                if (size <= 0) return
                val dup = buffer.duplicate()
                if (offset + size > dup.capacity()) return
                val data = ByteArray(size)
                dup.position(offset)
                dup.limit(offset + size)
                dup.get(data)
                when (type) {
                    IEncodeDataCallBack.DataType.H264_SPS -> {
                        AnnexB.splitSpsPps(data)?.let { (sps, pps) ->
                            rec.onVideoConfig(sourceId, sps, pps)
                        }
                    }
                    IEncodeDataCallBack.DataType.H264_KEY -> rec.onVideoFrame(sourceId, data, true)
                    IEncodeDataCallBack.DataType.H264 -> rec.onVideoFrame(sourceId, data, false)
                    IEncodeDataCallBack.DataType.AAC -> Unit // audio e fixo, vem do AudioEngine
                }
            }
        }
    }

    private fun startRecording() {
        val pid = programId
        if (pid == null) {
            toast("Nenhuma fonte no programa")
            return
        }
        val name = "DXStudio_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/DXStudio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Não consegui criar o arquivo de gravação")
            return
        }
        val pfd = contentResolver.openFileDescriptor(uri, "w")
        if (pfd == null) {
            contentResolver.delete(uri, null, null)
            toast("Não consegui abrir o arquivo de gravação")
            return
        }
        currentRecordingUri = uri
        currentRecordingPfd = pfd

        val rec = ProgramRecorder(pfd.fileDescriptor)
        recorder = rec
        rec.start()
        rec.setProgram(pid)

        // Audio fixo -> encoder AAC -> gravador
        audioEngine.startEncoding(
            onFormat = { format -> recorder?.setAudioFormat(format) },
            onData = { data -> recorder?.onAudioFrame(data) }
        )
        // Fontes UVC: liga o stream codificado de todas (o gravador filtra pelo programa)
        for (source in sources) {
            if (source.opened) {
                try {
                    source.uvc?.captureStreamStart()
                } catch (e: Exception) {
                    // fonte sem encoder disponivel: segue sem ela
                }
            }
        }
        // Camera interna: liga o encoder dela
        internalCamera?.startEncoder(rec)

        recording = true
        recStartElapsed = System.currentTimeMillis()
        uiHandler.post(timerRunnable)
        recIndicator.visibility = View.VISIBLE
        onAirBadge.visibility = View.VISIBLE
        recButton.text = "■ PARAR"
        recInfo.text = "Gravando: $name"
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dxstudio:rec").apply {
            acquire(4 * 60 * 60 * 1000L)
        }
        refreshAssignments()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        uiHandler.removeCallbacks(timerRunnable)
        recIndicator.visibility = View.GONE
        onAirBadge.visibility = View.GONE
        recTimer.text = "00:00:00"

        for (source in sources) {
            try {
                source.uvc?.captureStreamStop()
            } catch (e: Exception) {
                // ignora: a fonte pode ja ter sido removida
            }
        }
        internalCamera?.stopEncoder()
        audioEngine.stopEncoding()

        recorder?.finish()
        recorder = null
        try {
            currentRecordingPfd?.close()
        } catch (e: Exception) {
            // arquivo ja fechado pelo sistema
        }
        currentRecordingPfd = null
        val uri = currentRecordingUri
        if (uri != null) {
            val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            contentResolver.update(uri, values, null, null)
        }
        currentRecordingUri = null
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        recButton.text = "● REC"
        recInfo.text = "Gravação salva em Filmes/DXStudio"
        toast("Gravação salva em Filmes/DXStudio")
        refreshAssignments()
    }

    // ---------- Ciclo de vida ----------

    override fun onDestroy() {
        if (recording) stopRecording()
        for (source in sources) {
            try {
                source.uvc?.closeCamera()
            } catch (e: Exception) {
                // limpeza final
            }
        }
        cameraClient?.unRegister()
        cameraClient?.destroy()
        cameraClient = null
        internalCamera?.stop()
        audioEngine.stop()
        super.onDestroy()
    }

    private fun toast(message: String) {
        uiHandler.post { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
