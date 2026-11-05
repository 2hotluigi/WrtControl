package dev.angel.wrtcontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.angel.wrtcontrol.ui.AppRoot
import dev.angel.wrtcontrol.ui.WrtTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = (application as WrtApp).repository
        setContent {
            WrtTheme { AppRoot(repository) }
        }
    }
}
