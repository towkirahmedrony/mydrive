package com.mydrive.app.debug

import android.net.Uri

object SecretRedactor {
    private const val REDACTED = "[REDACTED]"
    private const val MAX_BODY = 2_048
    private const val MAX_STACK = 8_192

    private val sensitiveKeys = setOf(
        "authorization",
        "auth",
        "bearer",
        "token",
        "access_token",
        "accesstoken",
        "refresh_token",
        "refreshtoken",
        "id_token",
        "api_key",
        "apikey",
        "api_secret",
        "apisecret",
        "secret",
        "signature",
        "password",
        "passwd",
        "bot_token",
        "bottoken",
        "client_secret",
        "clientsecret",
        "service_role",
        "servicerole",
        "private_key",
        "privatekey",
        "jwt"
    )

    private val patterns = listOf(
        Regex("(?i)(authorization\\s*[:=]\\s*)([^\\s,;\"']+)"),
        Regex("(?i)(bearer\\s+)([A-Za-z0-9._\\-+=/]+)"),
        Regex("eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9._\\-]{10,}\\.[A-Za-z0-9._\\-]{10,}"),
        Regex("(?i)(refresh[_-]?token\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(access[_-]?token\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(api[_-]?key\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(api[_-]?secret\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(signature\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(client[_-]?secret\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(service[_-]?role[^\\s\"']*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("(?i)(bot[_-]?token\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)"),
        Regex("\\b\\d{8,10}:[A-Za-z0-9_-]{30,}\\b"),
        Regex("(?i)(password\\s*[:=]\\s*[\"']?)([^\\s,;\"']+)")
    )

    fun text(value: String?): String {
        if (value.isNullOrEmpty()) return value.orEmpty()
        var result: String = value
        for (pattern in patterns) {
            result = pattern.replace(result) { match ->
                when (match.groupValues.size) {
                    3 -> match.groupValues[1] + REDACTED
                    else -> REDACTED
                }
            }
        }
        return result
    }

    fun body(value: String?): String {
        val redacted = text(value)
        return if (redacted.length <= MAX_BODY) redacted else redacted.take(MAX_BODY) + "…"
    }

    fun stack(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val redacted = text(value)
        return if (redacted.length <= MAX_STACK) redacted else redacted.take(MAX_STACK) + "…"
    }

    fun metadata(raw: Map<String, String?>): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(raw.size)
        for ((key, value) in raw) {
            if (value.isNullOrBlank()) continue
            val normalized = key.lowercase().replace("-", "_")
            out[key] = if (sensitiveKeys.any { normalized.contains(it) }) {
                REDACTED
            } else {
                text(value).take(512)
            }
        }
        return out
    }

    fun safePath(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(url)
            val path = uri.path?.takeIf { it.isNotBlank() } ?: url
            path.substringBefore("?")
        } catch (_: Exception) {
            url.substringBefore("?").substringBefore("://").let {
                if (url.contains("://")) {
                    "/" + url.substringAfter("://").substringAfter("/", missingDelimiterValue = "").substringBefore("?")
                } else {
                    url.substringBefore("?")
                }
            }
        }
    }

    fun maskUserId(userId: String?): String? {
        if (userId.isNullOrBlank()) return null
        return if (userId.length <= 8) userId.take(4) + "…" else userId.take(8) + "…"
    }
}

