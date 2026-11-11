package com.biometricpass.app.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Offline QR Code generator and decoder built on ZXing (zero network dependencies).
 */
object QrCodeHelper {

    const val MAX_MIGRATION_QR_CREDENTIALS = 30
    const val MAX_SAFE_QR_PAYLOAD_BYTES = 1800

    /**
     * Checks whether the migration payload can be comfortably displayed and scanned as a single QR Code.
     */
    fun canFitInSingleQr(credentialCount: Int, payload: String): Boolean {
        if (credentialCount > MAX_MIGRATION_QR_CREDENTIALS) return false
        if (payload.toByteArray(Charsets.UTF_8).size > MAX_SAFE_QR_PAYLOAD_BYTES) return false
        return try {
            generateQrBitmap(payload, 512)
            true
        } catch (_: Exception) {
            false
        }
    }

    private val qrReader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    /**
     * Generates a square QR Code [Bitmap] from [content].
     */
    fun generateQrBitmap(content: String, sizePx: Int = 768): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /**
     * Decodes a QR Code from a [Bitmap] (e.g., picked from Gallery).
     */
    @Synchronized
    fun decodeFromBitmap(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            qrReader.decodeWithState(binaryBitmap).text
        } catch (_: Exception) {
            null
        } finally {
            qrReader.reset()
        }
    }

    /**
     * Decodes a QR Code from a CameraX [ImageProxy] frame (YUV_420_888).
     */
    @Synchronized
    fun decodeFromImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes.firstOrNull() ?: return null
            val buffer = plane.buffer
            val data = ByteArray(buffer.remaining())
            buffer.get(data)

            val width = imageProxy.width
            val height = imageProxy.height
            val rowStride = plane.rowStride

            val yData = if (rowStride == width) {
                data
            } else {
                val tight = ByteArray(width * height)
                for (row in 0 until height) {
                    val srcOffset = row * rowStride
                    if (srcOffset + width <= data.size) {
                        System.arraycopy(data, srcOffset, tight, row * width, width)
                    }
                }
                tight
            }

            val source = PlanarYUVLuminanceSource(
                yData,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            qrReader.decodeWithState(binaryBitmap).text
        } catch (_: Exception) {
            null
        } finally {
            qrReader.reset()
        }
    }
}
