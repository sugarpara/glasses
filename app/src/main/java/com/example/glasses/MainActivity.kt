package com.example.glasses

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.glasses.ui.GlassesApp
import com.example.glasses.ui.theme.GlassesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GlassesTheme {
                GlassesApp()
            }
        }
    }
}
