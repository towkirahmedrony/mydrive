package com.mydrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.backup.BackupDiscoveryReason
import com.mydrive.app.data.media.MediaStoreChangeMonitor
import com.mydrive.app.ui.auth.AuthLoadingScreen
import com.mydrive.app.ui.auth.AuthNavHost
import com.mydrive.app.ui.auth.SuspendedAccountScreen
import com.mydrive.app.ui.navigation.AppNavHost
import com.mydrive.app.ui.session.SessionViewModel
import com.mydrive.app.ui.theme.MyDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var mediaStoreMonitor: MediaStoreChangeMonitor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as MyDriveApp
        setContent {
            MyDriveTheme {
                val sessionViewModel: SessionViewModel = viewModel(
                    factory = SessionViewModel.factory(app.authRepository)
                )
                val authState by sessionViewModel.state.collectAsStateWithLifecycle()
                when (val state = authState) {
                    AuthState.Loading -> AuthLoadingScreen()
                    AuthState.Unauthenticated -> AuthNavHost(authRepository = app.authRepository)
                    is AuthState.Suspended -> SuspendedAccountScreen(
                        name = state.profile.displayName,
                        email = state.profile.email,
                        onLogout = sessionViewModel::logout
                    )
                    is AuthState.Authenticated -> key(state.profile.id) {
                        AppNavHost(
                            repository = app.mediaRepository,
                            syncRepository = app.syncRepository,
                            backupRepository = app.backupRepository,
                            authRepository = app.authRepository,
                            galleryTabStore = app.galleryTabStore
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val app = application as MyDriveApp
        val monitor = mediaStoreMonitor ?: MediaStoreChangeMonitor(this) {
            app.mediaRepository.onMediaStoreChanged()
            lifecycleScope.launch {
                app.automaticBackupCoordinator.request(BackupDiscoveryReason.MEDIASTORE)
            }
        }.also { mediaStoreMonitor = it }
        monitor.start()
    }

    override fun onResume() {
        super.onResume()
        val app = application as MyDriveApp
        app.authRepository.onAppForeground()
        lifecycleScope.launch {
            app.automaticBackupCoordinator.request(BackupDiscoveryReason.FOREGROUND)
        }
    }

    override fun onStop() {
        mediaStoreMonitor?.stop()
        super.onStop()
    }
}
