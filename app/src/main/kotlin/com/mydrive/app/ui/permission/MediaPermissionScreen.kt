package com.mydrive.app.ui.permission

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mydrive.app.ui.components.PrimaryActionButton
import com.mydrive.app.ui.components.SecondaryActionButton
import com.mydrive.app.ui.theme.Copper
import com.mydrive.app.ui.theme.Graphite
import com.mydrive.app.ui.theme.Ink
import com.mydrive.app.ui.theme.Ivory
import com.mydrive.app.ui.theme.Mist
import com.mydrive.app.ui.theme.Spacing
import com.mydrive.app.ui.theme.Stroke

@Composable
fun MediaPermissionScreen(
    denied: Boolean,
    onAllowAccess: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Ink)
            .padding(horizontal = Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .background(Graphite, CircleShape)
                .border(1.dp, Stroke, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.PhotoLibrary,
                contentDescription = null,
                tint = Copper,
                modifier = Modifier.size(40.dp)
            )
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = if (denied) "Allow access to continue" else "Your memories belong here",
            style = MaterialTheme.typography.headlineLarge,
            color = Ivory,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = if (denied) {
                "Albums needs access to your photos and videos. You can allow access or open Settings to turn it on."
            } else {
                "Allow Albums to access your photos and videos so you can browse them in one place."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Mist,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.xl))
        PrimaryActionButton(
            text = "Allow Access",
            onClick = onAllowAccess,
            modifier = Modifier.fillMaxWidth()
        )
        if (denied) {
            Spacer(Modifier.height(Spacing.sm))
            SecondaryActionButton(
                text = "Open Settings",
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
