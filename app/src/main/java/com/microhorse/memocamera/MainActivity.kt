package com.microhorse.memocamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {

    private val volumeShutterFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Volatile
    var isCameraPreviewActive: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    MemoCameraApp(
                        volumeShutterEvents = volumeShutterFlow,
                        onPreviewStateChanged = { isCameraPreviewActive = it }
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (isCameraPreviewActive) {
                if (event?.repeatCount == 0) {
                    volumeShutterFlow.tryEmit(Unit)
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}

private enum class CaptureMode {
    PHOTO,
    VIDEO
}

private sealed interface CaptureUiState {
    data object CameraPreview : CaptureUiState
    data class PhotoMemoInput(
        val capturedBitmap: Bitmap,
        val capturedTimeMillis: Long,
        val capturedLocation: Location?
    ) : CaptureUiState
    data class VideoMemoInput(
        val rawVideoFile: File,
        val previewBitmap: Bitmap?,
        val durationMillis: Long,
        val capturedTimeMillis: Long,
        val capturedLocation: Location?
    ) : CaptureUiState
}

private fun hasLocationPermissionGranted(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@SuppressLint("MissingPermission")
private fun getBestLastKnownLocation(context: Context): Location? {
    if (!hasLocationPermissionGranted(context)) return null
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER,
        "fused"
    )
    var bestLocation: Location? = null
    for (provider in providers) {
        val loc = try {
            locationManager.getLastKnownLocation(provider)
        } catch (_: Exception) {
            null
        } ?: continue
        if (bestLocation == null || loc.time > bestLocation.time) {
            bestLocation = loc
        }
    }
    return bestLocation
}

@Composable
fun MemoCameraApp(
    volumeShutterEvents: SharedFlow<Unit>,
    onPreviewStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val trialAndBillingManager = remember { TrialAndBillingManager(context) }
    val licenseState by trialAndBillingManager.uiState.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            trialAndBillingManager.release()
        }
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasLocationPermission by remember {
        mutableStateOf(hasLocationPermissionGranted(context))
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPermission = result[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED)
        hasLocationPermission = hasLocationPermissionGranted(context)
        trialAndBillingManager.refreshTrialState()
    }

    LaunchedEffect(Unit) {
        trialAndBillingManager.refreshTrialState()
        val hasAudio = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission || !hasLocationPermission || !hasAudio) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // 4日間の無料体験終了後（または時計巻き戻し検知時）かつ未課金の場合はPro解放画面を表示
    if (!licenseState.canUseApp) {
        LaunchedEffect(Unit) {
            onPreviewStateChanged(false)
        }
        ProPaywallScreen(
            licenseState = licenseState,
            onPurchaseClick = {
                if (activity != null) {
                    trialAndBillingManager.launchPurchaseFlow(activity)
                }
            },
            onRestoreClick = {
                trialAndBillingManager.restorePurchases()
            },
            onSecretUnlockSubmit = { code ->
                val unlocked = trialAndBillingManager.trySecretUnlock(code)
                if (unlocked) {
                    Toast.makeText(
                        context,
                        "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        context,
                        "コードが正しくありません",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                unlocked
            }
        )
    } else if (!hasCameraPermission) {
        LaunchedEffect(Unit) {
            onPreviewStateChanged(false)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    permissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.CAMERA,
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            ) {
                Text(
                    text = "カメラ権限を許可する",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    } else {
        MemoCameraMainScreen(
            hasLocationPermission = hasLocationPermission,
            trialAndBillingManager = trialAndBillingManager,
            volumeShutterEvents = volumeShutterEvents,
            onPreviewStateChanged = onPreviewStateChanged
        )
    }
}

@SuppressLint("ClickableViewAccessibility", "MissingPermission")
@Composable
private fun MemoCameraMainScreen(
    hasLocationPermission: Boolean,
    trialAndBillingManager: TrialAndBillingManager,
    volumeShutterEvents: SharedFlow<Unit>,
    onPreviewStateChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var uiState by remember { mutableStateOf<CaptureUiState>(CaptureUiState.CameraPreview) }
    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var memoText by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedTime by remember { mutableLongStateOf(0L) }

    // 動画録画状態
    var activeRecording by remember { mutableStateOf<Recording?>(null) }
    var isRecordingVideo by remember { mutableStateOf(false) }
    var recordingElapsedSeconds by remember { mutableIntStateOf(0) }

    // GPS現在地（EXIF / MP4位置情報記録用）
    var currentLocation by remember { mutableStateOf<Location?>(getBestLastKnownLocation(context)) }

    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) {
            onDispose { }
        } else {
            currentLocation = getBestLastKnownLocation(context)
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val listener = LocationListener { loc ->
                currentLocation = loc
            }
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                "fused"
            )
            providers.forEach { provider ->
                try {
                    if (locationManager?.isProviderEnabled(provider) == true) {
                        locationManager.requestLocationUpdates(
                            provider,
                            2000L,
                            1f,
                            listener,
                            Looper.getMainLooper()
                        )
                    }
                } catch (_: Exception) {
                }
            }
            onDispose {
                try {
                    locationManager?.removeUpdates(listener)
                } catch (_: Exception) {
                }
            }
        }
    }

    // セルフタイマー設定（0秒 -> 3秒 -> 10秒）とカウントダウン残り秒数
    var timerSettingSeconds by remember { mutableIntStateOf(0) }
    var countdownRemaining by remember { mutableIntStateOf(0) }
    var countdownJob by remember { mutableStateOf<Job?>(null) }

    // ライト常時点灯（トーチ）モード状態
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    // ズーム状態（0.5x / 1x / 2x ワンタップ ＆ ピンチズーム連動）
    var currentZoomRatio by remember { mutableFloatStateOf(1.0f) }
    var minZoomRatio by remember { mutableFloatStateOf(0.5f) }
    var maxZoomRatio by remember { mutableFloatStateOf(8.0f) }

    LaunchedEffect(uiState) {
        onPreviewStateChanged(uiState is CaptureUiState.CameraPreview)
    }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }

    // 標準カメラアプリ同様のピンチイン・ピンチアウト＆タップフォーカス
    DisposableEffect(previewView, cameraInstance) {
        val cam = cameraInstance
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val activeCam = cameraInstance ?: return false
                    val current = activeCam.cameraInfo.zoomState.value?.zoomRatio ?: currentZoomRatio
                    val nextZoom = (current * detector.scaleFactor).coerceIn(minZoomRatio, maxZoomRatio)
                    activeCam.cameraControl.setZoomRatio(nextZoom)
                    currentZoomRatio = nextZoom
                    return true
                }
            }
        )

        previewView.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)
            if (!scaleGestureDetector.isInProgress && event.action == MotionEvent.ACTION_UP) {
                val activeCam = cameraInstance
                if (activeCam != null) {
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(event.x, event.y)
                    val action = FocusMeteringAction.Builder(point).build()
                    activeCam.cameraControl.startFocusAndMetering(action)
                }
                view.performClick()
            }
            true
        }

        val zoomLiveData = cam?.cameraInfo?.zoomState
        val observer = androidx.lifecycle.Observer<androidx.camera.core.ZoomState> { state ->
            if (state != null) {
                currentZoomRatio = state.zoomRatio
                minZoomRatio = state.minZoomRatio
                maxZoomRatio = state.maxZoomRatio
            }
        }
        zoomLiveData?.observe(lifecycleOwner, observer)

        onDispose {
            zoomLiveData?.removeObserver(observer)
            previewView.setOnTouchListener(null)
        }
    }

    val imageCapture = remember {
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .build()
    }

    val videoCapture = remember {
        val qualitySelector = QualitySelector.from(
            Quality.HD,
            FallbackStrategy.lowerQualityOrHigherThan(Quality.HD)
        )
        val recorder = Recorder.Builder()
            .setAspectRatio(AspectRatio.RATIO_4_3)
            .setQualitySelector(qualitySelector)
            .build()
        VideoCapture.withOutput(recorder)
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            countdownJob?.cancel()
            try {
                activeRecording?.stop()
            } catch (_: Exception) {
            }
            cameraInstance?.cameraControl?.enableTorch(false)
            cameraExecutor.shutdown()
        }
    }

    fun performShutterHaptic() {
        previewView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        )
    }

    fun setTargetZoom(target: Float) {
        val cam = cameraInstance ?: return
        val clamped = target.coerceIn(minZoomRatio, maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        currentZoomRatio = clamped
    }

    fun executePhotoCaptureNow() {
        trialAndBillingManager.refreshTrialState()
        if (!trialAndBillingManager.uiState.value.canUseApp) return
        if (isCapturing || uiState !is CaptureUiState.CameraPreview) return
        isCapturing = true
        performShutterHaptic()
        val now = System.currentTimeMillis()
        lastCapturedTime = now
        val locationAtCapture = currentLocation ?: getBestLastKnownLocation(context)

        imageCapture.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val rawBitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    image.close()

                    val normalizedBitmap = ImageSynthesizer.normalizeRotation(
                        rawBitmap,
                        rotation
                    )

                    ContextCompat.getMainExecutor(context).execute {
                        memoText = ""
                        isCapturing = false
                        uiState = CaptureUiState.PhotoMemoInput(
                            capturedBitmap = normalizedBitmap,
                            capturedTimeMillis = lastCapturedTime,
                            capturedLocation = locationAtCapture
                        )
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    ContextCompat.getMainExecutor(context).execute {
                        isCapturing = false
                        Toast.makeText(
                            context,
                            "撮影失敗: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    fun toggleVideoRecording() {
        if (uiState !is CaptureUiState.CameraPreview) return

        val currentRec = activeRecording
        if (currentRec != null || isRecordingVideo) {
            performShutterHaptic()
            try {
                currentRec?.stop()
            } catch (_: Exception) {
                activeRecording = null
                isRecordingVideo = false
            }
            return
        }

        trialAndBillingManager.refreshTrialState()
        if (!trialAndBillingManager.uiState.value.canUseApp) return

        performShutterHaptic()
        val now = System.currentTimeMillis()
        lastCapturedTime = now
        val locationAtCapture = currentLocation ?: getBestLastKnownLocation(context)

        val rawVideoFile = File(context.cacheDir, "memo_raw_${now}.mp4")
        val outputOptions = FileOutputOptions.Builder(rawVideoFile)
            .apply {
                if (locationAtCapture != null) {
                    setLocation(locationAtCapture)
                }
            }
            .build()

        val hasAudioPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        try {
            val pending = videoCapture.output.prepareRecording(context, outputOptions)
            if (hasAudioPermission) {
                pending.withAudioEnabled()
            }

            isRecordingVideo = true
            recordingElapsedSeconds = 0

            activeRecording = pending.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        isRecordingVideo = true
                        recordingElapsedSeconds = 0
                    }
                    is VideoRecordEvent.Status -> {
                        recordingElapsedSeconds =
                            (event.recordingStats.recordedDurationNanos / 1_000_000_000L).toInt()
                    }
                    is VideoRecordEvent.Finalize -> {
                        activeRecording = null
                        isRecordingVideo = false
                        recordingElapsedSeconds = 0

                        if (rawVideoFile.exists() && rawVideoFile.length() > 0L) {
                            coroutineScope.launch(Dispatchers.IO) {
                                val (previewBmp, durationMs) =
                                    VideoSynthesizer.extractPreviewFrameAndDuration(rawVideoFile)
                                withContext(Dispatchers.Main) {
                                    memoText = ""
                                    uiState = CaptureUiState.VideoMemoInput(
                                        rawVideoFile = rawVideoFile,
                                        previewBitmap = previewBmp,
                                        durationMillis = durationMs,
                                        capturedTimeMillis = now,
                                        capturedLocation = locationAtCapture
                                    )
                                }
                            }
                        } else {
                            try {
                                rawVideoFile.delete()
                            } catch (_: Exception) {
                            }
                            Toast.makeText(
                                context,
                                "動画撮影に失敗しました",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            activeRecording = null
            isRecordingVideo = false
            Toast.makeText(
                context,
                "録画開始エラー: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun triggerShutterAction() {
        if (uiState !is CaptureUiState.CameraPreview) return

        if (captureMode == CaptureMode.VIDEO) {
            toggleVideoRecording()
            return
        }

        if (isCapturing) return

        // カウントダウン中に押された場合はキャンセル
        if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownRemaining = 0
            return
        }

        if (timerSettingSeconds == 0) {
            executePhotoCaptureNow()
        } else {
            countdownJob = coroutineScope.launch {
                for (sec in timerSettingSeconds downTo 1) {
                    countdownRemaining = sec
                    delay(1000L)
                }
                countdownRemaining = 0
                executePhotoCaptureNow()
            }
        }
    }

    // 音量ボタン（＋／ー）でのシャッター・録画開始/停止連動
    LaunchedEffect(Unit) {
        volumeShutterEvents.collect {
            triggerShutterAction()
        }
    }

    // カメラバインド（3ユースケース同時バインド優先、非対応機種のみモード別バインドへ自動フォールバック）
    var supportsTripleUseCase by remember { mutableStateOf(true) }

    LaunchedEffect(lifecycleOwner, captureMode, supportsTripleUseCase) {
        if (supportsTripleUseCase && cameraInstance != null) {
            // 既に3ユースケース同時バインド済みの場合は再バインド不要（0msでモード切替）
            return@LaunchedEffect
        }
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val previewResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(previewResolutionSelector)
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            try {
                cameraProvider.unbindAll()
                val cam = try {
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                        videoCapture
                    )
                } catch (_: Exception) {
                    supportsTripleUseCase = false
                    if (captureMode == CaptureMode.PHOTO) {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageCapture
                        )
                    } else {
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            videoCapture
                        )
                    }
                }
                cameraInstance = cam
                cam.cameraInfo.zoomState.value?.let { zs ->
                    currentZoomRatio = zs.zoomRatio
                    minZoomRatio = zs.minZoomRatio
                    maxZoomRatio = zs.maxZoomRatio
                }
                if (isTorchOn && cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(true)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "カメラ初期化エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 【状態1: カメラプレビュー（3:4 プレビュー ＋ ズームピル ＋ 写真/動画切替 ＋ シャッターバー）】
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 中央：3:4 フル画角カメラプレビュー ＋ 下部中央に標準カメラ風の「0.5x / 1x / 2x」ワンタップズームピル
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                )

                // カウントダウン表示
                if (countdownRemaining > 0) {
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(Color(0x99000000))
                            .border(3.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = countdownRemaining.toString(),
                            color = Color.White,
                            fontSize = 56.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // プレビュー下部：標準カメラアプリ風 ワンタップズーム切替ピル（.5 / 1x / 2）
                ZoomSelectorPill(
                    currentZoomRatio = currentZoomRatio,
                    minZoomRatio = minZoomRatio,
                    maxZoomRatio = maxZoomRatio,
                    onSelectZoom = { target -> setTargetZoom(target) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp)
                )
            }

            // 下部コントロールパネル（上段＝「写真 / 動画」ワンタップ切替、下段＝タイマー/録画時間・シャッター・ライト）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161615))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 上段：「写真 / 動画」ワンタップ切替ピル（固定幅でレイアウトシフト完全ゼロ）
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF262624))
                        .border(1.dp, Color(0xFF3E3D38), RoundedCornerShape(20.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isPhotoMode = captureMode == CaptureMode.PHOTO
                    Box(
                        modifier = Modifier
                            .width(76.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isPhotoMode) Color(0xFFFFFFFF) else Color.Transparent
                            )
                            .clickable(enabled = !isRecordingVideo && !isCapturing) {
                                if (countdownRemaining > 0) {
                                    countdownJob?.cancel()
                                    countdownRemaining = 0
                                }
                                captureMode = CaptureMode.PHOTO
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "写真",
                            color = if (isPhotoMode) Color(0xFF1C1C1A) else Color(0xFFA6A39B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(76.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (!isPhotoMode) Color(0xFFB51C22) else Color.Transparent
                            )
                            .clickable(enabled = !isRecordingVideo && !isCapturing) {
                                if (countdownRemaining > 0) {
                                    countdownJob?.cancel()
                                    countdownRemaining = 0
                                }
                                captureMode = CaptureMode.VIDEO
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "動画",
                            color = if (!isPhotoMode) Color(0xFFFFFFFF) else Color(0xFFA6A39B),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // 下段：左＝タイマー（動画時は録画時間表示）、中央＝シャッター/録画ボタン、右＝ライト常時点灯
                Box(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (captureMode == CaptureMode.PHOTO) {
                        // 左側（写真モード）：セルフタイマー切替ボタン（OFF -> 3秒 -> 10秒）
                        val timerActive = timerSettingSeconds > 0
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(92.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    if (timerActive) Color(0xFFB51C22) else Color(0xFF262624)
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (timerActive) Color(0xFFFFFFFF) else Color(0xFF6E6B64),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .clickable {
                                    if (countdownRemaining > 0) {
                                        countdownJob?.cancel()
                                        countdownRemaining = 0
                                    }
                                    timerSettingSeconds = when (timerSettingSeconds) {
                                        0 -> 3
                                        3 -> 10
                                        else -> 0
                                    }
                                }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val timerLabel = when (timerSettingSeconds) {
                                3 -> "⏱ 3秒"
                                10 -> "⏱ 10秒"
                                else -> "⏱ OFF"
                            }
                            Text(
                                text = timerLabel,
                                color = Color(0xFFFFFFFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        // 左側（動画モード）：録画時間インジケーター（固定幅 92.dp でガタつきなし）
                        val mins = recordingElapsedSeconds / 60
                        val secs = recordingElapsedSeconds % 60
                        val timeFormatted = String.format(Locale.US, "● %02d:%02d", mins, secs)
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(92.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    if (isRecordingVideo) Color(0xFFB51C22) else Color(0xFF262624)
                                )
                                .border(
                                    width = 1.5.dp,
                                    color = if (isRecordingVideo) Color(0xFFFFFFFF) else Color(0xFF6E6B64),
                                    shape = RoundedCornerShape(24.dp)
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = timeFormatted,
                                color = Color(0xFFFFFFFF),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // 中央：シャッターボタン（写真モード＝白丸、動画待機＝深紅丸、動画録画中＝深紅角丸四角）
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(80.dp)
                            .clip(CircleShape)
                            .border(4.dp, Color(0xFFFFFFFF), CircleShape)
                            .clickable(
                                enabled = !isCapturing && uiState is CaptureUiState.CameraPreview
                            ) {
                                triggerShutterAction()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (captureMode == CaptureMode.PHOTO) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isCapturing -> Color(0xFF6E6B64)
                                            countdownRemaining > 0 -> Color(0xFFB51C22)
                                            else -> Color(0xFFFFFFFF)
                                        }
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (countdownRemaining > 0) {
                                    Text(
                                        text = "■",
                                        color = Color(0xFFFFFFFF),
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } else {
                            // 動画モード：待機中は深紅の丸、録画中は深紅の停止四角（■）
                            Box(
                                modifier = Modifier
                                    .size(if (isRecordingVideo) 32.dp else 64.dp)
                                    .clip(
                                        if (isRecordingVideo) RoundedCornerShape(8.dp) else CircleShape
                                    )
                                    .background(Color(0xFFB51C22))
                            )
                        }
                    }

                    // 右側：ライト点灯モード（常時点灯 ON/OFF・固定幅 92.dp で枠サイズ不変）ボタン
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(92.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(
                                if (isTorchOn) Color(0xFFFFFFFF) else Color(0xFF262624)
                            )
                            .border(
                                width = 1.5.dp,
                                color = if (isTorchOn) Color(0xFFFFFFFF) else Color(0xFF6E6B64),
                                shape = RoundedCornerShape(24.dp)
                            )
                            .clickable {
                                val cam = cameraInstance
                                if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                                    Toast.makeText(
                                        context,
                                        "ライト非対応の端末です",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    val nextState = !isTorchOn
                                    isTorchOn = nextState
                                    cam.cameraControl.enableTorch(nextState)
                                }
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "🔦 ライト",
                            color = if (isTorchOn) Color(0xFF1C1C1A) else Color(0xFFFFFFFF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 【状態2: 写真または動画のメモ入力オーバーレイ】
        val currentState = uiState
        if (currentState is CaptureUiState.PhotoMemoInput) {
            MemoInputOverlay(
                previewBitmap = currentState.capturedBitmap,
                videoBadgeText = null,
                memoText = memoText,
                onMemoChange = { input ->
                    if (trialAndBillingManager.trySecretUnlock(input)) {
                        memoText = ""
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        memoText = input
                    }
                },
                onDiscard = {
                    val bitmapToRecycle = currentState.capturedBitmap
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview
                    bitmapToRecycle.recycle()
                },
                onSaveAndNext = {
                    val bitmapToSave = currentState.capturedBitmap
                    val textToSave = if (trialAndBillingManager.trySecretUnlock(memoText)) {
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                        ""
                    } else {
                        memoText
                    }
                    val timeToSave = currentState.capturedTimeMillis
                    val locationToSave = currentState.capturedLocation

                    // 即座にカメラへ戻る
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview

                    // 裏側で合成＆カメラロール保存
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedUri = ImageSynthesizer.synthesizeAndSave(
                            context = appContext,
                            originalBitmap = bitmapToSave,
                            memoText = textToSave,
                            capturedTimeMillis = timeToSave,
                            location = locationToSave
                        )
                        withContext(Dispatchers.Main) {
                            trialAndBillingManager.refreshTrialState()
                            val msg = if (savedUri != null) {
                                "写真を保存しました"
                            } else {
                                "保存に失敗しました"
                            }
                            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        } else if (currentState is CaptureUiState.VideoMemoInput) {
            val totalSecs = max(1, (currentState.durationMillis / 1000L).toInt())
            val badge = String.format(Locale.US, "● 動画 %02d:%02d", totalSecs / 60, totalSecs % 60)

            MemoInputOverlay(
                previewBitmap = currentState.previewBitmap,
                videoBadgeText = badge,
                memoText = memoText,
                onMemoChange = { input ->
                    if (trialAndBillingManager.trySecretUnlock(input)) {
                        memoText = ""
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        memoText = input
                    }
                },
                onDiscard = {
                    val rawFile = currentState.rawVideoFile
                    val previewBmp = currentState.previewBitmap
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview
                    previewBmp?.recycle()
                    try {
                        if (rawFile.exists()) rawFile.delete()
                    } catch (_: Exception) {
                    }
                },
                onSaveAndNext = {
                    val rawFile = currentState.rawVideoFile
                    val previewBmp = currentState.previewBitmap
                    val textToSave = if (trialAndBillingManager.trySecretUnlock(memoText)) {
                        Toast.makeText(
                            context,
                            "✨ Pro永久ライセンス（一生の相棒）が解放されました！",
                            Toast.LENGTH_LONG
                        ).show()
                        ""
                    } else {
                        memoText
                    }
                    val timeToSave = currentState.capturedTimeMillis
                    val locationToSave = currentState.capturedLocation

                    // 即座にカメラへ戻る
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview
                    previewBmp?.recycle()
                    Toast.makeText(appContext, "動画を合成・保存中です...", Toast.LENGTH_SHORT).show()

                    // 裏側で動画＋下部白余白＋メモを合成してカメラロールへMP4保存
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedUri = VideoSynthesizer.synthesizeAndSave(
                            context = appContext,
                            rawVideoFile = rawFile,
                            memoText = textToSave,
                            capturedTimeMillis = timeToSave,
                            location = locationToSave
                        )
                        withContext(Dispatchers.Main) {
                            trialAndBillingManager.refreshTrialState()
                            val msg = if (savedUri != null) {
                                "動画を保存しました"
                            } else {
                                "動画の保存に失敗しました"
                            }
                            Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
        }
    }
}

/**
 * 標準カメラアプリ風のズーム切替カプセル（0.5x / 1x / 2x ワンタップ切替 ＋ ピンチズーム時の倍率表示）
 */
@Composable
private fun ZoomSelectorPill(
    currentZoomRatio: Float,
    minZoomRatio: Float,
    maxZoomRatio: Float,
    onSelectZoom: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val ultraWideTarget = if (minZoomRatio < 1.0f) max(0.5f, minZoomRatio) else 0.5f
    val twoXTarget = min(2.0f, maxZoomRatio)

    val isUltraWideSelected = currentZoomRatio < 0.85f
    val isOneXSelected = currentZoomRatio in 0.85f..1.49f
    val isTwoXSelected = currentZoomRatio >= 1.5f

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x99000000))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZoomButtonItem(
            label = if (isUltraWideSelected) {
                formatZoomLabel(currentZoomRatio)
            } else {
                ".5"
            },
            isSelected = isUltraWideSelected,
            onClick = { onSelectZoom(ultraWideTarget) }
        )

        ZoomButtonItem(
            label = if (isOneXSelected) {
                formatZoomLabel(currentZoomRatio)
            } else {
                "1"
            },
            isSelected = isOneXSelected,
            onClick = { onSelectZoom(1.0f) }
        )

        ZoomButtonItem(
            label = if (isTwoXSelected) {
                formatZoomLabel(currentZoomRatio)
            } else {
                "2"
            },
            isSelected = isTwoXSelected,
            onClick = { onSelectZoom(twoXTarget) }
        )
    }
}

private fun formatZoomLabel(zoom: Float): String {
    val rounded = (zoom * 10f).toInt() / 10f
    return if (abs(rounded - rounded.toInt()) < 0.05f) {
        "${rounded.toInt()}x"
    } else {
        String.format(Locale.US, "%.1fx", rounded)
    }
}

@Composable
private fun ZoomButtonItem(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(if (isSelected) 38.dp else 32.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) Color(0xFFFFFFFF) else Color(0x44FFFFFF)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isSelected) Color(0xFF1C1C1A) else Color(0xFFFFFFFF),
            fontSize = if (isSelected) 12.sp else 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun MemoInputOverlay(
    previewBitmap: Bitmap?,
    videoBadgeText: String?,
    memoText: String,
    onMemoChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onSaveAndNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onDiscard)

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        // 上部：撮影プレビュー（最大化）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "撮影プレビュー",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }

            if (videoBadgeText != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFFB51C22))
                        .border(1.5.dp, Color(0xFFFFFFFF), RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = videoBadgeText,
                        color = Color(0xFFFFFFFF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        // 下部：高さを小さく抑えたコンパクトなテキスト入力欄＋ボタン
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFFFFFFFF))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("メモを入力（空欄でも保存可）", fontSize = 14.sp) },
                minLines = 1,
                maxLines = 3
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = "撮り直し",
                        color = Color(0xFF1C1C1A),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSaveAndNext,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(40.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB51C22),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "保存",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

/**
 * 4日間の全機能無料体験終了後に表示される「メモカメラ Pro（買い切り・永久ライセンス）」案内画面
 */
@Composable
private fun ProPaywallScreen(
    licenseState: LicenseUiState,
    onPurchaseClick: () -> Unit,
    onRestoreClick: () -> Unit,
    onSecretUnlockSubmit: (String) -> Boolean
) {
    var showSecretDialog by remember { mutableStateOf(false) }
    var secretCodeInput by remember { mutableStateOf("") }

    if (showSecretDialog) {
        val focusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showSecretDialog = false },
            title = {
                Text(
                    text = "ライセンスコード入力",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        text = "オーナー・専用ライセンスコードをお持ちの場合は入力してください。",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretCodeInput,
                        onValueChange = { secretCodeInput = it },
                        placeholder = { Text("コードを入力") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                    LaunchedEffect(Unit) {
                        focusRequester.requestFocus()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (onSecretUnlockSubmit(secretCodeInput)) {
                            secretCodeInput = ""
                            showSecretDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFB51C22),
                        contentColor = Color.White
                    )
                ) {
                    Text("解除する", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSecretDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F1115))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 100年残る光学＋3本水平線アイコン（タップでもライセンスコード入力ダイアログを開ける）
            Box(
                modifier = Modifier
                    .size(92.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFFFFF))
                    .clickable {
                        secretCodeInput = ""
                        showSecretDialog = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier.size(width = 48.dp, height = 38.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1C1C1A)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFFFFFFF))
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 1.dp, end = 2.dp)
                                .size(6.5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFB51C22))
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.size(width = 44.dp, height = 16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 2.8.dp)
                                .background(Color(0xFF1C1C1A))
                        )
                        Spacer(modifier = Modifier.height(3.6.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 44.dp, height = 2.8.dp)
                                .background(Color(0xFF1C1C1A))
                        )
                        Spacer(modifier = Modifier.height(3.6.dp))
                        Box(
                            modifier = Modifier
                                .size(width = 26.dp, height = 2.8.dp)
                                .background(Color(0xFFB51C22))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x26B51C22))
                    .border(1.dp, Color(0xFFB51C22), RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (licenseState.isClockTampered) {
                        "端末の時計設定を確認してください"
                    } else {
                        "4日間の無料体験期間が終了しました"
                    },
                    color = Color(0xFFFFFFFF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "メモカメラ Pro",
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "一生の相棒として、シンプルに記録し続ける",
                color = Color(0xFFCBD5E1),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.height(22.dp))

            // 特徴カード
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF181B22))
                    .border(1.dp, Color(0xFF2A2F3A), RoundedCornerShape(16.dp))
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FeatureRow(
                    title = "買い切り・永久ライセンス（サブスクなし）",
                    desc = "一度のご購入で、今後ずっと追加課金なしで全機能をお使いいただけます。"
                )
                FeatureRow(
                    title = "広告ゼロ・通信ゼロの圧倒的安定感",
                    desc = "写真・動画もメモもGPS位置情報も、外部サーバーへ一切送信せず端末内だけに即座に保存します。"
                )
                FeatureRow(
                    title = "機種変更してもそのまま復元",
                    desc = "Googleアカウントに紐づくため、将来スマートフォンを買い替えても無料で復元できます。"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onPurchaseClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD0102C),
                    contentColor = Color.White
                )
            ) {
                Text(
                    text = "${licenseState.formattedPrice} で永久解放する（買い切り）",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onRestoreClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "購入を復元する（機種変更・再インストール時）",
                    color = Color(0xFFE2E8F0),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = {
                    secretCodeInput = ""
                    showSecretDialog = true
                }
            ) {
                Text(
                    text = "ライセンスコードを入力",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(
    title: String,
    desc: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xFFB51C22)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "✓",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        Column {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                color = Color(0xFF94A3B8),
                fontSize = 12.sp
            )
        }
    }
}
