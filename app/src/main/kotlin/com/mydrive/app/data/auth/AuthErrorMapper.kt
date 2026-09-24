package com.mydrive.app.data.auth

import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

object AuthErrorMapper {

    fun message(error: Throwable): String {
        if (error is CancellationException) throw error
        if (error is AuthWeakPasswordException) return "Choose a stronger password."
        if (error is AuthRestException) {
            mappedAuthCode(error.errorCode)?.let { return it }
            val description = error.errorDescription.trim()
            if (description.isNotBlank()) {
                mappedRaw(description.lowercase())?.let { return it }
                if (isUserFacing(description)) return clean(description)
            }
        }

        val raw = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
            if (error is RestException) {
                append(' ')
                append(error.error)
                append(' ')
                append(error.description.orEmpty())
            }
        }.lowercase()

        mappedRaw(raw)?.let { return it }
        if (isOffline(error, raw)) return "No internet connection. Check your network and try again."
        if (isTimeout(error, raw)) return "The request timed out. Please try again."
        if (isDnsFailure(error, raw)) return "Couldn't reach the server. Check your network and try again."

        val fallback = error.message?.trim().orEmpty()
        if (isUserFacing(fallback)) return clean(fallback)
        if (error is RestException) {
            val description = error.description?.trim().orEmpty()
            if (isUserFacing(description)) return clean(description)
        }
        return "Something went wrong. Please try again."
    }

    fun otpMessage(error: Throwable): String {
        val raw = buildString {
            append(error.message.orEmpty())
            append(' ')
            append(error.cause?.message.orEmpty())
            if (error is RestException) {
                append(' ')
                append(error.error)
                append(' ')
                append(error.description.orEmpty())
            }
        }.lowercase()
        if (error is AuthRestException) {
            when (error.errorCode) {
                AuthErrorCode.OtpExpired -> return "This code has expired. Request a new one."
                AuthErrorCode.OverRequestRateLimit,
                AuthErrorCode.OverEmailSendRateLimit -> return "Too many attempts. Please wait and try again."
                AuthErrorCode.ValidationFailed -> return "Enter a valid email address."
                AuthErrorCode.UserNotFound -> return "Enter a valid email address."
                else -> Unit
            }
        }
        return when {
            isOffline(error, raw) -> "No internet connection. Check your network and try again."
            isTimeout(error, raw) -> "The request timed out. Please try again."
            isDnsFailure(error, raw) -> "Couldn't reach the server. Check your network and try again."
            isOtpExpired(raw) -> "This code has expired. Request a new one."
            isOtpInvalid(raw) || isInvalidCredentials(raw) -> "That code is incorrect. Please try again."
            isRateLimited(raw) -> "Too many attempts. Please wait and try again."
            isInvalidEmail(raw) -> "Enter a valid email address."
            else -> message(error)
        }
    }

    fun isSessionExpired(error: Throwable): Boolean {
        if (error is AuthRestException) {
            return error.errorCode == AuthErrorCode.SessionExpired ||
                error.errorCode == AuthErrorCode.SessionNotFound ||
                error.errorCode == AuthErrorCode.BadJwt ||
                error.errorCode == AuthErrorCode.RefreshTokenNotFound ||
                error.errorCode == AuthErrorCode.RefreshTokenAlreadyUsed
        }
        val raw = (error.message.orEmpty() + " " + error.cause?.message.orEmpty()).lowercase()
        return isSessionExpired(raw)
    }

    private fun mappedAuthCode(code: AuthErrorCode?): String? = when (code) {
        AuthErrorCode.InvalidCredentials -> "Incorrect email or password."
        AuthErrorCode.EmailExists, AuthErrorCode.UserAlreadyExists -> "This email is already registered."
        AuthErrorCode.WeakPassword -> "Choose a stronger password."
        AuthErrorCode.ValidationFailed -> "Enter a valid email address."
        AuthErrorCode.EmailNotConfirmed -> "Confirm your email, then sign in."
        AuthErrorCode.OtpExpired -> "This code has expired. Request a new one."
        AuthErrorCode.OtpDisabled -> "Email verification codes are unavailable right now."
        AuthErrorCode.UserBanned -> "This account is suspended and can't use Albums."
        AuthErrorCode.OverRequestRateLimit,
        AuthErrorCode.OverEmailSendRateLimit -> "Too many attempts. Please wait and try again."
        AuthErrorCode.SessionExpired,
        AuthErrorCode.SessionNotFound,
        AuthErrorCode.BadJwt,
        AuthErrorCode.RefreshTokenNotFound,
        AuthErrorCode.RefreshTokenAlreadyUsed -> "Your session expired. Please sign in again."
        AuthErrorCode.SignupDisabled -> "New accounts can't be created right now."
        AuthErrorCode.EmailProviderDisabled -> "Email sign-in is unavailable right now."
        else -> null
    }

    private fun mappedRaw(raw: String): String? = when {
        isUnavailable(raw) -> "Albums is temporarily unavailable. Please try again."
        isOtpExpired(raw) -> "This code has expired. Request a new one."
        isOtpInvalid(raw) -> "That code is incorrect. Please try again."
        isInvalidCredentials(raw) -> "Incorrect email or password."
        isEmailTaken(raw) -> "This email is already registered."
        isWeakPassword(raw) -> "Choose a stronger password."
        isInvalidEmail(raw) -> "Enter a valid email address."
        isSessionExpired(raw) -> "Your session expired. Please sign in again."
        isRateLimited(raw) -> "Too many attempts. Please wait and try again."
        isEmailNotConfirmed(raw) -> "Confirm your email, then sign in."
        else -> null
    }

    private fun isOffline(error: Throwable, raw: String): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is IOException && raw.contains("network is unreachable")) {
                return true
            }
            current = current.cause
        }
        return raw.contains("network is unreachable") || raw.contains("no network")
    }

    private fun isTimeout(error: Throwable, raw: String): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is ConnectTimeoutException || current is SocketTimeoutException) return true
            current = current.cause
        }
        return raw.contains("timeout") || raw.contains("timed out")
    }

    private fun isDnsFailure(error: Throwable, raw: String): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException || current is UnresolvedAddressException) return true
            current = current.cause
        }
        return raw.contains("unable to resolve host") ||
            raw.contains("unable to resolve") ||
            raw.contains("failed to connect")
    }

    private fun isUnavailable(raw: String): Boolean {
        return raw.contains("503") ||
            raw.contains("502") ||
            raw.contains("504") ||
            raw.contains("service unavailable") ||
            raw.contains("overloaded")
    }

    private fun isInvalidCredentials(raw: String): Boolean {
        return raw.contains("invalid login credentials") ||
            raw.contains("invalid_credentials") ||
            raw.contains("invalid email or password") ||
            raw.contains("wrong password")
    }

    private fun isEmailTaken(raw: String): Boolean {
        return raw.contains("already registered") ||
            raw.contains("user already exists") ||
            raw.contains("email_exists") ||
            raw.contains("already been registered")
    }

    private fun isWeakPassword(raw: String): Boolean {
        return raw.contains("weak_password") ||
            raw.contains("password should be") ||
            raw.contains("password is too short") ||
            raw.contains("least 6")
    }

    private fun isInvalidEmail(raw: String): Boolean {
        return raw.contains("unable to validate email") ||
            raw.contains("invalid email") ||
            raw.contains("email_address_invalid")
    }

    private fun isSessionExpired(raw: String): Boolean {
        return raw.contains("session expired") ||
            raw.contains("jwt expired") ||
            raw.contains("refresh_token") && raw.contains("not found") ||
            raw.contains("invalid jwt") ||
            raw.contains("session_not_found")
    }

    private fun isRateLimited(raw: String): Boolean {
        return raw.contains("rate limit") || raw.contains("too many requests") || raw.contains("429")
    }

    private fun isEmailNotConfirmed(raw: String): Boolean {
        return raw.contains("email not confirmed") || raw.contains("email_not_confirmed")
    }

    private fun isOtpExpired(raw: String): Boolean {
        return raw.contains("otp_expired") ||
            raw.contains("otp expired") ||
            raw.contains("token has expired") ||
            raw.contains("code has expired") ||
            raw.contains("expired otp")
    }

    private fun isOtpInvalid(raw: String): Boolean {
        return raw.contains("invalid otp") ||
            raw.contains("otp_invalid") ||
            raw.contains("invalid token") ||
            raw.contains("token is invalid") ||
            raw.contains("invalid email otp") ||
            raw.contains("wrong code")
    }

    private fun isUserFacing(text: String): Boolean {
        if (text.isBlank() || text.length > 180) return false
        val lower = text.lowercase()
        if (lower.contains("io.github") ||
            lower.contains("kotlinx.") ||
            lower.contains("unexpected status") ||
            lower.contains("http/") ||
            lower.contains("json") && lower.contains("serial")
        ) {
            return false
        }
        return text.any { it.isWhitespace() } || text.endsWith(".")
    }

    private fun clean(text: String): String {
        val trimmed = text.trim().trimEnd(':')
        return trimmed.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}
