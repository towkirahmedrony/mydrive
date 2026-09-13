package com.mydrive.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Radius
import com.mydrive.app.ui.theme.Sage
import com.mydrive.app.ui.theme.Spacing
import androidx.compose.foundation.shape.RoundedCornerShape

@Composable
fun EmailVerificationScreen(
    viewModel: AuthViewModel,
    email: String,
    onBack: () -> Unit
) {
    val state by viewModel.verification.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme

    LaunchedEffect(email) {
        viewModel.ensureVerificationEmail(email)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Verify your email", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            "We sent a 6-digit verification code to your email. Enter it below to finish creating your Albums account.",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = state.email.ifBlank { email },
            style = MaterialTheme.typography.titleSmall,
            color = colors.onBackground
        )
        Spacer(Modifier.height(Spacing.xl))
        OtpCodeField(
            value = state.code,
            onValueChange = viewModel::setVerificationCode,
            enabled = !state.isVerifying,
            onFilled = {
                if (state.canVerify) viewModel.verifyEmailCode()
            }
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
            text = if (state.isVerifying) "Verifying..." else "Verify",
            onClick = viewModel::verifyEmailCode,
            enabled = state.canVerify,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(Spacing.md))
        val resendLabel = when {
            state.isSendingCode -> "Sending a new code..."
            state.cooldownSeconds > 0 -> "Resend code in ${state.cooldownSeconds}s"
            else -> "Resend code"
        }
        Text(
            text = resendLabel,
            style = MaterialTheme.typography.labelLarge,
            color = if (state.canResend) Copper else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (state.canResend) {
                        Modifier.clickable(onClick = viewModel::resendVerificationCode)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = Spacing.xs)
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "Back to Sign Up",
            style = MaterialTheme.typography.labelLarge,
            color = Copper,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(vertical = Spacing.xs)
        )
    }
}

@Composable
private fun OtpCodeField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onFilled: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val focusRequester = FocusRequester()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    BasicTextField(
        value = value,
        onValueChange = { incoming ->
            val digits = incoming.filter { it.isDigit() }.take(6)
            onValueChange(digits)
        },
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
        enabled = enabled,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = { if (value.length == 6) onFilled() }
        ),
        cursorBrush = SolidColor(Copper),
        textStyle = MaterialTheme.typography.titleLarge.copy(color = colors.onBackground),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(6) { index ->
                    val char = value.getOrNull(index)?.toString().orEmpty()
                    val selected = enabled && (value.length == index || (index == 5 && value.length == 6))
                    Box(
                        modifier = Modifier
                            .size(width = 44.dp, height = 52.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(colors.surfaceVariant)
                            .border(
                                width = 1.dp,
                                color = if (selected) Copper else colors.outline,
                                shape = RoundedCornerShape(Radius.sm)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = char,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.onBackground
                        )
                    }
                }
            }
        }
    )
}
