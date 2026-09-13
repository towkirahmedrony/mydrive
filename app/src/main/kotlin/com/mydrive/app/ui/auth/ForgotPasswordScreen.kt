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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing

@Composable
fun ForgotPasswordScreen(
    viewModel: AuthViewModel,
    onBackToLogin: () -> Unit
) {
    val state by viewModel.forgotPassword.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Reset password", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "Enter your email and we'll send a reset link if an account exists.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.xl))
        AuthTextField(
            value = state.email,
            onValueChange = viewModel::setForgotEmail,
            label = "Email",
            keyboardType = KeyboardType.Email,
            imeAction = ImeAction.Done,
            onImeAction = { if (!state.isSubmitting) viewModel.sendPasswordReset() }
        )
        if (state.errorMessage != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(state.errorMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        if (state.infoMessage != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(state.infoMessage.orEmpty(), style = MaterialTheme.typography.bodySmall, color = Sage)
        }
        Spacer(Modifier.height(Spacing.lg))
        PrimaryActionButton(
            text = if (state.isSubmitting) "Sending..." else "Send reset link",
            onClick = { if (!state.isSubmitting) viewModel.sendPasswordReset() },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = "Back to Login",
            style = MaterialTheme.typography.labelLarge,
            color = Copper,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBackToLogin)
                .padding(vertical = Spacing.xs)
        )
    }
}
