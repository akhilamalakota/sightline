package com.sightline.app.detection

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-shot ML Kit barcode/QR reader for BARCODE mode. Returns the first code
 * found in a frame plus a spoken-safe format label ("QR", "Product", "Code").
 */
class BarcodeReader(context: Context) {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
        )
    }

    data class Code(val formatName: String, val value: String)

    suspend fun read(bitmap: Bitmap): Code? = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = Tasks.await(scanner.process(image))
            val barcode = result.firstOrNull() ?: return@withContext null
            val raw = barcode.rawValue ?: return@withContext null
            val name = when (barcode.format) {
                Barcode.FORMAT_QR_CODE -> "QR"
                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E -> "Product"
                else -> "Code"
            }
            Code(name, raw)
        } catch (e: Exception) {
            android.util.Log.e("BarcodeReader", "Scan failed", e)
            null
        }
    }
}