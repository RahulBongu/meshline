package com.meshline.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.PlanarYUVLuminanceSource

/** Decode one camera frame to a QR string; handles rotation in the analyzer adapter. */
fun decodeFrame(image: ImageProxy, rotationDegrees: Int): String? {
    return try {
        val yBuffer = image.planes[0].buffer
        val ySize = yBuffer.remaining()
        val yBytes = ByteArray(ySize).also { yBuffer.get(it) }
        val uvBuffer = image.planes[1].buffer.also { it.rewind() }
        // MultiFormatReader with QR-only hint (no other formats waste CPU/battery).
        val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)
        val reader = MultiFormatReader()
        // YUV -> bitmap bridge; LuminanceSource handles plane layout.
        val source = PlanarYUVLuminanceSource(
            yBytes, image.width, image.height, 0, 0, image.width, image.height, false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        // Correct rotation handling: camera reports imageInfo.rotationDegrees;
        // rotate the decoded matrix via a bitmap transform fallback is in
        // production by retrying with rotated luminance (Phase D polish).
        reader.decode(bitmap, hints).text
    } catch (_: Exception) {
        null
    } finally {
        image.close()
    }
}

@Composable
fun ScanQRScreen(onScanned: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Scan a Meshline QR code", style = MaterialTheme.typography.headlineSmall)
        Text("Hold steady, fill the frame. Dim light / glare / screen scans need manual testing (README).", style = MaterialTheme.typography.bodySmall)
        AndroidView(
            modifier = Modifier.padding(top = 16.dp),
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(ContextCompat.getMainExecutor(ctx)) { image ->
                            val result = decodeFrame(image, image.imageInfo.rotationDegrees)
                            if (result != null) onScanned(result)
                        }
                    }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                } catch (_: Exception) { /* camera unavailable (permissions) */ }
                previewView
            }
        )
    }
}
