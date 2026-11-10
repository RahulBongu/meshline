package com.meshline.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decode one camera frame to a QR string.
 *
 * CameraX reports the sensor orientation in [ImageProxy.imageInfo.rotationDegrees];
 * the Y plane is rotated to upright before handing it to ZXing, so both portrait
 * and landscape devices work. Only QR is enabled (no 1D formats), which keeps
 * CPU/battery use down.
 */
fun decodeFrame(image: ImageProxy, rotationDegrees: Int): String? {
    return try {
        val yBuffer = image.planes[0].buffer
        val yBytes = ByteArray(yBuffer.remaining()).also { yBuffer.get(it) }
        val (data, width, height) = rotateLuminance(yBytes, image.width, image.height, rotationDegrees)
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
        MultiFormatReader().decode(bitmap, hints).text
    } catch (_: Exception) {
        null
    } finally {
        image.close()
    }
}

/** Rotates the 8-bit luminance plane clockwise. QR is rotation-invariant, but
 *  an upright image improves detection in poor light/glare. */
internal fun rotateLuminance(data: ByteArray, w: Int, h: Int, degrees: Int): Triple<ByteArray, Int, Int> {
    return when (((degrees % 360) + 360) % 360) {
        90 -> {
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = data[y * w + x]
            Triple(out, h, w)
        }
        180 -> {
            val out = ByteArray(w * h)
            for (i in data.indices) out[w * h - 1 - i] = data[i]
            Triple(out, w, h)
        }
        270 -> {
            val out = ByteArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[(w - 1 - x) * h + y] = data[y * w + x]
            Triple(out, h, w)
        }
        else -> Triple(data, w, h)
    }
}

@Composable
fun ScanQRScreen(onScanned: (String) -> Unit, error: String? = null) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val handled = remember { AtomicBoolean(false) }
    val analyzerExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analyzerExecutor.shutdown() }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text("Scan a Meshline QR code", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Hold steady and fill the frame. Only QR codes are decoded.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        AndroidView(
            modifier = Modifier
                .padding(top = 16.dp)
                .fillMaxWidth()
                .height(360.dp),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(analyzerExecutor) { image ->
                            if (handled.get()) {
                                image.close()
                                return@setAnalyzer
                            }
                            val result = decodeFrame(image, image.imageInfo.rotationDegrees)
                            if (result != null && handled.compareAndSet(false, true)) {
                                previewView.post { onScanned(result) }
                            }
                        }
                    }
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // Camera unavailable or permission revoked; the caller shows the error.
                }
                previewView
            },
        )
    }
}
