package com.mydrive.app.data.repository

import com.mydrive.app.data.auth.AuthErrorMapper
import com.mydrive.app.data.auth.AuthState
import com.mydrive.app.data.auth.AuthUserProfile
import com.mydrive.app.data.local.DeviceIdStore
import com.mydrive.app.data.local.DeviceInfoFactory
import com.mydrive.app.data.remote.NetworkMonitor
import com.mydrive.app.data.remote.SupabaseConfig
import com.mydrive.app.data.remote.dto.DeviceInsert
import com.mydrive.app.data.remote.dto.DeviceLastSeenUpdate
import com.mydrive.app.data.remote.dto.DeviceRow
import com.mydrive.app.data.remote.dto.ProfileNameUpdate
import com.mydrive.app.data.remote.dto.ProfileRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.CoroutineScope
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
    private val deviceIdStore: DeviceIdStore,
    private val network: NetworkMonitor,
    private val scope: CoroutineScope
) {

    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private val deviceMutex = Mutex()
    private var registeredDeviceId: String? = null
    private var lastSeenAtMillis = 0L
    private var sessionJob: Job? = null

    init {
        restoreSession()
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
                when (val status = client.auth.sessionStatus.value) {
                    is SessionStatus.Authenticated -> completeAuthenticatedSession(pendingFullName = null)
                    is SessionStatus.RefreshFailure -> {
                        runCatching { client.auth.signOut() }
                        _state.value = AuthState.Unauthenticated
                    }
                    else -> _state.value = AuthState.Unauthenticated
                }
            } catch (_: Throwable) {
                runCatching { client.auth.signOut() }
                _state.value = AuthState.Unauthenticated
            }
        }
    }

    suspend fun login(email: String, password: String): Result<Unit> {
        val supabase = client ?: return Result.failure(IllegalStateException(notConfiguredMessage()))
        if (!network.isOnline()) {
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
            val session = supabase.auth.currentSessionOrNull()
            if (session == null) {
                _state.value = AuthState.Unauthenticated
                throw EmailConfirmationRequired()
            }
            completeAuthenticatedSession(pendingFullName = fullName)
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
        val supabase = client
        registeredDeviceId = null
        lastSeenAtMillis = 0L
        return runCatching {
            supabase?.auth?.signOut()
            Unit
        }.also {
            _state.value = AuthState.Unauthenticated
        }.fold(
            onSuccess = { Result.success(Unit) },
            onFailure = {
                _state.value = AuthState.Unauthenticated
                Result.success(Unit)
            }
        )
    }

    fun onAppForeground() {
        val current = _state.value
        if (current !is AuthState.Authenticated && current !is AuthState.Suspended) return
        scope.launch {
            runCatching { touchDevice(force = false) }
        }
    }

    private suspend fun completeAuthenticatedSession(pendingFullName: String?) {
        val supabase = client ?: throw IllegalStateException(notConfiguredMessage())
        val userId = supabase.auth.currentUserOrNull()?.id
            ?: throw IllegalStateException("Your session expired. Please sign in again.")
        val profile = loadProfile(userId, pendingFullName)
        if (profile.isSuspended) {
            _state.value = AuthState.Suspended(profile)
            return
        }
        runCatching { touchDevice(force = true) }
        _state.value = AuthState.Authenticated(profile)
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
                    return AuthUserProfile(
                        id = row.id,
                        email = row.email.orEmpty(),
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
                    // Reuse an existing row if this device was already registered.
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
        _state.value = AuthState.Loading
        return try {
            block()
            Result.success(Unit)
        } catch (error: EmailConfirmationRequired) {
            _state.value = AuthState.Unauthenticated
            Result.failure(error)
        } catch (error: ProfileNotFound) {
            runCatching { client?.auth?.signOut() }
            _state.value = AuthState.Unauthenticated
            Result.failure(IllegalStateException("We couldn't load your account. Please try again."))
        } catch (error: Throwable) {
            runCatching {
                if (client?.auth?.currentSessionOrNull() == null) {
                    _state.value = AuthState.Unauthenticated
                }
            }
            if (_state.value is AuthState.Loading) {
                _state.value = AuthState.Unauthenticated
            }
            Result.failure(IllegalStateException(AuthErrorMapper.message(error)))
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
