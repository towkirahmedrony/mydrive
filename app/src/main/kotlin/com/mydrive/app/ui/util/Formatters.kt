package com.mydrive.app.ui.util

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.mydrive.app.data.model.MediaType
import com.mydrive.app.ui.theme.ClayDim
import com.mydrive.app.ui.theme.CopperDim
import com.mydrive.app.ui.theme.SageDim
import com.mydrive.app.ui.theme.Slate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatFileSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return if (mb < 1024) String.format(Locale.US, "%.1f MB", mb)
    else String.format(Locale.US, "%.1f GB", mb / 1024.0)
}

fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

fun formatPlaybackMs(milliseconds: Int): String {
    val totalSec = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

fun formatDateTime(millis: Long): String {
    return SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.US).format(Date(millis))
}

fun formatTimeAgo(millis: Long, now: Long = System.currentTimeMillis()): String {
    val diff = (now - millis).coerceAtLeast(0)
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60}h ago"
        else -> "${minutes / (24 * 60)}d ago"
    }
}

fun dateGroupLabel(millis: Long, now: Long = System.currentTimeMillis()): String {
    val nowCal = Calendar.getInstance().apply { timeInMillis = now }
    val itemCal = Calendar.getInstance().apply { timeInMillis = millis }
    if (nowCal.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
        nowCal.get(Calendar.DAY_OF_YEAR) == itemCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Today"
    }
    val yesterday = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -1)
    }
    if (yesterday.get(Calendar.YEAR) == itemCal.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == itemCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return "Yesterday"
    }
    return SimpleDateFormat("MMMM d", Locale.US).format(Date(millis))
}

fun greetingForHour(hour: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..17 -> "Good afternoon"
    else -> "Good evening"
}

fun thumbnailBrush(seed: Int, type: MediaType): Brush {
    val palettes = listOf(
        listOf(Color(0xFF2A2420), Color(0xFF6B5344), Color(0xFFC4A484)),
        listOf(Color(0xFF1C2422), Color(0xFF3E5A4E), Color(0xFF8FADA0)),
        listOf(Color(0xFF1A1E28), Color(0xFF3A4A62), Color(0xFF8AA4C4)),
        listOf(Color(0xFF2A1C1A), Color(0xFF6F3A2A), Color(0xFFD97858)),
        listOf(Color(0xFF222018), Color(0xFF5A5340), Color(0xFFB8A878)),
        listOf(Color(0xFF1E2228), Color(0xFF4A5568), Color(0xFF9AA8BC))
    )
    val colors = palettes[seed.mod(palettes.size)]
    return if (type == MediaType.VIDEO) {
        Brush.linearGradient(colors)
    } else {
        Brush.verticalGradient(colors)
    }
}

val PlaceholderDark: Color = Slate
val AccentWarm: Color = CopperDim
val AccentCool: Color = SageDim
val AccentClay: Color = ClayDim
