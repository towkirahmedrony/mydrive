package com.mydrive.app.ui.permission

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Requests local MediaStore permission once when the system has never asked.
 *
 * Permission only gates the local overlay. This effect never hides the cloud
 * catalog, never re-prompts after a denial, and never fires on recomposition.
 */
@Composable
fun MediaAccessRequest(
    needsPermission: Boolean,
    permissions: Array<String>,
    onResult: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { onResult() }
    var launched by remember { mutableStateOf(false) }
    LaunchedEffect(needsPermission) {
        if (!needsPermission || launched || permissions.isEmpty()) return@LaunchedEffect
        launched = true
        launcher.launch(permissions)
    }
}
