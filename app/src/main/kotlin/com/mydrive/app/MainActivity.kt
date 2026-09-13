package com.mydrive.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.ui.auth.AuthLoadingScreen
import com.mydrive.app.ui.auth.AuthNavHost
import com.mydrive.app.ui.auth.SuspendedAccountScreen
import com.mydrive.app.ui.navigation.AppNavHost
import com.mydrive.app.ui.session.SessionViewModel
import com.mydrive.app.ui.theme.MyDriveTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
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
                    is AuthState.Authenticated -> AppNavHost(
                        repository = app.mediaRepository,
                        authRepository = app.authRepository,
                        galleryTabStore = app.galleryTabStore
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as MyDriveApp
        app.authRepository.onAppForeground()
        lifecycleScope.launch {
            app.mediaRepository.refresh(force = false)
        }
    }
}
