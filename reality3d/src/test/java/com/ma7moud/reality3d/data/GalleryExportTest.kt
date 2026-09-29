package com.ma7moud.reality3d.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

/** The phone's media store, as far as saving a photo needs: new rows, files behind them, updates and deletes. */
class FakeMediaProvider : ContentProvider() {
    val inserted = ArrayList<ContentValues>()
    val updates = ArrayList<ContentValues>()
    val deleted = ArrayList<Uri>()
    val files = HashMap<Uri, File>()

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        inserted += ContentValues(values)
        val target = Uri.parse("content://media/external/images/media/${inserted.size}")
        files[target] = File.createTempFile("gallery", ".jpg")
        return target
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(files.getValue(uri), ParcelFileDescriptor.parseMode(mode))

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        updates += ContentValues(values)
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        deleted += uri
        return 1
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class GalleryExportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val provider = Robolectric.setupContentProvider(FakeMediaProvider::class.java, "media")

    @Test
    fun aPhotoIsCopiedIntoThePicturesFolderAndShown() {
        val source = File(context.cacheDir, "camera.jpg").apply { writeBytes(ByteArray(5000) { (it * 7).toByte() }) }
        val saved = GalleryExport.save(context, Uri.fromFile(source), now = 1_790_000_000_000L)

        assertNotNull(saved)
        val row = provider.inserted.single()
        val name = row.getAsString(MediaStore.Images.Media.DISPLAY_NAME)
        assertTrue(name, name.matches(Regex("Reality3D_\\d{8}_\\d{6}\\.jpg")))
        assertEquals("image/jpeg", row.getAsString(MediaStore.Images.Media.MIME_TYPE))
        assertEquals("Pictures/Reality3D", row.getAsString(MediaStore.Images.Media.RELATIVE_PATH))
        // Hidden while the copy runs, shown once it is whole.
        assertEquals(1, row.getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertEquals(0, provider.updates.single().getAsInteger(MediaStore.Images.Media.IS_PENDING))
        assertArrayEquals(source.readBytes(), provider.files.getValue(saved!!).readBytes())
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun aPhotoThatCantBeReadLeavesNothingBehind() {
        try {
            GalleryExport.save(context, Uri.fromFile(File(context.cacheDir, "missing.jpg")))
            fail("expected the copy to fail")
        } catch (e: IOException) {
            // The half-made entry is removed.
        }
        assertEquals(1, provider.deleted.size)
        assertTrue(provider.updates.isEmpty())
    }

    @Test
    @Config(sdk = [28])
    fun beforeAndroid10ThePhoneWouldAskForAPermissionSoNothingIsSaved() {
        assertNull(GalleryExport.save(context, Uri.fromFile(File(context.cacheDir, "camera.jpg"))))
        assertTrue(provider.inserted.isEmpty())
        assertFalse(GalleryExport.available)
    }
}
