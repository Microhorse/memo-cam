package com.example.zaishucamera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private const val PREFS_NAME = "memo_camera_prefs"
private const val KEY_FOLDERS = "folder_list"
private const val KEY_SELECTED_FOLDER = "selected_folder"
private const val KEY_AUTO_GOOGLE_PHOTOS_BACKUP = "auto_google_photos_backup"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    MemoCameraApp()
                }
            }
        }
    }
}

/**
 * 単一画面のUI状態
 */
private sealed interface CaptureUiState {
    data object CameraPreview : CaptureUiState
    data class MemoInput(
        val capturedBitmap: Bitmap,
        val capturedTimeMillis: Long
    ) : CaptureUiState
}

@Composable
fun MemoCameraApp() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    if (!hasCameraPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
            ) {
                Text(
                    text = "カメラ権限を許可する",
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    } else {
        MemoCameraMainScreen()
    }
}

@Composable
private fun MemoCameraMainScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    val todayFolder = remember {
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    }
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    // アルバム（思い出タグ）一覧の読み込み
    val folders = remember {
        val savedRaw = prefs.getString(KEY_FOLDERS, null)
        val initialList = if (!savedRaw.isNullOrBlank()) {
            savedRaw.split("|").filter { it.isNotBlank() }.toMutableList()
        } else {
            mutableListOf(todayFolder, "日常", "旅行", "グルメ")
        }
        if (!initialList.contains(todayFolder)) {
            initialList.add(0, todayFolder)
        }
        mutableStateListOf(*initialList.toTypedArray())
    }

    var selectedFolder by remember {
        val savedSelected = prefs.getString(KEY_SELECTED_FOLDER, todayFolder) ?: todayFolder
        mutableStateOf(if (folders.contains(savedSelected)) savedSelected else todayFolder)
    }

    // Googleフォト即時自動バックアップ連動（DCIM/Cameraへの同時保存）
    var autoGooglePhotosBackup by remember {
        mutableStateOf(prefs.getBoolean(KEY_AUTO_GOOGLE_PHOTOS_BACKUP, true))
    }

    // 現在選択中のフォルダ内の撮影済み枚数とURIリスト
    var folderPhotoUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(selectedFolder, refreshTrigger) {
        withContext(Dispatchers.IO) {
            val uris = ImageSynthesizer.getFolderImageUris(appContext, selectedFolder)
            withContext(Dispatchers.Main) {
                folderPhotoUris = uris
            }
        }
    }

    fun saveFoldersToPrefs() {
        prefs.edit()
            .putString(KEY_FOLDERS, folders.joinToString("|"))
            .putString(KEY_SELECTED_FOLDER, selectedFolder)
            .apply()
    }

    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderNameInput by remember { mutableStateOf("") }

    var uiState by remember { mutableStateOf<CaptureUiState>(CaptureUiState.CameraPreview) }
    var memoText by remember { mutableStateOf("") }
    var isCapturing by remember { mutableStateOf(false) }
    var lastCapturedTime by remember { mutableLongStateOf(0L) }

    // ライト常時点灯（トーチ）モード状態
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }
    var isTorchOn by remember { mutableStateOf(false) }

    // カメラセンサーの最大領域（3:4 フル画角）を活かす PreviewView
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FIT_CENTER
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
            cameraInstance?.cameraControl?.enableTorch(false)
            cameraExecutor.shutdown()
        }
    }

    // カメラのライフサイクルバインド（3:4 フル画角で起動時に1回のみバインドし常時スタンバイ）
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
                if (isTorchOn && cam.cameraInfo.hasFlashUnit()) {
                    cam.cameraControl.enableTorch(true)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "カメラ初期化エラー: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(context))
    }

    // 新規アルバム（タグ）追加ダイアログ
    if (showNewFolderDialog) {
        val dialogFocusRequester = remember { FocusRequester() }
        AlertDialog(
            onDismissRequest = { showNewFolderDialog = false },
            title = { Text("アルバム（タグ）を追加", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "指定した名前で端末フォルダ（DCIM/MemoCamera/〇〇）が作成され、写真下部の白余白にも #タグ として印字されます。",
                        fontSize = 13.sp,
                        color = Color.DarkGray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newFolderNameInput,
                        onValueChange = { newFolderNameInput = it },
                        placeholder = { Text("例: 沖縄旅行、カフェ巡り、家族") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(dialogFocusRequester)
                    )
                    LaunchedEffect(Unit) {
                        dialogFocusRequester.requestFocus()
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val sanitized = ImageSynthesizer.sanitizeFolderName(newFolderNameInput)
                        if (sanitized.isNotEmpty()) {
                            if (!folders.contains(sanitized)) {
                                folders.add(0, sanitized)
                            }
                            selectedFolder = sanitized
                            saveFoldersToPrefs()
                        }
                        newFolderNameInput = ""
                        showNewFolderDialog = false
                    }
                ) {
                    Text("追加して選択")
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewFolderDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 【状態1: カメラプレビュー状態（3:4 プレビュー枠 ＋ 上下分離バー）】
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. 上部：アルバム（タグ）ワンタップ切替バー ＆ シェア連携バー
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                // 1段目：カメラロール同時保存トグル ＆ 現アルバムの一括シェアボタン
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (autoGooglePhotosBackup) Color(0xFF1B5E20) else Color(0xFF2C2C2C)
                            )
                            .clickable {
                                autoGooglePhotosBackup = !autoGooglePhotosBackup
                                prefs.edit()
                                    .putBoolean(KEY_AUTO_GOOGLE_PHOTOS_BACKUP, autoGooglePhotosBackup)
                                    .apply()
                                val msg = if (autoGooglePhotosBackup) {
                                    "カメラロール同時保存: ON (Gフォト・SNSからすぐ選べます)"
                                } else {
                                    "カメラロール同時保存: OFF (専用フォルダのみ保存)"
                                }
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (autoGooglePhotosBackup) "☁️ カメラロール同期: ON" else "☁️ カメラロール同期: OFF",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // 現在のアルバムの写真をSNSやGoogleフォトへ送るボタン
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFD97706))
                            .clickable {
                                if (folderPhotoUris.isEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "「$selectedFolder」にはまだ写真がありません",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                } else {
                                    shareFolderPhotos(context, selectedFolder, folderPhotoUris)
                                }
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "📤 「$selectedFolder」(${folderPhotoUris.size}枚)をシェア",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // 2段目：アルバム（タグ）横スクロールチップ
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ＋ 新規タグ作成ボタン
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, Color(0xFFFBBF24), RoundedCornerShape(16.dp))
                            .background(Color(0xFF1E293B))
                            .clickable {
                                newFolderNameInput = ""
                                showNewFolderDialog = true
                            }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "＋ タグ追加",
                            color = Color(0xFFFDE68A),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    folders.forEach { folder ->
                        val isSelected = folder == selectedFolder
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(
                                    if (isSelected) Color(0xFFFFB300) else Color(0xFF2C2C2C)
                                )
                                .clickable {
                                    selectedFolder = folder
                                    saveFoldersToPrefs()
                                }
                                .padding(horizontal = 12.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "#$folder",
                                color = if (isSelected) Color.Black else Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            // 2. 中央：カメラセンサーの3:4フル画角を100%表示するプレビュー枠（＋下部に白余白で9:16になるガイド）
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
            }

            // 3. 下部：シャッターボタン ＆ ライト常時点灯（トーチ）切替ボタン
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121212))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                // 左側：9:16 ストーリー比率バッジ表示
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF1E293B))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "📐 9:16 Story",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 中央：シャッターボタン
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(80.dp)
                        .clip(CircleShape)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(if (isCapturing) Color.Gray else Color.White)
                        .clickable(
                            enabled = !isCapturing && uiState is CaptureUiState.CameraPreview
                        ) {
                            isCapturing = true
                            val now = System.currentTimeMillis()
                            lastCapturedTime = now

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
                                                capturedTimeMillis = lastCapturedTime
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
                )

                // 右側：ライト点灯モード（常時点灯トーチ ON/OFF）ボタン
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            if (isTorchOn) Color(0xFFF59E0B) else Color(0xFF2C2C2C)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (isTorchOn) Color(0xFFFDE68A) else Color(0xFF757575),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .clickable {
                            val cam = cameraInstance
                            if (cam == null || !cam.cameraInfo.hasFlashUnit()) {
                                Toast.makeText(
                                    context,
                                    "この端末のカメラにはライトがありません",
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
                        text = if (isTorchOn) "💡 ライトON" else "🔦 ライトOFF",
                        color = if (isTorchOn) Color.Black else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 【状態2: メモ即時入力状態（単一画面オーバーレイ）】
        val currentState = uiState
        if (currentState is CaptureUiState.MemoInput) {
            MemoInputOverlay(
                capturedBitmap = currentState.capturedBitmap,
                selectedFolder = selectedFolder,
                memoText = memoText,
                onMemoChange = { memoText = it },
                onDiscard = {
                    val bitmapToRecycle = currentState.capturedBitmap
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview
                    bitmapToRecycle.recycle()
                },
                onSaveAndNext = {
                    // 【状態3: バックグラウンド合成・即時復帰】
                    val bitmapToSave = currentState.capturedBitmap
                    val textToSave = memoText
                    val folderToSave = selectedFolder
                    val autoBackup = autoGooglePhotosBackup
                    val timeToSave = currentState.capturedTimeMillis

                    // 1. UIを即座に【状態1: カメラプレビュー状態】へ戻し、入力欄もクリア
                    memoText = ""
                    uiState = CaptureUiState.CameraPreview

                    // 2. 裏側（Dispatchers.IO）で 9:16 画像合成とMediaStore保存を実行
                    coroutineScope.launch(Dispatchers.IO) {
                        val savedUri = ImageSynthesizer.synthesizeAndSave(
                            context = appContext,
                            originalBitmap = bitmapToSave,
                            memoText = textToSave,
                            folderName = folderToSave,
                            alsoSaveToCameraRoll = autoBackup,
                            capturedTimeMillis = timeToSave
                        )
                        withContext(Dispatchers.Main) {
                            refreshTrigger++
                            val msg = if (savedUri != null) {
                                "📸「#$folderToSave」に保存しました (9:16 ストーリー比率)"
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
 * 選択中アルバムの画像を共有シート（Instagram、Googleフォト、LINE等）へ一括送信する
 */
private fun shareFolderPhotos(
    context: Context,
    folderName: String,
    uris: List<Uri>
) {
    val arrayListUris = ArrayList(uris)
    val baseIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "image/jpeg"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListUris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    val chooser = Intent.createChooser(baseIntent, "「#$folderName」(${uris.size}枚)をシェア")
    context.startActivity(chooser)
}

@Composable
private fun MemoInputOverlay(
    capturedBitmap: Bitmap,
    selectedFolder: String,
    memoText: String,
    onMemoChange: (String) -> Unit,
    onDiscard: () -> Unit,
    onSaveAndNext: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    BackHandler(onBack = onDiscard)

    // 状態2に切り替わった瞬間、自動でフォーカスを当ててソフトキーボードを即座に立ち上げる
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
        // 上部：撮影画像のプレビュー表示
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
                .padding(6.dp),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = capturedBitmap.asImageBitmap(),
                contentDescription = "撮影プレビュー",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 下部：白い入力エリア（タグ表示＋複数行メモ＋ワンタップ保存ボタン）
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "タグ: #$selectedFolder",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFD97706)
                )
                Text(
                    text = "📐 写真(3:4)＋白余白 ＝ 9:16 ストーリーサイズ",
                    fontSize = 11.sp,
                    color = Color(0xFF64748B)
                )
            }

            OutlinedTextField(
                value = memoText,
                onValueChange = onMemoChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("思い出メモを入力（複数行可・空欄でも保存可）") },
                minLines = 2,
                maxLines = 5
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text(
                        text = "撮り直し(破棄)",
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = onSaveAndNext,
                    modifier = Modifier
                        .weight(1.6f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD97706),
                        contentColor = Color.White
                    )
                ) {
                    Text(
                        text = "保存して次へ",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
