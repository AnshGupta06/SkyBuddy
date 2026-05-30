package com.example.skybuddy.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MlKitBarcodeScanner @Inject constructor() {

    private val options = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        .build()

    private val scanner = BarcodeScanning.getClient(options)

    suspend fun scanBarcode(context: Context, imageUri: Uri): String? {
        return try {
            val image = InputImage.fromFilePath(context, imageUri)
            val barcodes = scanner.process(image).await()
            if (barcodes.isNotEmpty()) {
                val raw = barcodes.first().rawValue
                android.util.Log.d("MlKitBarcodeScanner", "Found barcode: $raw")
                raw
            } else {
                android.util.Log.w("MlKitBarcodeScanner", "No barcode found in image")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MlKitBarcodeScanner", "Barcode scanning failed", e)
            null
        }
    }

    suspend fun scanBarcodeBitmap(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val barcodes = scanner.process(image).await()
            if (barcodes.isNotEmpty()) {
                val raw = barcodes.first().rawValue
                android.util.Log.d("MlKitBarcodeScanner", "Found barcode (bitmap): $raw")
                raw
            } else {
                android.util.Log.w("MlKitBarcodeScanner", "No barcode found in bitmap")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("MlKitBarcodeScanner", "Barcode bitmap scanning failed", e)
            null
        }
    }
}
