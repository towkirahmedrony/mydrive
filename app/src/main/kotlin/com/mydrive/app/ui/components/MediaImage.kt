package com.mydrive.app.ui.components

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.BuildConfig
import com.mydrive.app.MyDriveApp
import com.mydrive.app.data.session.AccountSession
import com.mydrive.app.ui.util.thumbnailBrush

@Composable
fun MediaImage(
    uri: String,
    seed: Int,
    type: MediaType,
    fallbackMediaId: String? = null,
    previewUri: String? = null,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    sizePx: Int = ThumbnailLoader.PREVIEW_SIZE_PX,
    contentDescription: String? = null,
    placeholderBitmap: Bitmap? = null,
    onUnavailable: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val ownerId = AccountSession.userId
    // A device URI stays the primary candidate; the cloud preview is only
    // consulted when it does not resolve. When there is no device URI at all the
    // preview becomes the primary, so the cache key stays URL-derived-or-media-id
    // based instead of collapsing onto an empty URI.
    val primaryUri = uri.ifBlank { previewUri.orEmpty() }
    val cloudFallback = previewUri?.takeIf { uri.isNotBlank() }
    var bitmap by remember(primaryUri, cloudFallback, fallbackMediaId, sizePx, ownerId) {
        mutableStateOf(
            placeholderBitmap
                ?: ThumbnailLoader.peek(primaryUri, sizePx, mediaId = fallbackMediaId, userId = ownerId)
                ?: ThumbnailLoader.peek(primaryUri, 256, mediaId = fallbackMediaId, userId = ownerId)
        )
    }
    var failed by remember(primaryUri, cloudFallback, fallbackMediaId, sizePx, ownerId) {
        mutableStateOf(false)
    }

    LaunchedEffect(primaryUri, cloudFallback, fallbackMediaId, sizePx, ownerId) {
        if (primaryUri.isBlank() && fallbackMediaId.isNullOrBlank()) {
            bitmap = null
            failed = true
            onUnavailable?.invoke()
            return@LaunchedEffect
        }
        val cached = ThumbnailLoader.peek(primaryUri, sizePx, mediaId = fallbackMediaId, userId = ownerId)
        if (cached != null) {
            bitmap = cached
        }
        val app = context.applicationContext as? MyDriveApp
        val loaded = ThumbnailLoader.load(
            context = context,
            uriString = uri,
            sizePx = sizePx,
            fallbackMediaId = fallbackMediaId,
            previewUri = cloudFallback,
            sessionProvider = app?.sessionProvider,
            userId = ownerId
        )
        if (loaded != null) {
            bitmap = loaded
        } else if (bitmap == null) {
            failed = true
            if (BuildConfig.DEBUG) {
                Log.d(
                    "MyDriveMediaImage",
                    "thumbnail_load_failed host=${runCatching { Uri.parse(primaryUri).host }.getOrNull()} size=$sizePx"
                )
            }
            onUnavailable?.invoke()
        }
    }

    Box(modifier = modifier.background(thumbnailBrush(seed, type))) {
        val current = bitmap
        if (current != null && !current.isRecycled) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale
            )
        } else if (failed) {
            Text(
                text = "Image unavailable",
                color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.72f),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.Center)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
    }
}
