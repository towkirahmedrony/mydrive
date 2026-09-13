package com.mydrive.app.data.auth

import com.mydrive.app.data.model.UserProfile

data class AuthUserProfile(
    val id: String,
    val email: String,
    val fullName: String,
    val role: String,
    val status: String
) {
    val isSuspended: Boolean
        get() = status.equals("suspended", ignoreCase = true)

    val displayName: String
        get() = fullName.ifBlank { email.substringBefore("@").ifBlank { "Account" } }

    val displayStatus: String
        get() = status.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            .ifBlank { "Active" }

    fun toUserProfile(): UserProfile = UserProfile(
        name = displayName,
        email = email,
        accountStatus = displayStatus
    )
}

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val profile: AuthUserProfile) : AuthState
    data class Suspended(val profile: AuthUserProfile) : AuthState
}
