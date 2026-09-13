package com.mydrive.app.data.auth

import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

object AuthErrorMapper {

    fun message(error: Throwable): String {
        if (error is AuthWeakPasswordException) return "Choose a stronger password."
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

        return when {
            isNetwork(error, raw) -> "No internet connection. Check your network and try again."
            isUnavailable(raw) -> "Albums is temporarily unavailable. Please try again."
            isInvalidCredentials(raw) -> "Incorrect email or password."
            isEmailTaken(raw) -> "This email is already registered."
            isWeakPassword(raw) -> "Choose a stronger password."
            isInvalidEmail(raw) -> "Enter a valid email address."
            isSessionExpired(raw) -> "Your session expired. Please sign in again."
            isRateLimited(raw) -> "Too many attempts. Please wait and try again."
            isEmailNotConfirmed(raw) -> "Confirm your email, then sign in."
            else -> "Something went wrong. Please try again."
        }
    }

    fun isSessionExpired(error: Throwable): Boolean {
        val raw = (error.message.orEmpty() + " " + error.cause?.message.orEmpty()).lowercase()
        return isSessionExpired(raw)
    }

    private fun isNetwork(error: Throwable, raw: String): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnknownHostException ||
                current is UnresolvedAddressException ||
                current is ConnectTimeoutException ||
                current is SocketTimeoutException ||
                current is HttpRequestException
            ) {
                return true
            }
            if (current is IOException && raw.contains("unable to resolve")) return true
            current = current.cause
        }
        return raw.contains("unable to resolve host") ||
            raw.contains("failed to connect") ||
            raw.contains("network is unreachable") ||
            raw.contains("timeout")
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
}
