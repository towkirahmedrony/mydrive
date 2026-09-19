package com.mydrive.app.data.auth

import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

sealed interface PreparedAuth {
    data class Available(val userId: String, val accessToken: String) : PreparedAuth
    data object SignedOut : PreparedAuth
    data object NetworkError : PreparedAuth
}

class AuthenticatedSessionProvider(
    private val client: SupabaseClient?
) {
    private val mutex = Mutex()

    fun currentUserIdOrNull(): String? {
        val supabase = client ?: return null
        return supabase.auth.currentUserOrNull()?.id
    }

    suspend fun prepare(forceRefresh: Boolean = false): PreparedAuth = mutex.withLock {
        val supabase = client ?: return@withLock PreparedAuth.SignedOut
        val auth = supabase.auth
        runCatching { auth.awaitInitialization() }

        when (val status = auth.sessionStatus.value) {
            is SessionStatus.Initializing -> runCatching { auth.awaitInitialization() }
            is SessionStatus.RefreshFailure -> {
                if (status.cause is RefreshFailureCause.NetworkError) {
                    sessionFrom(auth.currentSessionOrNull())?.let { return@withLock it }
                    return@withLock PreparedAuth.NetworkError
                }
                val loaded = runCatching { auth.loadFromStorage() }.getOrDefault(false)
                if (!loaded) {
                    sessionFrom(auth.currentSessionOrNull())?.let { return@withLock it }
                    return@withLock PreparedAuth.SignedOut
                }
            }
            is SessionStatus.NotAuthenticated -> {
                val loaded = runCatching { auth.loadFromStorage() }.getOrDefault(false)
                if (!loaded && auth.currentSessionOrNull() == null) {
                    return@withLock PreparedAuth.SignedOut
                }
            }
            is SessionStatus.Authenticated -> Unit
        }

        var session = auth.currentSessionOrNull()
            ?: return@withLock PreparedAuth.SignedOut

        val remainingSeconds = session.expiresAt.epochSeconds - Clock.System.now().epochSeconds
        val needsRefresh = forceRefresh ||
            remainingSeconds <= REFRESH_SKEW_SECONDS ||
            session.user?.id.isNullOrBlank()
        if (needsRefresh) {
            DeveloperLogger.info(
                category = LogCategory.AUTH,
                event = "SESSION_REFRESH_ATTEMPTED",
                message = if (forceRefresh) "Forced session refresh" else "Session refresh near expiry",
                metadata = mapOf(
                    "force_refresh" to forceRefresh.toString(),
                    "remaining_seconds" to remainingSeconds.toString(),
                    "expires_at" to session.expiresAt.toString()
                )
            )
            val refresh = runCatching { auth.refreshCurrentSession() }
            if (refresh.isFailure) {
                val error = refresh.exceptionOrNull()
                runCatching { auth.loadFromStorage() }
                session = auth.currentSessionOrNull() ?: session
                val recovered = sessionFrom(session)
                val recoveredSeconds = session.expiresAt.epochSeconds - Clock.System.now().epochSeconds
                if (recovered is PreparedAuth.Available && recoveredSeconds > REFRESH_SKEW_SECONDS) {
                    DeveloperLogger.warn(
                        category = LogCategory.AUTH,
                        event = "SESSION_REFRESH_FAILED",
                        message = "Refresh failed but a valid local session was recovered",
                        throwable = error,
                        metadata = mapOf("error_source" to "supabase_client")
                    )
                    return@withLock recovered
                }
                if (error != null && AuthErrorMapper.isSessionExpired(error)) {
                    DeveloperLogger.error(
                        category = LogCategory.AUTH,
                        event = "SESSION_EXPIRED",
                        message = "Session expired during refresh",
                        throwable = error,
                        metadata = mapOf("error_source" to "supabase_client")
                    )
                    return@withLock PreparedAuth.SignedOut
                }
                DeveloperLogger.error(
                    category = LogCategory.AUTH,
                    event = "SESSION_REFRESH_FAILED",
                    message = "Session refresh failed",
                    throwable = error,
                    metadata = mapOf("error_source" to "supabase_client")
                )
                return@withLock recovered ?: PreparedAuth.NetworkError
            }
            DeveloperLogger.info(
                category = LogCategory.AUTH,
                event = "SESSION_REFRESH_SUCCEEDED",
                message = "Session refresh succeeded"
            )
            session = auth.currentSessionOrNull() ?: return@withLock PreparedAuth.SignedOut
        }

        return@withLock sessionFrom(session) ?: PreparedAuth.SignedOut
    }

    private fun sessionFrom(session: UserSession?): PreparedAuth.Available? {
        if (session == null) return null
        val accessToken = session.accessToken
        if (accessToken.isBlank()) return null
        val userId = session.user?.id
        if (userId.isNullOrBlank()) return null
        return PreparedAuth.Available(userId = userId, accessToken = accessToken)
    }

    companion object {
        private const val REFRESH_SKEW_SECONDS = 60L
    }
}
