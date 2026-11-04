package com.microhorse.memocamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Crop
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.max
import kotlin.math.min

object VideoSynthesizer {

    /**
     * 録画直後のメモ入力画面表示用に、動画の先頭フレーム（3:4中央クロップ）と再生時間(ms)を取得する
     */
    fun extractPreviewFrameAndDuration(videoFile: File): Pair<Bitmap?, Long> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val rawFrame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            val croppedFrame = if (rawFrame != null) {
                cropBitmapTo3x4IfNeeded(rawFrame)
            } else {
                null
            }
            Pair(croppedFrame, durationMs)
        } catch (_: Exception) {
            Pair(null, 0L)
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun cropBitmapTo3x4IfNeeded(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val targetH = (w * 4) / 3
        if (h <= targetH + 4) return src
        val topOffset = (h - targetH) / 2
        val cropped = Bitmap.createBitmap(src, 0, topOffset, w, targetH)
        if (cropped != src) {
            src.recycle()
        }
        return cropped
    }

    /**
     * 撮影した動画の下部に、写真と全く同じ比率の白い余白（最小22%〜長文時自動拡張）＋撮影日時＋メモを合成し、
     * 1本のMP4動画としてカメラロール（DCIM/Camera）へ保存する
     */
    @OptIn(UnstableApi::class)
    suspend fun synthesizeAndSave(
        context: Context,
        rawVideoFile: File,
        memoText: String,
        capturedTimeMillis: Long,
        location: Location? = null
    ): Uri? {
        if (!rawVideoFile.exists()) return null

        var inW = 720
        var inH = 960
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(rawVideoFile.absolutePath)
            val metaW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 720
            val metaH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 960
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) {
                inW = metaH
                inH = metaW
            } else {
                inW = metaW
                inH = metaH
            }
        } catch (_: Exception) {
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }

        // プレビュー枠と同じ 3:4（横:縦 = 3:4）領域の高さ
        val croppedInH = min(inH, (inW * 4) / 3)

        // ハードウェアH.264エンコーダの上限（最大高さ1920）に収まるようベース幅を決定
        val baseW = min(inW, 960)
        val baseVideoH = (baseW * croppedInH) / max(1, inW)

        // 下部白余白とテキストレイアウトの計算（ImageSynthesizerと完全同一比率）
        val tempOverlayInfo = buildWhiteMarginOverlayBitmap(
            targetWidth = baseW,
            targetVideoHeight = baseVideoH,
            memoText = memoText,
            capturedTimeMillis = capturedTimeMillis
        )
        val outW = tempOverlayInfo.outWidth
        val outH = tempOverlayInfo.outHeight
        val videoAreaH = tempOverlayInfo.videoAreaHeight
        val overlayBitmap = tempOverlayInfo.bitmap

        val synthesizedTempFile = File(
            context.cacheDir,
            "memo_synth_${System.currentTimeMillis()}.mp4"
        )

        val transformSuccess = try {
            val videoEffects = mutableListOf<Effect>()

            // 1. 元動画が3:4より縦長（9:16等）の場合は中央3:4領域にクロップ
            if (inH > croppedInH + 4) {
                val cropRatioY = (croppedInH.toFloat() / inH.toFloat()).coerceIn(0.1f, 1.0f)
                videoEffects.add(Crop(-1f, 1f, -cropRatioY, cropRatioY))
            }

            // 2. 下部に白余白分の高さを拡張し、映像を上部の 3:4 領域へ配置する MatrixTransformation
            val padBottomTransform = object : MatrixTransformation {
                override fun configure(inputWidth: Int, inputHeight: Int): Size {
                    return Size(outW, outH)
                }

                override fun getMatrix(presentationTimeUs: Long): Matrix {
                    val scaleY = videoAreaH.toFloat() / outH.toFloat()
                    return Matrix().apply {
                        // OpenGL NDC [-1, 1] では +1 が上端、-1 が下端
                        postScale(1f, scaleY)
                        postTranslate(0f, 1f - scaleY)
                    }
                }
            }
            videoEffects.add(padBottomTransform)

            // 3. 下部白余白＋日時・メモを描画したオーバーレイBitmapを合成
            val bitmapOverlay = BitmapOverlay.createStaticBitmapOverlay(overlayBitmap)
            val overlayEffect = OverlayEffect(ImmutableList.of(bitmapOverlay))
            videoEffects.add(overlayEffect)

            val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(rawVideoFile)))
                .setEffects(Effects(emptyList(), videoEffects))
                .build()

            runTransformerOnMain(context, editedMediaItem, synthesizedTempFile)
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            overlayBitmap.recycle()
        }

        val fileToSave = if (transformSuccess && synthesizedTempFile.exists() && synthesizedTempFile.length() > 0L) {
            synthesizedTempFile
        } else {
            rawVideoFile
        }

        val savedUri = saveVideoToMediaStore(
            context = context,
            videoFile = fileToSave,
            capturedTimeMillis = capturedTimeMillis,
            location = location
        )

        try {
            if (synthesizedTempFile.exists()) synthesizedTempFile.delete()
        } catch (_: Exception) {
        }
        try {
            if (rawVideoFile.exists()) rawVideoFile.delete()
        } catch (_: Exception) {
        }

        return savedUri
    }

    private data class OverlayBitmapResult(
        val bitmap: Bitmap,
        val outWidth: Int,
        val outHeight: Int,
        val videoAreaHeight: Int
    )

    private fun buildWhiteMarginOverlayBitmap(
        targetWidth: Int,
        targetVideoHeight: Int,
        memoText: String,
        capturedTimeMillis: Long
    ): OverlayBitmapResult {
        // H.264 エンコーダ互換のため偶数ピクセルに揃える
        var outW = max(320, (targetWidth / 2) * 2)
        var videoH = max(320, (targetVideoHeight / 2) * 2)

        fun computeLayout(w: Int, h: Int): Triple<StaticLayout, StaticLayout?, Int> {
            val padding = w * 0.04f
            val contentWidth = max(1, (w - (padding * 2f)).toInt())
            val minBottomSpace = (h * 0.22f).toInt()

            val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
            val dateString = dateFormat.format(Date(capturedTimeMillis))

            val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.DKGRAY
                textSize = w * 0.028f
            }
            val dateLayout = StaticLayout.Builder
                .obtain(dateString, 0, dateString.length, datePaint, contentWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false)
                .build()

            val trimmedMemo = memoText.trimEnd()
            val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#1A1A1A")
                textSize = w * 0.04f
            }
            val memoLayout: StaticLayout? = if (trimmedMemo.isNotEmpty()) {
                StaticLayout.Builder
                    .obtain(trimmedMemo, 0, trimmedMemo.length, memoPaint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.15f)
                    .setIncludePad(false)
                    .build()
            } else {
                null
            }

            val dateToMemoGap = if (memoLayout != null) (w * 0.015f).toInt() else 0
            val textHeight = dateLayout.height + dateToMemoGap + (memoLayout?.height ?: 0)
            val bottomSpace = max(minBottomSpace, textHeight + (padding * 2f).toInt())
            return Triple(dateLayout, memoLayout, bottomSpace)
        }

        var (dateLayout, memoLayout, bottomSpace) = computeLayout(outW, videoH)
        var outH = ((videoH + bottomSpace + 1) / 2) * 2

        // 万が一合計高さが1920を超える場合は1920以下に縮小してハードウェアエンコーダ制限を回避
        if (outH > 1920) {
            val scale = 1920f / outH.toFloat()
            outW = max(320, ((outW * scale).toInt() / 2) * 2)
            videoH = max(320, ((videoH * scale).toInt() / 2) * 2)
            val recomputed = computeLayout(outW, videoH)
            dateLayout = recomputed.first
            memoLayout = recomputed.second
            bottomSpace = recomputed.third
            outH = min(1920, ((videoH + bottomSpace + 1) / 2) * 2)
        }

        val padding = outW * 0.04f
        val dateToMemoGap = if (memoLayout != null) (outW * 0.015f).toInt() else 0

        val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // 上部 (0, 0)〜(outW, videoH) は透明のままにし、下部 (0, videoH)〜(outW, outH) を白で塗りつぶす
        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, videoH.toFloat(), outW.toFloat(), outH.toFloat(), bgPaint)

        // 日時とメモ本文を描画
        canvas.save()
        canvas.translate(padding, videoH + padding)
        dateLayout.draw(canvas)
        if (memoLayout != null) {
            canvas.translate(0f, (dateLayout.height + dateToMemoGap).toFloat())
            memoLayout.draw(canvas)
        }
        canvas.restore()

        return OverlayBitmapResult(
            bitmap = bitmap,
            outWidth = outW,
            outHeight = outH,
            videoAreaHeight = videoH
        )
    }

    @OptIn(UnstableApi::class)
    private suspend fun runTransformerOnMain(
        context: Context,
        editedMediaItem: EditedMediaItem,
        outputFile: File
    ): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val listener = object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    if (cont.isActive) {
                        cont.resume(true)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    exportException.printStackTrace()
                    if (cont.isActive) {
                        cont.resume(false)
                    }
                }
            }

            val transformer = Transformer.Builder(context)
                .addListener(listener)
                .build()

            cont.invokeOnCancellation {
                try {
                    transformer.cancel()
                } catch (_: Exception) {
                }
            }

            try {
                transformer.start(editedMediaItem, outputFile.absolutePath)
            } catch (e: Exception) {
                e.printStackTrace()
                if (cont.isActive) {
                    cont.resume(false)
                }
            }
        }
    }

    private fun saveVideoToMediaStore(
        context: Context,
        videoFile: File,
        capturedTimeMillis: Long,
        location: Location?
    ): Uri? {
        val fileNameDate = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(capturedTimeMillis))
        val fileName = "MEMO_$fileNameDate.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, capturedTimeMillis / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, capturedTimeMillis)
            if (location != null) {
                put(MediaStore.Video.Media.LATITUDE, location.latitude)
                put(MediaStore.Video.Media.LONGITUDE, location.longitude)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outStream ->
                FileInputStream(videoFile).use { inStream ->
                    inStream.copyTo(outStream)
                }
            } ?: throw IOException("Failed to open video OutputStream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(uri, updateValues, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            resolver.delete(uri, null, null)
            null
        }
    }
}
