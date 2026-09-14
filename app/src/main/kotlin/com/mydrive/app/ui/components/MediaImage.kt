package com.mydrive.app.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mydrive.app.data.media.ThumbnailLoader
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.util.thumbnailBrush

@Composable
fun MediaImage(
    uri: String,
    seed: Int,
    type: MediaType,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    sizePx: Int = 256,
    contentDescription: String? = null,
    placeholderBitmap: Bitmap? = null,
    onUnavailable: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var bitmap by remember(uri, sizePx) {
        mutableStateOf(
            placeholderBitmap
                ?: ThumbnailLoader.peek(uri, sizePx)
                ?: ThumbnailLoader.peek(uri, 256)
        )
    }

    LaunchedEffect(uri, sizePx) {
        if (uri.isBlank()) {
            bitmap = null
            onUnavailable?.invoke()
            return@LaunchedEffect
        }
        val cached = ThumbnailLoader.peek(uri, sizePx)
        if (cached != null) {
            bitmap = cached
            return@LaunchedEffect
        }
        val loaded = ThumbnailLoader.load(context, uri, sizePx)
        if (loaded != null) {
            bitmap = loaded
        } else if (bitmap == null) {
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
        }
    }
}
