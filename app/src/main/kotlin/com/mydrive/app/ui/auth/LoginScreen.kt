package com.mydrive.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onGoToSignUp: () -> Unit,
    onForgotPassword: () -> Unit
) {
    val state by viewModel.login.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Albums", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Sign in to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xl))
        AuthTextField(
            value = state.email,
            onValueChange = viewModel::setLoginEmail,
            label = "Email",
            keyboardType = KeyboardType.Email
        )
        Spacer(Modifier.height(Spacing.sm))
        AuthPasswordField(
            value = state.password,
            onValueChange = viewModel::setLoginPassword,
            label = "Password",
            onImeAction = { if (!state.isSubmitting) viewModel.login() }
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Forgot Password",
            style = MaterialTheme.typography.labelLarge,
            color = Copper,
            modifier = Modifier
                .align(Alignment.End)
                .clickable(onClick = onForgotPassword)
                .padding(vertical = Spacing.xs)
        )
        if (state.errorMessage != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(state.errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(Spacing.lg))
        PrimaryActionButton(
            text = if (state.isSubmitting) "Signing in..." else "Login",
            onClick = viewModel::login,
            enabled = !state.isSubmitting,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "Go to Sign Up",
            style = MaterialTheme.typography.labelLarge,
            color = Copper,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onGoToSignUp)
                .padding(vertical = Spacing.xs)
        )
    }
}
