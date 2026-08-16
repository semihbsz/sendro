package com.sendro.android.ui.screens

import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A CameraX preview with a ZXing QR analyser.
 *
 * ZXing core, not ML Kit: ML Kit's barcode scanner needs Google Play Services
 * (or a 3 MB bundled model), and "no Play Services" is a hard rule for Sendro.
 * ZXing core is a pure-Java jar that decodes the Y plane of the analyser frame
 * directly — no bitmap allocation per frame.
 *
 * [onScanned] fires at most once; the caller navigates away.
 */
@Composable
fun QrScannerView(
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val consumed = remember { AtomicBoolean(false) }
    val reader = remember {
        MultiFormatReader().apply {
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java)
            hints[DecodeHintType.POSSIBLE_FORMATS] =
                listOf(com.google.zxing.BarcodeFormat.QR_CODE)
            hints[DecodeHintType.TRY_HARDER] = true
            setHints(hints)
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { viewContext ->
                val previewView = PreviewView(viewContext).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
                val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                providerFuture.addListener({
                    val provider = runCatching { providerFuture.get() }.getOrNull()
                        ?: return@addListener

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(executor) { proxy ->
                        try {
                            if (!consumed.get()) {
                                decode(reader, proxy)?.let { text ->
                                    if (consumed.compareAndSet(false, true)) {
                                        ContextCompat.getMainExecutor(viewContext)
                                            .execute { onScanned(text) }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.d(TAG, "frame decode failed", e)
                        } finally {
                            proxy.close()
                        }
                    }

                    runCatching {
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis,
                        )
                    }.onFailure { Log.w(TAG, "could not bind camera", it) }
                }, ContextCompat.getMainExecutor(viewContext))
                previewView
            },
        )
    }
}

/**
 * Decode one analyser frame.
 *
 * The Y plane of a YUV_420_888 image is already an 8-bit luminance buffer,
 * which is exactly what ZXing wants — copying it row by row (respecting the
 * row stride) is cheaper and more reliable than converting to a Bitmap.
 */
private fun decode(reader: MultiFormatReader, proxy: ImageProxy): String? {
    val plane = proxy.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = proxy.width
    val height = proxy.height

    val data = ByteArray(width * height)
    val row = ByteArray(rowStride)
    buffer.rewind()
    var offset = 0
    for (y in 0 until height) {
        val remaining = buffer.remaining()
        if (remaining <= 0) break
        val take = minOf(rowStride, remaining)
        buffer.get(row, 0, take)
        val copy = minOf(width, take)
        System.arraycopy(row, 0, data, offset, copy)
        offset += width
    }

    val source = PlanarYUVLuminanceSource(
        data, width, height, 0, 0, width, height, false,
    )
    return try {
        // NOT reader.reset() afterwards: it drops the configured hints and the
        // next frame would fall back to scanning every barcode format.
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: NotFoundException) {
        null
    }
}

private const val TAG = "SendroQr"
