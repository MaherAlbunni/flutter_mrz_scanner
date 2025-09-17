package io.github.olexale.flutter_mrz_scanner

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.util.Size
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.googlecode.tesseract.android.TessBaseAPI
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXCamera constructor(
    private val context: Context,
    private val messenger: MethodChannel,
    private val lifecycleOwner: LifecycleOwner
) {
    private val DEFAULT_PAGE_SEG_MODE = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
    private var cachedTessData: File? = null
    private var mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    val previewView = PreviewView(context)

    private var cameraProvider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var imageAnalyzer: ImageAnalysis? = null
    private var imageCapture: ImageCapture? = null
    private var camera: androidx.camera.core.Camera? = null
    private var isFlashOn = false
    private var lensFacing = CameraSelector.LENS_FACING_BACK

    init {
        if (cachedTessData == null) {
            try {
                cachedTessData = getFileFromAssets(context, fileName = "ocrb.traineddata")
            } catch (e: Exception) {
                messenger.invokeMethod("onError", "Failed to initialize OCR: ${e.message}")
            }
        }
    }

    fun startCamera(isFrontCam: Boolean = false) {
        lensFacing = if (isFrontCam) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                messenger.invokeMethod("onError", "Camera initialization failed: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    fun flashlightOn() {
        isFlashOn = true
        camera?.cameraControl?.enableTorch(true)
    }

    fun flashlightOff() {
        isFlashOn = false
        camera?.cameraControl?.enableTorch(false)
    }

    fun takePhoto(result: MethodChannel.Result, crop: Boolean) {
        val imageCapture = imageCapture ?: run {
            result.error("CAMERA_ERROR", "Image capture not initialized", null)
            return
        }

        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(createTempFile()).build()

        imageCapture.takePicture(
            outputFileOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exception: ImageCaptureException) {
                    result.error("PHOTO_ERROR", "Photo capture failed: ${exception.message}", null)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(output.savedUri?.path)
                        val finalBitmap = if (crop) {
                            calculateCutoutRect(bitmap, false)
                        } else {
                            bitmap
                        }

                        val stream = ByteArrayOutputStream()
                        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
                        result.success(stream.toByteArray())
                    } catch (e: Exception) {
                        result.error("PHOTO_PROCESSING_ERROR", "Failed to process photo: ${e.message}", null)
                    }
                }
            }
        )
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: throw IllegalStateException("Camera initialization failed.")

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()

        imageAnalyzer = ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, ImageAnalyzer())
            }

        try {
            cameraProvider.unbindAll()

            camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalyzer
            )

            // Apply flash state if needed
            if (isFlashOn) {
                camera?.cameraControl?.enableTorch(true)
            }

        } catch (exc: Exception) {
            messenger.invokeMethod("onError", "Camera binding failed: ${exc.message}")
        }
    }

    private inner class ImageAnalyzer : ImageAnalysis.Analyzer {
        @SuppressLint("UnsafeOptInUsageError")
        override fun analyze(imageProxy: ImageProxy) {
            try {
                val bitmap = imageProxy.toBitmap()
                val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
                val cropped = calculateCutoutRect(rotatedBitmap, true)
                val mrz = scanMRZ(cropped)
                val fixedMrz = extractMRZ(mrz)

                mainExecutor.execute {
                    messenger.invokeMethod("onParsed", fixedMrz)
                }
            } catch (e: Exception) {
                mainExecutor.execute {
                    messenger.invokeMethod("onError", "Image analysis failed: ${e.message}")
                }
            } finally {
                imageProxy.close()
            }
        }
    }

    private fun ImageProxy.toBitmap(): Bitmap {
        return when (format) {
            ImageFormat.YUV_420_888 -> {
                val yBuffer = planes[0].buffer
                val uBuffer = planes[1].buffer
                val vBuffer = planes[2].buffer

                val ySize = yBuffer.remaining()
                val uSize = uBuffer.remaining()
                val vSize = vBuffer.remaining()

                val nv21 = ByteArray(ySize + uSize + vSize)

                yBuffer.get(nv21, 0, ySize)
                vBuffer.get(nv21, ySize, vSize)
                uBuffer.get(nv21, ySize + vSize, uSize)

                val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
                val out = ByteArrayOutputStream()
                yuvImage.compressToJpeg(Rect(0, 0, width, height), 100, out)
                val imageBytes = out.toByteArray()
                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            }
            else -> {
                // Fallback for other formats
                val buffer = planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source

        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun scanMRZ(bitmap: Bitmap): String {
        val baseApi = TessBaseAPI()
        return try {
            baseApi.init(context.cacheDir.absolutePath, "ocrb")
            baseApi.pageSegMode = DEFAULT_PAGE_SEG_MODE
            baseApi.setImage(bitmap)
            val mrz = baseApi.utF8Text
            baseApi.stop()
            mrz
        } catch (e: Exception) {
            baseApi.stop()
            ""
        }
    }

    private fun extractMRZ(input: String): String {
        val lines = input.split("\n")
        val mrzLength = lines.lastOrNull()?.length ?: 0
        if (mrzLength == 0) return ""

        val mrzLines = lines.takeLastWhile { it.length == mrzLength }
        return mrzLines.joinToString("\n")
    }

    @Throws(IOException::class)
    private fun getFileFromAssets(context: Context, fileName: String): File {
        val directory = File(context.cacheDir, "tessdata/")
        directory.mkdir()
        return File(directory, fileName)
            .also { file ->
                if (!file.exists()) {
                    file.outputStream().use { cache ->
                        context.assets.open(fileName).use { stream ->
                            stream.copyTo(cache)
                        }
                    }
                }
            }
    }

    private fun calculateCutoutRect(bitmap: Bitmap, cropToMRZ: Boolean): Bitmap {
        val documentFrameRatio = 1.42 // Passport's size (ISO/IEC 7810 ID-3) is 125mm × 88mm
        val width: Double
        val height: Double

        if (bitmap.height > bitmap.width) {
            width = bitmap.width * 0.9 // Fill 90% of the width
            height = width / documentFrameRatio
        } else {
            height = bitmap.height * 0.75 // Fill 75% of the height
            width = height * documentFrameRatio
        }

        val mrzZoneOffset = if (cropToMRZ) height * 0.6 else 0.toDouble()
        val topOffset = (bitmap.height - height) / 2 + mrzZoneOffset
        val leftOffset = (bitmap.width - width) / 2

        val finalWidth = width.toInt().coerceAtMost(bitmap.width - leftOffset.toInt())
        val finalHeight = (height - mrzZoneOffset).toInt().coerceAtMost(bitmap.height - topOffset.toInt())

        return try {
            Bitmap.createBitmap(
                bitmap,
                leftOffset.toInt().coerceAtLeast(0),
                topOffset.toInt().coerceAtLeast(0),
                finalWidth,
                finalHeight
            )
        } catch (e: Exception) {
            bitmap // Return original if cropping fails
        }
    }

    private fun createTempFile(): File {
        return File.createTempFile("mrz_photo", ".jpg", context.cacheDir)
    }

    fun cleanup() {
        cameraExecutor.shutdown()
        stopCamera()
    }
}