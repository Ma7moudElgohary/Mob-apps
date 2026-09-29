package com.ma7moud.reality3d.diagnostics

import android.content.Context
import android.os.Build
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which app, phone and Android a report is from. */
object AppInfo {

    /** "Reality3D 0.5.83 (83) · samsung SM-S948B · Android 16 (API 36) · 2026-09-29 18:43". */
    fun header(context: Context, timestamp: Long = System.currentTimeMillis()): String {
        val version = try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        } catch (e: Exception) {
            "?"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
        return "Reality3D $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · $time"
    }
}
