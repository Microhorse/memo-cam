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

private sealed interface CaptureUiState {
    data object CameraPreview : CaptureUiState
    data class MemoInput(
        val capturedBitmap: Bitmap,
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
        if (!hasCameraPermission || !hasLocationPermission) {
            permissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.CAMERA,
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
    var memoText by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedTime by remember { mutableLongStateOf(0L) }

    // GPS現在地（EXIF位置情報記録用）
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
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            countdownJob?.cancel()
            cameraInstance?.cameraControl?.enableTorch(false)
            cameraExecutor.shutdown()
        }
    }

    fun setTargetZoom(target: Float) {
        val cam = cameraInstance ?: return
        val clamped = target.coerceIn(minZoomRatio, maxZoomRatio)
        cam.cameraControl.setZoomRatio(clamped)
        currentZoomRatio = clamped
    }

    fun executeCaptureNow() {
        trialAndBillingManager.refreshTrialState()
        if (!trialAndBillingManager.uiState.value.canUseApp) return
        if (isCapturing || uiState !is CaptureUiState.CameraPreview) return
        isCapturing = true
        previewView.performHapticFeedback(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }
        )
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
                        uiState = CaptureUiState.MemoInput(
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

    fun triggerShutterOrTimer() {
        if (isCapturing || uiState !is CaptureUiState.CameraPreview) return

        // カウントダウン中に押された場合はキャンセル
        if (countdownRemaining > 0) {
            countdownJob?.cancel()
            countdownRemaining = 0
            return
        }

        if (timerSettingSeconds == 0) {
            executeCaptureNow()
        } else {
            countdownJob = coroutineScope.launch {
                for (sec in timerSettingSeconds downTo 1) {
                    countdownRemaining = sec
                    delay(1000L)
                }
                countdownRemaining = 0
                executeCaptureNow()
            }
        }
    }

    // 音量ボタン（＋／ー）でのシャッター連動
    LaunchedEffect(Unit) {
        volumeShutterEvents.collect {
            triggerShutterOrTimer()
        }
    }

    LaunchedEffect(lifecycleOwner) {
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
                val cam = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture
                )
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
        // 【状態1: カメラプレビュー（3:4 プレビュー ＋ ズームピル ＋ タイマー・シャッター・ライト）】
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

            // 下部：左＝セルフタイマー、中央＝シャッター、右＝ライト常時点灯（石色×墨黒×深紅の100年デザイン配色）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161615))
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                // 左側：セルフタイマー切替ボタン（OFF -> 3秒 -> 10秒）
                val timerActive = timerSettingSeconds > 0
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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

                // 中央：シャッターボタン（音量ボタンでも撮影可）
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color(0xFFFFFFFF), CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isCapturing -> Color(0xFF6E6B64)
                                countdownRemaining > 0 -> Color(0xFFB51C22)
                                else -> Color(0xFFFFFFFF)
                            }
                        )
                        .clickable(
                            enabled = !isCapturing && uiState is CaptureUiState.CameraPreview
                        ) {
                            triggerShutterOrTimer()
                        },
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

                // 右側：ライト点灯モード（常時点灯 ON/OFF・文字固定で枠サイズ不変）ボタン
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
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
                        .padding(horizontal = 14.dp, vertical = 10.dp),
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

        // 【状態2: メモ入力（テキスト入力枠の高さをコンパクトに抑えたUI）】
        val currentState = uiState
        if (currentState is CaptureUiState.MemoInput) {
            MemoInputOverlay(
                capturedBitmap = currentState.capturedBitmap,
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
                                "保存しました"
                            } else {
                                "保存に失敗しました"
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
        // 0.5倍ボタン
        ZoomButtonItem(
            label = if (isUltraWideSelected) {
                formatZoomLabel(currentZoomRatio)
            } else {
                ".5"
            },
            isSelected = isUltraWideSelected,
            onClick = { onSelectZoom(ultraWideTarget) }
        )

        // 1倍ボタン
        ZoomButtonItem(
            label = if (isOneXSelected) {
                formatZoomLabel(currentZoomRatio)
            } else {
                "1"
            },
            isSelected = isOneXSelected,
            onClick = { onSelectZoom(1.0f) }
        )

        // 2倍ボタン
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
    capturedBitmap: Bitmap,
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
            Image(
                bitmap = capturedBitmap.asImageBitmap(),
                contentDescription = "撮影プレビュー",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 下部：高さを小さく抑えたコンパクトなテキスト入力欄＋ボタン（石色×墨黒×深紅）
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
                    desc = "写真もメモもGPS位置情報も、外部サーバーへ一切送信せず端末内だけに即座に保存します。"
                )
                FeatureRow(
                    title = "機種変更してもそのまま復元",
                    desc = "Googleアカウントに紐づくため、将来スマートフォンを買い替えても無料で復元できます。"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // メイン購入ボタン（紅 #D0102C）
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

            // 購入復元ボタン
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

            // ライセンスコード入力ボタン
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
