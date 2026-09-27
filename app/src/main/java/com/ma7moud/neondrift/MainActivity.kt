package com.ma7moud.neondrift

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.ma7moud.neondrift.ui.NeonDriftApp
import com.ma7moud.neondrift.ui.theme.NeonDriftTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NeonDriftTheme {
                NeonDriftApp()
            }
        }
    }
}
