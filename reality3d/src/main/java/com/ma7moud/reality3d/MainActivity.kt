package com.ma7moud.reality3d

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.ma7moud.reality3d.ui.Reality3DApp
import com.ma7moud.reality3d.ui.Reality3DViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: Reality3DViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars get light icons whatever the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val useGlViewer = (application as Reality3DApplication).services.useGlViewer
        setContent { Reality3DApp(viewModel, useGlViewer) }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }
}
