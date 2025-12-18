package com.smart.bridge

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream

class AppListHandler(private val context: Context) {

    fun getInstalledApps(scope: CoroutineScope, result: MethodChannel.Result) {
        scope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val apps = pm.getInstalledApplications(0)
                val mapped = apps.mapNotNull { app ->
                    val packageName = app.packageName
                    val label = pm.getApplicationLabel(app)?.toString() ?: packageName
                    val iconBytes = runCatching { drawableToPng(pm.getApplicationIcon(app)) }.getOrNull()
                    mapOf(
                        "name" to label,
                        "package" to packageName,
                        "icon" to iconBytes
                    )
                }
                launch(Dispatchers.Main) {
                    result.success(mapped)
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    result.error("GET_APPS_ERROR", e.message, null)
                }
            }
        }
    }

    private fun drawableToPng(drawable: Drawable): ByteArray {
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 48
            val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 48
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }
}
