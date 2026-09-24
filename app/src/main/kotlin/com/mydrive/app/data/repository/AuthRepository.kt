package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthErrorMapper
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.auth.AuthUserProfile
import com.mydrive.app.data.auth.AuthenticatedSessionProvider
import com.mydrive.app.data.auth.PreparedAuth
import com.mydrive.app.data.local.DeviceIdStore
import com.mydrive.app.data.local.DeviceInfoFactory
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.SupabaseConfig
import com.mydrive.app.debug.DeveloperLogger
import com.mydrive.app.debug.LogCategory
import com.mydrive.app.debug.SecretRedactor
import com.mydrive.app.data.remote.dto.DeviceInsert
import com.mydrive.app.data.remote.dto.DeviceLastSeenUpdate
import com.mydrive.app.data.remote.dto.DeviceRow
import com.mydrive.app.data.remote.dto.ProfileNameUpdate
import com.mydrive.app.data.remote.dto.ProfileRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.RefreshFailureCause
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant

class AuthRepository(
    private val client: SupabaseClient?,
    private val sessionProvider: AuthenticatedSessionProvider,
    private val deviceIdStore: DeviceIdStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope,
    private val onSignedOut: (previousUserId: String?) -> Unit = {},
    private val onAuthenticated: (userId: String) -> Unit = {}
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val sessionMutex = Mutex()
    private val deviceMutex = Mutex()
    private var registeredDeviceId: String? = null
    private var lastSeenAtMillis = 0L
    private var sessionJob: Job? = null
    private var watcherJob: Job? = null
    private var pendingFullName: String? = null

    init {
        restoreSession()
        watchSession()
    }

    fun restoreSession() {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            _state.value = AuthState.Loading
            if (!SupabaseConfig.isConfigured || client == null) {
                _state.value = AuthState.Unauthenticated
                return@launch
            }
            try {
                client.auth.awaitInitialization()
                var status = client.auth.sessionStatus.value
                if (status is SessionStatus.RefreshFailure) {
                    for (attempt in 1..3) {
                        delay(800L * attempt)
                        runCatching { client.auth.loadFromStorage() }
                        status = client.auth.sessionStatus.value
                        if (status !is SessionStatus.RefreshFailure) break
                    }
                }
                when (val resolved = client.auth.sessionStatus.value) {
                    is SessionStatus.Authenticated -> completeAuthenticatedSession(pendingFullName = null)
                    is SessionStatus.RefreshFailure -> {
                        DeveloperLogger.warn(
                            category = LogCategory.AUTH,
                            event = "SESSION_REFRESH_FAILED",
                            message = "Session restore hit refresh failure",
                            metadata = mapOf("error_source" to "supabase_client")
                        )
                        if (shouldClearRefreshFailure(resolved.cause)) {
                            runCatching { client.auth.signOut() }
                        }
                        _state.value = AuthState.Unauthenticated
                    }
                    else -> _state.value = AuthState.Unauthenticated
                }
            } catch (error: Throwable) {
                if (AuthErrorMapper.isSessionExpired(error)) {
                    runCatching { client.auth.signOut() }
                }
                _state.value = AuthState.Unauthenticated
            }
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (network.isDefinitelyOffline()) {
            return Result.failure(IllegalStateException(AuthErrorMapper.message(UnknownNetwork())))
        }
        return runAuth {
            supabase.auth.signInWith(Email) {
                this.email = email
                this.password = password
            }
            completeAuthenticatedSession(pendingFullName = null)
        }
    }

    suspend fun signUp(
        fullName: String,
        email: String,
        password: String
    ): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (!network.isOnline()) {
            return Result.failure(IllegalStateException(AuthErrorMapper.message(UnknownNetwork())))
        }
        return runAuth {
            supabase.auth.signUpWith(Email) {
                this.email = email
                this.password = password
                data = buildJsonObject {
                    put("full_name", fullName)
                }
            }
            pendingFullName = fullName
            if (supabase.auth.currentSessionOrNull() != null) {
                runCatching { supabase.auth.signOut() }
            }
        }
    }

    suspend fun sendVerificationCode(email: String): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (!network.isOnline()) {
            return Result.failure(IllegalStateException(AuthErrorMapper.message(UnknownNetwork())))
        }
        val normalized = email.trim()
        if (normalized.isBlank()) {
            return Result.failure(IllegalStateException("Enter a valid email address."))
        }
        return runCatching {
            supabase.auth.resendEmail(OtpType.Email.SIGNUP, normalized)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(IllegalStateException(AuthErrorMapper.otpMessage(it))) }
        )
    }

    suspend fun verifyEmailCode(email: String, code: String): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (!network.isOnline()) {
            return Result.failure(IllegalStateException(AuthErrorMapper.message(UnknownNetwork())))
        }
        val normalizedEmail = email.trim()
        val token = code.filter { it.isDigit() }
        if (normalizedEmail.isBlank()) {
            return Result.failure(IllegalStateException("Enter a valid email address."))
        }
        if (token.length != 6) {
            return Result.failure(IllegalStateException("Enter the 6-digit code from your email."))
        }
        return try {
            supabase.auth.verifyEmailOtp(
                type = OtpType.Email.EMAIL,
                email = normalizedEmail,
                token = token
            )
            completeAuthenticatedSession(pendingFullName = pendingFullName)
            Result.success(Unit)
        } catch (error: ProfileNotFound) {
            runCatching { supabase.auth.signOut() }
            _state.value = AuthState.Unauthenticated
            Result.failure(IllegalStateException("We couldn't load your account. Please try again."))
        } catch (error: Throwable) {
            Result.failure(IllegalStateException(AuthErrorMapper.otpMessage(error)))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (!network.isOnline()) {
            return Result.failure(IllegalStateException(AuthErrorMapper.message(UnknownNetwork())))
        }
        return runCatching {
            supabase.auth.resetPasswordForEmail(email)
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = { Result.failure(IllegalStateException(AuthErrorMapper.message(it))) }
        )
    }

    suspend fun logout(): Result<Unit> {
        val previousUserId = sessionProvider.currentUserIdOrNull()
            ?: client?.auth?.currentUserOrNull()?.id
        registeredDeviceId = null
        lastSeenAtMillis = 0L
        pendingFullName = null
        onSignedOut(previousUserId)
        DeveloperLogger.info(
            category = LogCategory.AUTH,
            event = "SIGNED_OUT",
            message = "User signed out",
            metadata = mapOf("user_id" to SecretRedactor.maskUserId(previousUserId).orEmpty())
        )
        return runCatching {
            client?.auth?.signOut()
            Unit
        }.also {
            _state.value = AuthState.Unauthenticated
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                runCatching { client?.auth?.clearSession() }
                _state.value = AuthState.Unauthenticated
                Result.success(Unit)
            }
        )
    }

    fun onAppForeground() {
        val current = _state.value
        if (current !is AuthState.Authenticated && current !is AuthState.Suspended) return
        scope.launch {
            runCatching { sessionProvider.prepare() }
            runCatching { touchDevice(force = false) }
        }
    }

    /**
     * Ensures this device is registered for the current user and returns the
     * `devices.id` row id (null when there is no active session). Used by the
     * backup pipeline when finalizing a Cloudinary upload.
     */
    suspend fun ensureDeviceRegistered(): String? {
        when (sessionProvider.prepare()) {
            is PreparedAuth.Available -> Unit
            else -> return null
        }
        if (registeredDeviceId != null) return registeredDeviceId
        touchDevice(force = true)
        return registeredDeviceId
    }

    private fun watchSession() {
        val supabase = client ?: return
        watcherJob?.cancel()
        watcherJob = scope.launch {
            supabase.auth.sessionStatus.collect { status ->
                when (status) {
                    is SessionStatus.NotAuthenticated -> {
                        val previousUserId = when (val current = _state.value) {
                            is AuthState.Authenticated -> current.profile.id
                            is AuthState.Suspended -> current.profile.id
                            else -> null
                        }
                        if (previousUserId != null) {
                            registeredDeviceId = null
                            lastSeenAtMillis = 0L
                            onSignedOut(previousUserId)
                            _state.value = AuthState.Unauthenticated
                            DeveloperLogger.info(
                                category = LogCategory.AUTH,
                                event = "SIGNED_OUT",
                                message = "Supabase reported NotAuthenticated",
                                metadata = mapOf(
                                    "user_id" to SecretRedactor.maskUserId(previousUserId).orEmpty(),
                                    "error_source" to "supabase_client"
                                )
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun completeAuthenticatedSession(pendingFullName: String?) {
        sessionMutex.withLock {
            val supabase = client ?: throw IllegalStateException(notConfiguredMessage())
            runCatching { supabase.auth.awaitInitialization() }
            val userId = supabase.auth.currentUserOrNull()?.id
                ?: throw IllegalStateException("Your session expired. Please sign in again.")
            val previousUserId = when (val current = _state.value) {
                is AuthState.Authenticated -> current.profile.id
                is AuthState.Suspended -> current.profile.id
                else -> null
            }
            if (previousUserId != null && previousUserId != userId) {
                onSignedOut(previousUserId)
            }
            val already = previousUserId == userId
            if (already) return
            val profile = try {
                loadProfile(userId, pendingFullName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (AuthErrorMapper.isSessionExpired(error)) throw error
                DeveloperLogger.warn(
                    category = LogCategory.AUTH,
                    event = "PROFILE_LOAD_DEFERRED",
                    message = "Authenticated session established but profile initialization was deferred",
                    throwable = error,
                    metadata = mapOf("user_id" to SecretRedactor.maskUserId(userId).orEmpty())
                )
                AuthUserProfile(
                    id = userId,
                    email = supabase.auth.currentUserOrNull()?.email.orEmpty(),
                    fullName = pendingFullName.orEmpty(),
                    role = "",
                    status = "active"
                )
            }
            onAuthenticated(userId)
            if (profile.isSuspended) {
                _state.value = AuthState.Suspended(profile)
                return
            }
            _state.value = AuthState.Authenticated(profile)
            DeveloperLogger.info(
                category = LogCategory.AUTH,
                event = "SIGNED_IN",
                message = "Authenticated session established",
                metadata = mapOf(
                    "user_id" to SecretRedactor.maskUserId(userId).orEmpty(),
                    "role" to profile.role,
                    "status" to profile.status
                )
            )
        }
        scope.launch {
            runCatching { touchDevice(force = true) }
        }
    }

    private suspend fun loadProfile(userId: String, pendingFullName: String?): AuthUserProfile {
        val supabase = client ?: throw IllegalStateException(notConfiguredMessage())
        var lastError: Throwable? = null
        repeat(4) { attempt ->
            try {
                val row = withContext(Dispatchers.IO) {
                    supabase.from(TABLE_PROFILES)
                        .select {
                            filter { eq("id", userId) }
                        }
                        .decodeSingleOrNull<ProfileRow>()
                }
                if (row != null) {
                    val name = row.fullName?.trim().orEmpty().ifBlank { pendingFullName.orEmpty().trim() }
                    if (name.isNotBlank() && row.fullName.isNullOrBlank()) {
                        runCatching {
                            supabase.from(TABLE_PROFILES).update(ProfileNameUpdate(name)) {
                                filter { eq("id", userId) }
                            }
                        }
                    }
                    val email = row.email?.trim().orEmpty().ifBlank {
                        supabase.auth.currentUserOrNull()?.email.orEmpty()
                    }
                    return AuthUserProfile(
                        id = row.id,
                        email = email,
                        fullName = name,
                        role = row.role.orEmpty(),
                        status = row.status.orEmpty().ifBlank { "active" }
                    )
                }
            } catch (error: Throwable) {
                lastError = error
                if (AuthErrorMapper.isSessionExpired(error)) throw error
            }
            if (attempt < 3) delay(400L * (attempt + 1))
        }
        throw lastError ?: ProfileNotFound()
    }

    private suspend fun touchDevice(force: Boolean) {
        val supabase = client ?: return
        if (_state.value is AuthState.Suspended) return
        runCatching { supabase.auth.awaitInitialization() }
        val userId = supabase.auth.currentUserOrNull()?.id ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSeenAtMillis < MIN_LAST_SEEN_INTERVAL_MS) return
        deviceMutex.withLock {
            val again = System.currentTimeMillis()
            if (!force && again - lastSeenAtMillis < MIN_LAST_SEEN_INTERVAL_MS) return
            val timestamp = Instant.now().toString()
            val deviceUid = deviceIdStore.deviceUid()
            val existingId = registeredDeviceId ?: findExistingDeviceId(userId, deviceUid)
            if (existingId != null) {
                supabase.from(TABLE_DEVICES).update(DeviceLastSeenUpdate(timestamp)) {
                    filter { eq("id", existingId) }
                }
                registeredDeviceId = existingId
            } else {
                try {
                    supabase.from(TABLE_DEVICES).insert(
                        DeviceInsert(
                            userId = userId,
                            deviceUid = deviceUid,
                            deviceName = DeviceInfoFactory.friendlyName(),
                            brand = DeviceInfoFactory.brand(),
                            model = DeviceInfoFactory.model(),
                            androidVersion = DeviceInfoFactory.androidVersion(),
                            status = "active",
                            lastSeenAt = timestamp
                        )
                    )
                } catch (_: Throwable) {
                }
                val reusedId = findExistingDeviceId(userId, deviceUid)
                if (reusedId != null) {
                    supabase.from(TABLE_DEVICES).update(DeviceLastSeenUpdate(timestamp)) {
                        filter { eq("id", reusedId) }
                    }
                }
                registeredDeviceId = reusedId
            }
            lastSeenAtMillis = again
        }
    }

    private suspend fun findExistingDeviceId(userId: String, deviceUid: String): String? {
        val supabase = client ?: return null
        val matches = supabase.from(TABLE_DEVICES)
            .select {
                filter {
                    eq("user_id", userId)
                    eq("device_uid", deviceUid)
                }
            }
            .decodeList<DeviceRow>()
        return matches.firstOrNull()?.id
    }

    private suspend fun runAuth(block: suspend () -> Unit): Result<Unit> {
        return try {
            block()
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: EmailConfirmationRequired) {
            Result.failure(error)
        } catch (error: ProfileNotFound) {
            runCatching { client?.auth?.signOut() }
            _state.value = AuthState.Unauthenticated
            Result.failure(IllegalStateException("We couldn't load your account. Please try again."))
        } catch (error: Throwable) {
            val current = _state.value
            if (current is AuthState.Loading) {
                _state.value = AuthState.Unauthenticated
            } else if (client?.auth?.currentSessionOrNull() == null &&
                current !is AuthState.Authenticated &&
                current !is AuthState.Suspended
            ) {
                _state.value = AuthState.Unauthenticated
            }
            Result.failure(IllegalStateException(AuthErrorMapper.message(error)))
        }
    }

    private fun shouldClearRefreshFailure(cause: RefreshFailureCause): Boolean {
        return when (cause) {
            is RefreshFailureCause.NetworkError -> false
            is RefreshFailureCause.InternalServerError -> AuthErrorMapper.isSessionExpired(cause.exception)
        }
    }

    private fun notConfiguredMessage(): String {
        return "Albums isn't connected right now. Please try again later."
    }

    class EmailConfirmationRequired : IllegalStateException(
        "Check your email to confirm your account, then sign in."
    )

    class ProfileNotFound : IllegalStateException("We couldn't load your account. Please try again.")

    private class UnknownNetwork : IOLessNetwork()

    private open class IOLessNetwork : RuntimeException("network is unreachable")

    companion object {
        private const val TABLE_PROFILES = "profiles"
        private const val TABLE_DEVICES = "devices"
        private const val MIN_LAST_SEEN_INTERVAL_MS = 5 * 60 * 1000L
    }
}
