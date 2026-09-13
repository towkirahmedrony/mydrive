package com.mydrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.mydrive.app.ui.navigation.AppNavHost
import com.mydrive.app.ui.theme.MyDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MyDriveApp
        setContent {
            MyDriveTheme {
                AppNavHost(
                    repository = app.mediaRepository,
                    galleryTabStore = app.galleryTabStore
                )
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
