package com.mydrive.app.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Spacing

@Composable
fun AuthLoadingScreen() {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Albums", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.lg))
        CircularProgressIndicator(color = Copper)
    }
}

@Composable
fun SuspendedAccountScreen(
    name: String,
    email: String,
    onLogout: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Account disabled", style = MaterialTheme.typography.headlineLarge, color = colors.onBackground)
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (name.isBlank()) {
                "This account is disabled and can't use Albums."
            } else {
                "$name, this account is disabled and can't use Albums."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (email.isNotBlank()) {
            Spacer(Modifier.height(Spacing.xs))
            Text(email, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.xl))
        PrimaryActionButton(
            text = "Logout",
            onClick = onLogout,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
