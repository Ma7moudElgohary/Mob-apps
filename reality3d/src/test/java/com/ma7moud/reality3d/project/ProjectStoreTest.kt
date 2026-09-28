package com.ma7moud.reality3d.project

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ma7moud.reality3d.mesh.Mesh3D
import com.ma7moud.reality3d.quality.QualityReport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class ProjectStoreTest {

    private val root = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "projects")
    private val store = ProjectStore(root)

    private val mesh = Mesh3D(
        positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0.25f),
        normals = FloatArray(12),
        uvs = FloatArray(8) { 0.5f },
        indices = intArrayOf(0, 1, 2, 0, 2, 3, 0, 3, 1, 1, 3, 2),
        solid = true,
        subjectIsolated = true,
    )

    private fun bitmap(color: Int) = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun savedModelsComeBackWhole() {
        assertEquals(emptyList<ProjectInfo>(), store.refresh())
        val saved = store.save(
            ProjectDraft(
                name = "Mug", kind = ProjectKind.PHOTO, mesh = mesh, thumbnail = bitmap(Color.RED),
                metersPerUnit = 0.2f, sizeKnown = false, quality = QualityReport(81, listOf("A little dark")),
                texture = bitmap(Color.BLUE), textureRegion = floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f), photo = bitmap(Color.GREEN),
            ),
        )
        assertEquals(listOf(saved.id), store.projects.value!!.map { it.id })
        assertTrue(saved.thumbnail.exists())
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0.05f), saved.size, 1e-6f)
        assertEquals(QualityReport(81, listOf("A little dark")), saved.quality)

        val project = store.load(saved.id)
        assertEquals("Mug", project.info.name)
        assertArrayEquals(mesh.positions, project.mesh.positions, 0f)
        assertArrayEquals(floatArrayOf(0.1f, 0.2f, 0.8f, 0.9f), project.textureRegion, 1e-6f)
        assertNotNull(project.texture)
        assertEquals(false, project.texture!!.isPremultiplied)
        assertNotNull(project.photo)
        assertEquals(0.2f, project.metersPerUnit, 1e-6f)
    }

    @Test
    fun renameScaleAndDelete() {
        val first = store.save(ProjectDraft("Chair", ProjectKind.SCAN, mesh, bitmap(Color.GRAY), 1f, sizeKnown = true))
        Thread.sleep(5)
        val second = store.save(ProjectDraft("Lamp", ProjectKind.PHOTO, mesh, bitmap(Color.GRAY), 0.2f, sizeKnown = false))
        assertEquals(listOf("Lamp", "Chair"), store.projects.value!!.map { it.name })
        assertNull(store.load(first.id).texture)

        store.rename(second.id, "  Desk lamp  ")
        store.setScale(second.id, 0.5f, mesh)
        val lamp = store.load(second.id)
        assertEquals("Desk lamp", lamp.info.name)
        assertEquals(0.5f, lamp.metersPerUnit, 1e-6f)
        assertTrue(lamp.info.sizeKnown)
        assertArrayEquals(floatArrayOf(0.5f, 0.25f, 0.125f), lamp.info.size, 1e-6f)

        store.delete(first.id)
        assertEquals(listOf("Desk lamp"), store.projects.value!!.map { it.name })
        assertTrue(root.listFiles()!!.none { it.name.startsWith(".") })
    }
}
