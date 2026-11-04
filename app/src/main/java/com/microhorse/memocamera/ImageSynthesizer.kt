package com.microhorse.memocamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

object ImageSynthesizer {

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
     * 元画像(4:3)の下部に材集カメラと全く同じサイズの白余白（最小22%〜長文時は自動拡張）を追加し、
     * 撮影日時とメモを描画してMediaStore（カメラロール）に保存する
     */
    fun synthesizeAndSave(
        context: Context,
        originalBitmap: Bitmap,
        memoText: String,
        capturedTimeMillis: Long,
        location: Location? = null
    ): Uri? {
        val w = originalBitmap.width
        val h = originalBitmap.height

        // パディングと最小下部余白の計算（材集カメラと完全同一：最小22%、パディング4%）
        val padding = w * 0.04f
        val contentWidth = max(1, (w - (padding * 2f)).toInt())
        val minBottomSpace = (h * 0.22f).toInt()

        // 1. 撮影日時用のTextPaintとStaticLayout（材集カメラと同一文字サイズ 2.8%）
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

        // 2. メモ本文用のTextPaintとStaticLayout（材集カメラと同一文字サイズ 4.0%）
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

        // 日時とメモの間の間隔
        val dateToMemoGap = if (memoLayout != null) (w * 0.015f).toInt() else 0
        val textHeight = dateLayout.height + dateToMemoGap + (memoLayout?.height ?: 0)

        // 実際の下部余白高さ（最小22% または テキスト全体＋上下パディングの大きい方）
        val bottomSpace = max(minBottomSpace, textHeight + (padding * 2f).toInt())

        // 3. 新規Bitmapの生成とCanvas描画
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

        // 日時の描画 (X: padding, Y: H + padding)
        canvas.save()
        canvas.translate(padding, h + padding)
        dateLayout.draw(canvas)

        // メモ本文の描画（日時の下）
        if (memoLayout != null) {
            canvas.translate(0f, (dateLayout.height + dateToMemoGap).toFloat())
            memoLayout.draw(canvas)
        }
        canvas.restore()

        // 4. カメラロール（DCIM/Camera）へ保存（EXIF GPS・日時メタデータ書き込み）
        val savedUri = saveToMediaStore(
            context = context,
            bitmap = combinedBitmap,
            relativePath = "${Environment.DIRECTORY_DCIM}/Camera",
            capturedTimeMillis = capturedTimeMillis,
            location = location
        )

        combinedBitmap.recycle()
        originalBitmap.recycle()

        return savedUri
    }

    private fun saveToMediaStore(
        context: Context,
        bitmap: Bitmap,
        relativePath: String,
        capturedTimeMillis: Long,
        location: Location?
    ): Uri? {
        val fileNameDate = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault())
            .format(Date(capturedTimeMillis))
        val fileName = "MEMO_$fileNameDate.jpg"

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

            // JPEGファイルへEXIFメタデータ（撮影日時＋GPS位置情報）を書き込む
            try {
                resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    val exifDateFormat = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                    val exifDateStr = exifDateFormat.format(Date(capturedTimeMillis))
                    exif.setAttribute(ExifInterface.TAG_DATETIME, exifDateStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, exifDateStr)
                    exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, exifDateStr)
                    if (location != null) {
                        exif.setGpsInfo(location)
                    }
                    exif.saveAttributes()
                }
            } catch (exifError: Exception) {
                exifError.printStackTrace()
            }

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
}
