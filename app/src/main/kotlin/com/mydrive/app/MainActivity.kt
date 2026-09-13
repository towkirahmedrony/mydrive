package com.mydrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.mydrive.app.ui.navigation.AppNavHost
import com.mydrive.app.ui.theme.MyDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        val repository = (application as MyDriveApp).mediaRepository
        setContent {
            MyDriveTheme {
                AppNavHost(repository = repository)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val repository = (application as MyDriveApp).mediaRepository
        lifecycleScope.launch {
            repository.refresh(force = false)
        }
    }
}
