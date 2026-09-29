package com.ma7moud.reality3d.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Native graphics, so photos are really encoded and decoded (the default mode accepts any bytes as a photo). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LastPhotoTest {

    private val directory = File(ApplicationProvider.getApplicationContext<Context>().filesDir, "last_photo")
    private val store = LastPhoto(directory)

    private fun photo(color: Int) = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun theKeptPhotoComesBackTheSameSize() {
        assertNull(store.load())
        store.save(photo(Color.rgb(200, 30, 30)))
        val back = store.load()
        assertNotNull(back)
        assertEquals(64, back!!.width)
        assertEquals(48, back.height)
        val pixel = back.getPixel(30, 20)
        assertTrue(Color.red(pixel) > 180 && Color.green(pixel) < 60 && Color.blue(pixel) < 60)
        assertEquals(Bitmap.Config.ARGB_8888, back.config)
    }

    @Test
    fun aNewPhotoReplacesTheOldOneAndClearingRemovesIt() {
        store.save(photo(Color.RED))
        store.save(Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) })
        assertEquals(20, store.load()!!.width)
        store.clear()
        assertNull(store.load())
        assertFalse(File(directory, "last_photo.jpg").exists())
        assertFalse(File(directory, "last_photo.jpg.part").exists())
    }

    @Test
    fun aDamagedFileIsNotAPhoto() {
        directory.mkdirs()
        File(directory, "last_photo.jpg").writeText("not a jpeg")
        assertNull(store.load())
    }
}
