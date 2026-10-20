package br.com.rafarezende.dxstudio

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.MediaStore
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.USBMonitor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity(), ICameraStateCallBack {

    // ---------- Views ----------
    private lateinit var statusText: TextView
    private lateinit var audioSpinner: Spinner
    private lateinit var vuMeter: ProgressBar
    private lateinit var recIndicator: TextView
    private lateinit var recTimer: TextView
    private lateinit var previewContainer: FrameLayout
    private lateinit var programContainer: FrameLayout
    private lateinit var previewMirror: ImageView
    private lateinit var programMirror: ImageView
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
        var uvc: MultiCameraClient.Camera? = null
        var encoder: UvcEncoder? = null
        var uvcOpenRequested: Boolean = false
        var uvcStage: String = "conectando"
        var uvcError: String? = null
        var frameCount: Int = 0
        var openedAt: Long = 0L
        var lastTextureW: Int = 0
        var lastTextureH: Int = 0
        var deviceId: Int = -1
        var opened: Boolean = false

        // Espelho pros monitores: a view nativa da UVC nao sai do slot;
        // quem aparece em Previa/Programa e o bitmap convertido dos frames.
        var mirror: FrameMirror? = null
        val mirrorBusy = AtomicBoolean(false)

        @Volatile
        var mirrored: Boolean = false
    }

    private val sources = mutableListOf<Source>()
    private var programId: String? = null
    private var previewId: String? = null
    private var fadeEnabled = false

    // ---------- USB / UVC ----------
    private var cameraClient: MultiCameraClient? = null
    private val pendingCameras = HashMap<Int, MultiCameraClient.Camera>()
    private val mirrorExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Vigia de reconexao USB: a conexao pos-autorizacao da AUSBC e
    // intermitente (as vezes o aviso se perde e a abertura interna falha em
    // silencio). Enquanto houver dispositivo com permissao e sem fonte, o
    // vigia tenta de novo a cada 2s; na 4a tentativa, reinicia o monitor.
    private val reconnectAttempts = HashMap<Int, Int>()
    private var reconnectWatchdogRunning = false

    private fun ensureReconnectWatchdog() {
        if (reconnectWatchdogRunning) return
        reconnectWatchdogRunning = true
        uiHandler.postDelayed(reconnectWatchdog, 1200)
    }

    private val reconnectWatchdog = object : Runnable {
        override fun run() {
            var stillPending = false
            for ((deviceId, camera) in pendingCameras) {
                if (findSourceByDevice(deviceId) != null) {
                    reconnectAttempts.remove(deviceId)
                    continue
                }
                stillPending = true
                val device = camera.getUsbDevice()
                if (cameraClient?.hasPermission(device) != true) continue
                val attempts = (reconnectAttempts[deviceId] ?: 0) + 1
                reconnectAttempts[deviceId] = attempts
                if (attempts > 10) continue
                if (attempts == 4) {
                    diag("reconexao automatica: reiniciando o monitor USB (tentativa 4)")
                    try {
                        cameraClient?.unRegister()
                        cameraClient?.register()
                    } catch (e: Exception) {
                        diag("reconexao automatica: falha ao reiniciar monitor (${e.message})")
                    }
                } else {
                    diag("reconexao automatica: tentativa $attempts (dispositivo $deviceId)")
                    cameraClient?.requestPermission(device)
                }
            }
            if (stillPending) {
                uiHandler.postDelayed(this, 2000)
            } else {
                reconnectWatchdogRunning = false
            }
        }
    }

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

    // ---------- Diagnostico da cadeia USB ----------
    private val diagLog = StringBuilder()

    @Synchronized
    private fun diag(event: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        diagLog.append(time).append("  ").append(event).append('\n')
        if (diagLog.length > 12000) diagLog.delete(0, diagLog.length - 12000)
    }

    private fun showDiagnostics() {
        val text = if (diagLog.isEmpty()) "Nenhum evento registrado ainda." else diagLog.toString()
        val body = TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(Color.parseColor("#F2F5F9"))
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(24, 16, 24, 16)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        android.app.AlertDialog.Builder(this)
            .setTitle("Diagnóstico USB")
            .setView(scroll)
            .setPositiveButton("Compartilhar") { _, _ ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(send, "Enviar diagnóstico"))
            }
            .setNegativeButton("Fechar", null)
            .show()
    }

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
        // Se o app fechou sozinho na abertura anterior, mostra o relatorio
        // do erro em vez de tentar abrir direto (diagnostico da v1).
        val crash = CrashLog.read(this)
        if (crash != null) {
            showCrashReport(crash)
            return
        }
        try {
            initStudio()
        } catch (t: Throwable) {
            CrashLog.write(this, t)
            showCrashReport(CrashLog.read(this) ?: t.toString())
        }
    }

    private fun showCrashReport(trace: String) {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.parseColor("#0B0E13"))
        }
        val title = TextView(this).apply {
            text = "O DX Studio fechou sozinho — relatório do erro"
            textSize = 18f
            setTextColor(Color.WHITE)
        }
        val body = TextView(this).apply {
            text = trace
            textSize = 11f
            setTextColor(Color.parseColor("#F2F5F9"))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        val scroll = ScrollView(this).apply { addView(body) }
        val share = Button(this).apply { text = "COMPARTILHAR RELATÓRIO" }
        share.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, trace)
            }
            startActivity(Intent.createChooser(send, "Enviar relatório"))
        }
        val wipe = Button(this).apply { text = "APAGAR E ABRIR O APP" }
        wipe.setOnClickListener {
            CrashLog.clear(this@MainActivity)
            recreate()
        }
        root.addView(title)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        root.addView(share)
        root.addView(wipe)
        setContentView(root)
    }

    private fun initStudio() {
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        statusText = findViewById(R.id.statusText)
        audioSpinner = findViewById(R.id.audioSpinner)
        vuMeter = findViewById(R.id.vuMeter)
        recIndicator = findViewById(R.id.recIndicator)
        recTimer = findViewById(R.id.recTimer)
        previewContainer = findViewById(R.id.previewContainer)
        programContainer = findViewById(R.id.programContainer)
        previewMirror = findViewById(R.id.previewMirror)
        programMirror = findViewById(R.id.programMirror)
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
        findViewById<Button>(R.id.diagButton).setOnClickListener { showDiagnostics() }

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
        // O dialogo de permissao USB do sistema pausa o app e o aviso de
        // conexao da biblioteca pode se perder nessa ida e volta. Ao retomar:
        // dispositivo com permissao concedida e ainda sem fonte e
        // reconectado aqui (repetir o pedido com permissao conecta na hora).
        for ((_, camera) in pendingCameras) {
            val device = camera.getUsbDevice()
            if (findSourceByDevice(device.deviceId) != null) continue
            if (cameraClient?.hasPermission(device) == true) {
                diag("onResume: reconectando dispositivo ${device.deviceId} (permissao ok, sem fonte)")
                cameraClient?.requestPermission(device)
                ensureReconnectWatchdog()
            }
        }
    }

    // ---------- USB UVC ----------

    private fun setupUsbClient() {
        cameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                diag(
                    "USB attach: ${device.deviceName} id=${device.deviceId} " +
                        "vid=${device.vendorId} pid=${device.productId} classe=${device.deviceClass}"
                )
                if (pendingCameras.containsKey(device.deviceId)) {
                    val hasSource = findSourceByDevice(device.deviceId) != null
                    val hasPerm = cameraClient?.hasPermission(device) == true
                    diag("attach repetido (id=${device.deviceId}); fonteExiste=$hasSource permissao=$hasPerm")
                    if (!hasSource && hasPerm) {
                        // A autorizacao chegou mas o aviso de conexao se
                        // perdeu: pedir de novo, com a permissao ja concedida,
                        // faz a biblioteca conectar na hora (sem dialogo).
                        val sent = cameraClient?.requestPermission(device) ?: false
                        diag("re-pedido pos-autorizacao enviado: $sent")
                        ensureReconnectWatchdog()
                    }
                    return
                }
                val camera = MultiCameraClient.Camera(this@MainActivity, device)
                pendingCameras[device.deviceId] = camera
                val sent = cameraClient?.requestPermission(device) ?: false
                diag("pedido de permissao USB enviado: $sent")
                ensureReconnectWatchdog()
            }

            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                diag("USB detach: id=${device.deviceId}")
                pendingCameras.remove(device.deviceId)
                reconnectAttempts.remove(device.deviceId)
                findSourceByDevice(device.deviceId)?.let { teardownSource(it) }
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                val camera = pendingCameras[device.deviceId]
                diag(
                    "USB connect: id=${device.deviceId} ctrlBlock=${if (ctrlBlock == null) "NULO" else "ok"} " +
                        "cameraPendente=${camera != null}"
                )
                camera ?: return
                if (findSourceByDevice(device.deviceId) != null) {
                    diag("connect ignorado: fonte ja existe (id=${device.deviceId})")
                    return
                }
                camera.setUsbControlBlock(ctrlBlock)
                attachCameraToSlot(camera, device)
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                findSourceByDevice(device.deviceId)?.let { teardownSource(it) }
            }

            override fun onCancelDev(device: UsbDevice?) {
                diag("USB permissao negada/cancelada: id=${device?.deviceId}")
            }
        })
        try {
            cameraClient?.register()
        } catch (e: Exception) {
            // Se o monitor USB falhar, o app segue aberto (camera do tablet
            // funciona) e avisa, em vez de fechar sem explicacao.
            toast("Monitor USB falhou ao iniciar: ${e.message}")
        }
    }

    private fun attachCameraToSlot(camera: MultiCameraClient.Camera, device: UsbDevice) {
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
        // A AUSBC 3.2.7 entrega os frames do preview em NV21; quem codifica
        // e o nosso UvcEncoder (o captureVideoStart dela exige
        // WRITE_EXTERNAL_STORAGE, impossivel no Android 13+). A copia do
        // array e necessaria porque a biblioteca altera o buffer em seguida.
        camera.addPreviewDataCallBack(object : IPreviewDataCallBack {
            override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
                if (source.frameCount == 0) {
                    diag("${source.baseLabel}: primeiro frame de preview recebido")
                }
                source.frameCount++
                if (data != null && source.frameCount % 90 == 0) {
                    // Brilho medio do plano Y (amostrado): perto de 0 = a
                    // plaquinha esta mandando imagem preta (sem sinal HDMI);
                    // valor medio/alto = tem imagem de verdade nos frames.
                    val ySize = data.size * 2 / 3
                    var sum = 0L
                    var n = 0
                    var i = 0
                    while (i < ySize) {
                        sum += data[i].toInt() and 0xFF
                        n++
                        i += 997
                    }
                    diag("${source.baseLabel}: brilho medio do frame = ${if (n > 0) sum / n else 0} (0=preto, 255=branco)")
                }
                // Espelho: se a fonte esta num monitor, converte o frame pra
                // bitmap (no maximo 1 conversao por vez; o resto e descartado)
                val mirror = source.mirror
                if (data != null && mirror != null && source.mirrored &&
                    source.mirrorBusy.compareAndSet(false, true)
                ) {
                    val copy = data.copyOf()
                    mirrorExecutor.execute {
                        try {
                            mirror.convert(copy)
                        } catch (e: Throwable) {
                            // Falhou a conversao: desliga o espelho desta
                            // fonte em vez de insistir (e derrubar o app)
                            diag("${source.baseLabel}: falha no espelho, desligando (${e.message})")
                            source.mirror = null
                        } finally {
                            source.mirrorBusy.set(false)
                        }
                        uiHandler.post { updateMonitorMirrors() }
                    }
                }
                val enc = source.encoder ?: return
                if (data != null) enc.offerNv21(data.copyOf())
            }
        })
        source.uvcStage = "aguardando textura"
        diag("$label: fonte criada (slot logico ${slotIndex + 1}); textura pronta de imediato: ${view.isAvailable}")
        openCameraWhenSurfaceReady(source, camera, view)
        refreshAssignments()
    }

    /**
     * A AUSBC pega a SurfaceTexture da view na hora de abrir a camera; como a
     * nossa view e criada por codigo (nao vem de layout), a textura quase
     * nunca esta pronta nesse instante e o preview nunca inicia (a fonte fica
     * "sem sinal" sem nenhum erro). Entao a abertura espera o aviso de
     * textura pronta. O listener tambem impede a destruicao da textura
     * quando a view muda de container (slot/previa/programa).
     */
    private fun openCameraWhenSurfaceReady(
        source: Source,
        camera: MultiCameraClient.Camera,
        view: AspectRatioTextureView
    ) {
        val openOnce = {
            if (!source.uvcOpenRequested) {
                source.uvcOpenRequested = true
                source.uvcStage = "abrindo camera"
                diag("${source.baseLabel}: chamando openCamera")
                camera.openCamera(view, uvcRequest())
            }
        }
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                diag("${source.baseLabel}: textura pronta (${width}x$height)")
                source.lastTextureW = width
                source.lastTextureH = height
                openOnce()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                // Nao destruir: a mesma view migra entre slot, previa e programa.
                return false
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        if (view.isAvailable) openOnce()
    }

    private fun uvcRequest(): CameraRequest {
        // Na 3.2.7 o CameraRequest so define a resolucao do preview.
        return CameraRequest.Builder()
            .setPreviewWidth(ProgramRecorder.WIDTH)
            .setPreviewHeight(ProgramRecorder.HEIGHT)
            .create()
    }

    private fun teardownSource(source: Source) {
        stopSourceEncoder(source)
        source.mirror?.destroy()
        source.mirror = null
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
        self: MultiCameraClient.Camera,
        code: ICameraStateCallBack.State,
        msg: String?
    ) {
        val source = findSourceByDevice(self.getUsbDevice().deviceId) ?: return
        diag("${source.baseLabel}: estado=$code msg=${msg ?: "-"}")
        when (code) {
            ICameraStateCallBack.State.OPENED -> {
                source.opened = true
                source.openedAt = System.currentTimeMillis()
                source.uvcError = null
                if (source.mirror == null) {
                    // Espelho dos monitores, no tamanho real do preview
                    val size = self.getPreviewSize()
                    val w = size?.width ?: ProgramRecorder.WIDTH
                    val h = size?.height ?: ProgramRecorder.HEIGHT
                    source.mirror = try {
                        FrameMirror(w, h).also {
                            diag("${source.baseLabel}: espelho criado (${w}x$h)")
                        }
                    } catch (e: Throwable) {
                        diag("${source.baseLabel}: espelho indisponivel (${e.message})")
                        null
                    }
                }
                onSourceOpened(source)
            }
            ICameraStateCallBack.State.CLOSED -> {
                source.opened = false
                source.uvcStage = "fechada"
                onSourceClosed(source)
            }
            ICameraStateCallBack.State.ERROR -> {
                source.opened = false
                source.uvcError = msg ?: "desconhecido"
                source.uvcStage = "erro"
                toast("${source.baseLabel}: erro ao abrir (${msg ?: "desconhecido"})")
                refreshAssignments()
            }
        }
    }

    private fun onSourceOpened(source: Source) {
        if (source.uvc != null) {
            // Vigia: camera "aberta" mas sem nenhum frame em 4s = preview morto
            uiHandler.postDelayed({
                if (source.opened && source.frameCount == 0) {
                    source.uvcStage = "aberta, sem frames"
                    diag("${source.baseLabel}: vigia - aberta ha 4s e nenhum frame chegou")
                    refreshAssignments()
                }
            }, 4000)
        }
        if (recording && source.uvc != null) {
            // Camera plugada no meio da gravacao: liga o encoder dela
            startSourceEncoder(source)
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

    /** Atualiza os espelhos dos monitores (fontes UVC aparecem via bitmap). */
    private fun updateMonitorMirrors() {
        val previewSrc = sources.firstOrNull { it.id == previewId }
        val programSrc = sources.firstOrNull { it.id == programId }
        if (previewSrc?.uvc != null) {
            previewMirror.visibility = View.VISIBLE
            previewSrc.mirror?.latest?.let { previewMirror.setImageBitmap(it) }
        } else {
            previewMirror.visibility = View.GONE
        }
        if (programSrc?.uvc != null) {
            programMirror.visibility = View.VISIBLE
            programSrc.mirror?.latest?.let { programMirror.setImageBitmap(it) }
        } else {
            programMirror.visibility = View.GONE
        }
    }

    /** Move as views das fontes pros containers certos e atualiza rotulos/bordas. */
    private fun refreshAssignments() {
        for (source in sources) {
            // Fonte UVC: a view nativa NUNCA sai do slot (a superficie morre
            // na migracao); nos monitores ela aparece pelo espelho.
            source.mirrored = source.uvc != null &&
                (source.id == previewId || source.id == programId)
            val target = when {
                source.uvc != null -> slotContainers[source.slotIndex]
                source.id == programId -> programContainer
                source.id == previewId -> previewContainer
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
            val state = when {
                source.uvc == null -> if (source.opened) "" else " · sem sinal"
                source.opened && source.frameCount > 0 -> ""
                source.opened -> " · aberta, sem frames"
                source.uvcError != null -> " · erro: ${source.uvcError}"
                else -> " · ${source.uvcStage}"
            }
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
        updateMonitorMirrors()
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

    private fun startSourceEncoder(source: Source) {
        if (source.uvc == null || source.encoder != null) return
        val rec = recorder ?: return
        val enc = UvcEncoder(ProgramRecorder.WIDTH, ProgramRecorder.HEIGHT, source.id, rec)
        source.encoder = enc
        enc.start()
    }

    private fun stopSourceEncoder(source: Source) {
        source.encoder?.stop()
        source.encoder = null
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
        // Fontes UVC: liga o encoder de todas as abertas (o gravador filtra pelo programa)
        for (source in sources) {
            if (source.opened) startSourceEncoder(source)
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
            stopSourceEncoder(source)
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
        mirrorExecutor.shutdownNow()
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
