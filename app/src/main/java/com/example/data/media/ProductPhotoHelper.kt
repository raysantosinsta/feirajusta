package com.example.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

object ProductPhotoHelper {

    private const val CAMERA_CACHE_DIR = "camera_captures"
    private const val PHOTOS_DIR = "product_photos"
    private const val MAX_DIMENSION_PX = 1280
    private const val JPEG_QUALITY = 82

    fun createCameraCaptureTarget(context: Context): Pair<Uri, File> {
        val cacheFolder = File(context.cacheDir, CAMERA_CACHE_DIR).apply { mkdirs() }
        val tempFile = File(cacheFolder, "capture_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
        return uri to tempFile
    }

    suspend fun compressAndSaveToAppStorage(
        context: Context,
        sourceUri: Uri,
        tempCameraFile: File? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            val boundsStream = resolver.openInputStream(sourceUri)
                ?: error("Não foi possível abrir a imagem selecionada.")
            boundsStream.use { input ->
                BitmapFactory.decodeStream(input, null, boundsOptions)
            }

            val srcWidth = boundsOptions.outWidth
            val srcHeight = boundsOptions.outHeight
            if (srcWidth <= 0 || srcHeight <= 0) {
                error("Arquivo de imagem inválido ou corrompido.")
            }

            val sampleSize = calculateInSampleSize(srcWidth, srcHeight, MAX_DIMENSION_PX)
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }

            val decodedBitmap = resolver.openInputStream(sourceUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: error("Não foi possível decodificar a foto do produto.")

            val rotationDegrees = resolver.openInputStream(sourceUri)?.use { exifStream ->
                runCatching {
                    when (ExifInterface(exifStream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                }.getOrDefault(0f)
            } ?: 0f

            val rotatedBitmap = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                val rotated = Bitmap.createBitmap(
                    decodedBitmap,
                    0,
                    0,
                    decodedBitmap.width,
                    decodedBitmap.height,
                    matrix,
                    true
                )
                if (rotated !== decodedBitmap) decodedBitmap.recycle()
                rotated
            } else {
                decodedBitmap
            }

            val finalBitmap = scaleDownIfNeeded(rotatedBitmap, MAX_DIMENSION_PX)
            val targetDir = File(context.filesDir, PHOTOS_DIR).apply { mkdirs() }
            val destFile = File(targetDir, "prod_${UUID.randomUUID()}.jpg")

            FileOutputStream(destFile).use { out ->
                val ok = finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (!ok) error("Falha ao comprimir a foto do produto.")
            }
            finalBitmap.recycle()

            if (tempCameraFile != null && tempCameraFile.exists()) {
                runCatching { tempCameraFile.delete() }
            }

            destFile.absolutePath
        }
    }

    fun deleteLocalPhoto(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var inSampleSize = 1
        val largest = max(width, height)
        if (largest > maxDim) {
            while ((largest / inSampleSize) > maxDim * 2) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val largest = max(bitmap.width, bitmap.height)
        if (largest <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / largest.toFloat()
        val targetW = (bitmap.width * ratio).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * ratio).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        if (scaled !== bitmap) {
            bitmap.recycle()
        }
        return scaled
    }
}
