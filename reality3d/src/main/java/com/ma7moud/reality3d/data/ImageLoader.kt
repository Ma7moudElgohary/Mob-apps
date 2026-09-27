package com.ma7moud.reality3d.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build

object ImageLoader {
    fun load(context: Context, uri: Uri, maxSize: Int = 1600): Bitmap {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, _, _ -> decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        } else {
            @Suppress("DEPRECATION")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open image" }
                BitmapFactory.decodeStream(input)
            }
        }
        val longest = maxOf(raw.width, raw.height)
        if (longest <= maxSize) return raw
        val scale = maxSize.toFloat() / longest
        return Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt().coerceAtLeast(1), (raw.height * scale).toInt().coerceAtLeast(1), true)
    }
}
