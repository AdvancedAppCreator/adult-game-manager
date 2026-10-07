package com.example.f95updater

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

object ScreenshotDiagnostics {
    fun dir(context: Context): File = File(context.filesDir, "screenshots").apply { mkdirs() }

    fun files(context: Context): List<File> =
        dir(context).listFiles { f -> f.isFile && f.extension.equals("png", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()

    suspend fun clear(context: Context) = withContext(Dispatchers.IO) {
        files(context).forEach { runCatching { it.delete() } }
    }

    suspend fun capture(context: Context, view: View, name: String): File = withContext(Dispatchers.Main) {
        val safeName = name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-")
        val bitmap = captureBitmap(view)
        val file = File(dir(context), "$safeName.png")
        withContext(Dispatchers.IO) {
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
            runCatching {
                AppConfigStore.writeBytesToDocuments(
                    context = context,
                    fileName = file.name,
                    mimeType = "image/png",
                    bytes = file.readBytes(),
                    subFolder = "AdultGameManager/screenshots",
                )
            }
            file
        }
    }

    private suspend fun captureBitmap(view: View): Bitmap {
        val width = view.width.coerceAtLeast(1)
        val height = view.height.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val activity = view.context.findActivity()
        if (activity != null) {
            val loc = IntArray(2)
            view.getLocationInWindow(loc)
            val src = Rect(loc[0], loc[1], loc[0] + width, loc[1] + height)
            val copied = suspendCancellableCoroutine { cont ->
                PixelCopy.request(activity.window, src, bitmap, { result ->
                    cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            }
            if (copied) return bitmap
        }

        // Older devices / PixelCopy failures. This can still fail on hardware-backed
        // images, but keeps a best-effort fallback for non-image screens.
        view.draw(Canvas(bitmap))
        return bitmap
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
