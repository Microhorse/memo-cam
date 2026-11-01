package com.example.zaishucamera

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

object ImageSynthesizer {

    /**
     * フォルダ名として安全な文字列に整形する
     */
    fun sanitizeFolderName(name: String): String {
        val cleaned = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return cleaned.ifEmpty { "日常" }
    }

    /**
     * ImageProxyから得たBitmapと回転角を正規化し、正立したBitmapを返す
     */
    fun normalizeRotation(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(
            source, 0, 0, source.width, source.height, matrix, true
        )
        if (rotated != source) {
            source.recycle()
        }
        return rotated
    }

    /**
     * 元画像(3:4)の下部に白余白を追加し、全体が「9:16（インスタのストーリーぴったり）」になる
     * ポラロイド風レイアウトで撮影日時・タグ・メモを描画してMediaStoreに保存する
     *
     * @param folderName アルバム・タグ名（DCIM/MemoCamera/<folderName> に保存）
     * @param alsoSaveToCameraRoll trueの場合、Googleフォト自動バックアップ対象の DCIM/Camera にも保存する
     */
    fun synthesizeAndSave(
        context: Context,
        originalBitmap: Bitmap,
        memoText: String,
        folderName: String,
        alsoSaveToCameraRoll: Boolean,
        capturedTimeMillis: Long
    ): Uri? {
        val w = originalBitmap.width
        val h = originalBitmap.height
        val safeFolder = sanitizeFolderName(folderName)

        // インスタグラムのストーリー（9:16）にぴったり収まる全体高さを算出
        // 写真が 3:4 (= 9:12) の場合、下部白余白が 9:4 となり、合計 9:16 になる
        val storyTargetHeight = (w * 16f / 9f).roundToInt()
        val minBottomSpace = max((h * 0.22f).roundToInt(), storyTargetHeight - h)

        val padding = w * 0.045f
        val contentWidth = max(1, (w - (padding * 2f)).toInt())

        // 1. 撮影日時＋タグ用のTextPaintとStaticLayout
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        val dateString = "${dateFormat.format(Date(capturedTimeMillis))}  #$safeFolder"

        val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            textSize = w * 0.028f
        }
        val dateLayout = StaticLayout.Builder
            .obtain(dateString, 0, dateString.length, datePaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.0f)
            .setIncludePad(false)
            .build()

        // 2. メモ本文用のStaticLayout（9:16枠内に美しく収まるよう文字サイズを自動調整）
        val trimmedMemo = memoText.trimEnd()
        val dateToMemoGap = if (trimmedMemo.isNotEmpty()) (w * 0.018f).toInt() else 0
        val availableMemoHeight = minBottomSpace - (padding * 2f).toInt() - dateLayout.height - dateToMemoGap

        var memoTextSize = w * 0.042f
        val minMemoTextSize = w * 0.024f
        var memoLayout: StaticLayout? = null

        if (trimmedMemo.isNotEmpty()) {
            while (true) {
                val memoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.parseColor("#1E293B")
                    textSize = memoTextSize
                }
                val candidate = StaticLayout.Builder
                    .obtain(trimmedMemo, 0, trimmedMemo.length, memoPaint, contentWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.18f)
                    .setIncludePad(false)
                    .build()

                if (candidate.height <= availableMemoHeight || memoTextSize <= minMemoTextSize) {
                    memoLayout = candidate
                    break
                }
                memoTextSize *= 0.9f
            }
        }

        val textHeight = dateLayout.height + dateToMemoGap + (memoLayout?.height ?: 0)
        // 基本は 9:16 ぴったりの minBottomSpace（超長文時のみ拡張）
        val bottomSpace = max(minBottomSpace, textHeight + (padding * 2f).toInt())

        // 3. 新規Bitmap（9:16）の生成とCanvas描画
        val newWidth = w
        val newHeight = h + bottomSpace
        val combinedBitmap = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)

        // 元画像を(0, 0)に描画
        canvas.drawBitmap(originalBitmap, 0f, 0f, null)

        // 下部領域(0, H)〜(W, H + bottomSpace)を白で塗りつぶす
        val bgPaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, h.toFloat(), newWidth.toFloat(), newHeight.toFloat(), bgPaint)

        // 日時とメモ本文の描画
        canvas.save()
        canvas.translate(padding, h + padding)
        dateLayout.draw(canvas)

        if (memoLayout != null) {
            canvas.translate(0f, (dateLayout.height + dateToMemoGap).toFloat())
            memoLayout.draw(canvas)
        }
        canvas.restore()

        // 4. MediaStoreへのJPEG保存（品質95%）
        // ① アルバム別の専用フォルダ (DCIM/MemoCamera/<safeFolder>) に保存
        val primaryRelativePath = "${Environment.DIRECTORY_DCIM}/MemoCamera/$safeFolder"
        val savedUri = saveToMediaStore(
            context = context,
            bitmap = combinedBitmap,
            relativePath = primaryRelativePath,
            folderPrefix = safeFolder,
            capturedTimeMillis = capturedTimeMillis
        )

        // ② Googleフォト即時自動バックアップ連動がONの場合は DCIM/Camera にも保存
        if (alsoSaveToCameraRoll) {
            val cameraRollPath = "${Environment.DIRECTORY_DCIM}/Camera"
            saveToMediaStore(
                context = context,
                bitmap = combinedBitmap,
                relativePath = cameraRollPath,
                folderPrefix = safeFolder,
                capturedTimeMillis = capturedTimeMillis
            )
        }

        // メモリ解放
        combinedBitmap.recycle()
        originalBitmap.recycle()

        return savedUri
    }

    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        relativePath: String,
        folderPrefix: String,
        capturedTimeMillis: Long
    ): Uri? {
        val fileNameDate = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(capturedTimeMillis))
        val fileName = "MEMO_${folderPrefix}_$fileNameDate.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, capturedTimeMillis / 1000)
            put(MediaStore.Images.Media.DATE_TAKEN, capturedTimeMillis)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)) {
                    throw IOException("Failed to compress bitmap")
                }
            } ?: throw IOException("Failed to open OutputStream")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
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

    /**
     * 指定フォルダ（DCIM/MemoCamera/<folderName>）内に保存されている画像のURI一覧を取得する
     */
    fun getFolderImageUris(context: Context, folderName: String): List<Uri> {
        val safeFolder = sanitizeFolderName(folderName)
        val targetPath = "${Environment.DIRECTORY_DCIM}/MemoCamera/$safeFolder/"
        val uris = mutableListOf<Uri>()

        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("$targetPath%")
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id
                    )
                    uris.add(contentUri)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return uris
    }
}
