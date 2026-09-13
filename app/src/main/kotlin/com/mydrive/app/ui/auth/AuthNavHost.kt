package com.mydrive.app.ui.auth

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mydrive.app.data.repository.AuthRepository

private const val LOGIN = "login"
private const val SIGN_UP = "signup"
private const val FORGOT_PASSWORD = "forgot-password"
private const val VERIFY_EMAIL = "verify-email/{email}"

private fun verifyRoute(email: String): String {
    return "verify-email/${Uri.encode(email)}"
}

@Composable
fun AuthNavHost(authRepository: AuthRepository) {
    val navController = rememberNavController()
    val viewModel: AuthViewModel = viewModel(factory = AuthViewModel.factory(authRepository))
    val signUp by viewModel.signUp.collectAsStateWithLifecycle()
    val fade = tween<Float>(durationMillis = 160)

    LaunchedEffect(signUp.pendingVerificationEmail) {
        val email = signUp.pendingVerificationEmail ?: return@LaunchedEffect
        viewModel.consumeVerificationNavigation()
        navController.navigate(verifyRoute(email)) {
            launchSingleTop = true
        }
    }

    NavHost(
        navController = navController,
        startDestination = LOGIN,
        modifier = Modifier.fillMaxSize(),
        enterTransition = { fadeIn(fade) },
        exitTransition = { fadeOut(fade) },
        popEnterTransition = { fadeIn(fade) },
        popExitTransition = { fadeOut(fade) }
    ) {
        composable(LOGIN) {
            LoginScreen(
                viewModel = viewModel,
                onGoToSignUp = { navController.navigate(SIGN_UP) },
                onForgotPassword = { navController.navigate(FORGOT_PASSWORD) }
            )
        }
        composable(SIGN_UP) {
            SignUpScreen(
                viewModel = viewModel,
                onGoToLogin = { navController.popBackStack() }
            )
        }
        composable(FORGOT_PASSWORD) {
            ForgotPasswordScreen(
                viewModel = viewModel,
                onBackToLogin = { navController.popBackStack() }
            )
        }
        composable(
            route = VERIFY_EMAIL,
            arguments = listOf(navArgument("email") { type = NavType.StringType })
        ) { entry ->
            val email = Uri.decode(entry.arguments?.getString("email").orEmpty())
            EmailVerificationScreen(
                viewModel = viewModel,
                email = email,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
