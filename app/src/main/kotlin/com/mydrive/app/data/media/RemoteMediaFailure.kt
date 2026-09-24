package com.mydrive.app.data.media

import com.mydrive.app.data.auth.AuthErrorMapper
import io.github.jan.supabase.exceptions.HttpRequestException
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import java.io.IOException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException

/**
 * Why a remote catalog request produced no data.
 *
 * The point of naming these separately is that **none of them means the media is
 * gone**. A catalog reconciliation may only conclude that a `media_assets` row
 * disappeared when the backend answered successfully and that row was not in the
 * answer. Every value below therefore preserves the last known catalog instead of
 * replacing it with an empty one.
 */
enum class RemoteMediaFailure {
    /** The device reports no usable network; no request was attempted. */
    OFFLINE,

    /** The request was started but did not answer within the client budget. */
    TIMEOUT,

    /** No authenticated session (or a session the backend rejected). */
    UNAUTHORIZED,

    /** The backend answered with an error, or the exchange could not be read. */
    SERVER
}

/**
 * A classified reconciliation failure. Deliberately **not** a
 * [kotlinx.coroutines.CancellationException]: the caller must be able to tell a
 * failed request (keep the catalog, report the failure) from real cancellation
 * (stop, commit nothing).
 */
class RemoteMediaException(
    val failure: RemoteMediaFailure,
    cause: Throwable? = null
) : Exception("Remote media request failed (${failure.name})", cause)

/**
 * Maps a thrown error to a [RemoteMediaFailure] using the same signals the
 * existing auth mapper already relies on (DNS/connect failures, socket timeouts,
 * Supabase request failures), so the whole app classifies connectivity the same
 * way. Cancellation is never classified — it must be propagated instead.
 */
object RemoteFailureClassifier {

    fun classify(error: Throwable): RemoteMediaFailure {
        var current: Throwable? = error
        while (current != null) {
            when {
                current is RemoteMediaException -> return current.failure
                current is TimeoutCancellationException -> return RemoteMediaFailure.TIMEOUT
                current is SocketTimeoutException || current is ConnectTimeoutException ->
                    return RemoteMediaFailure.TIMEOUT
                current is UnknownHostException ||
                    current is UnresolvedAddressException ||
                    current is HttpRequestException -> return RemoteMediaFailure.OFFLINE
                AuthErrorMapper.isSessionExpired(current) -> return RemoteMediaFailure.UNAUTHORIZED
                current is RestException && mentionsAuthorization(current) ->
                    return RemoteMediaFailure.UNAUTHORIZED
                // Any other transport failure reached neither an answer nor an
                // auth verdict: "couldn't ask", never "nothing exists". A ktor
                // request timeout also arrives as an IOException.
                current is IOException -> return if (mentionsTimeout(current)) {
                    RemoteMediaFailure.TIMEOUT
                } else {
                    RemoteMediaFailure.OFFLINE
                }
            }
            current = current.cause
        }
        return RemoteMediaFailure.SERVER
    }

    /**
     * Non-blocking, user-facing wording for a failed reconciliation. The empty
     * catalog case shows this and keeps the retry path open; when data is already
     * on screen the caller keeps the data and does not surface an error at all.
     */
    fun message(failure: RemoteMediaFailure): String = when (failure) {
        RemoteMediaFailure.OFFLINE -> "You're offline. Connect to the internet to sync your library."
        RemoteMediaFailure.TIMEOUT -> "The server took too long to respond. Pull to refresh to try again."
        RemoteMediaFailure.UNAUTHORIZED -> "Your session expired. Sign in again to sync your library."
        RemoteMediaFailure.SERVER -> "Couldn't load your photos and videos."
    }

    private fun mentionsTimeout(error: Throwable): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") || message.contains("timed out")
    }

    private fun mentionsAuthorization(error: RestException): Boolean {
        val raw = buildString {
            append(error.error)
            append(' ')
            append(error.description.orEmpty())
            append(' ')
            append(error.message.orEmpty())
        }.lowercase()
        return raw.contains("401") ||
            raw.contains("unauthorized") ||
            raw.contains("invalid jwt") ||
            raw.contains("jwt expired") ||
            raw.contains("session_not_found")
    }
}
