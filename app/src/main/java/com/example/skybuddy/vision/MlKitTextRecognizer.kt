package com.example.skybuddy.vision

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MlKitTextRecognizer @Inject constructor() {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(context: Context, imageUri: Uri): String? {
        return try {
            val image = try {
                InputImage.fromFilePath(context, imageUri)
            } catch (e: Exception) {
                // Fallback to manual bitmap loading if fromFilePath fails
                val bitmap = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val source = android.graphics.ImageDecoder.createSource(context.contentResolver, imageUri)
                    android.graphics.ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                        decoder.isMutableRequired = true
                        decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    }
                } else {
                    android.provider.MediaStore.Images.Media.getBitmap(context.contentResolver, imageUri)
                }
                InputImage.fromBitmap(bitmap, 0)
            }
            val result = recognizer.process(image).await()
            result.text.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e("MlKitTextRecognizer", "OCR failed", e)
            null
        }
    }

    suspend fun extractTextFromBitmap(bitmap: Bitmap): String? {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val result = recognizer.process(image).await()
            result.text.ifBlank { null }
        } catch (e: Exception) {
            android.util.Log.e("MlKitTextRecognizer", "OCR from bitmap failed", e)
            null
        }
    }
}
