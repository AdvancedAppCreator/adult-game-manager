package com.example.f95updater

internal fun formatIsoDate(value: String): String =
    value.take(10).ifBlank { value }

internal fun formatViews(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000     -> "%.0fK".format(n / 1_000.0)
    else           -> n.toString()
}

internal fun formatRelativeTime(ms: Long): String {
    val now = System.currentTimeMillis()
    val deltaSec = (now - ms) / 1000
    if (deltaSec < 0) return "just now"
    return when {
        deltaSec < 60        -> "just now"
        deltaSec < 3600      -> "${deltaSec / 60}m ago"
        deltaSec < 86_400    -> "${deltaSec / 3600}h ago"
        deltaSec < 604_800   -> "${deltaSec / 86_400}d ago"
        else                 -> {
            val d = java.util.Date(ms)
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(d)
        }
    }
}
