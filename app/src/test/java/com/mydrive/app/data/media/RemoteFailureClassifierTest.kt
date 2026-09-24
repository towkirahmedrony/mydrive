package com.mydrive.app.data.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class RemoteFailureClassifierTest {

    @Test
    fun `no route to the backend is offline`() {
        assertEquals(
            RemoteMediaFailure.OFFLINE,
            RemoteFailureClassifier.classify(UnknownHostException("api.example.test"))
        )
        assertEquals(
            RemoteMediaFailure.OFFLINE,
            RemoteFailureClassifier.classify(IOException("network is unreachable"))
        )
    }

    @Test
    fun `socket timeouts are timeouts`() {
        assertEquals(
            RemoteMediaFailure.TIMEOUT,
            RemoteFailureClassifier.classify(SocketTimeoutException("read timed out"))
        )
        assertEquals(
            RemoteMediaFailure.TIMEOUT,
            RemoteFailureClassifier.classify(IOException("Request timeout has expired"))
        )
    }

    @Test
    fun `a request timeout raised by the repository stays a timeout`() {
        val timeout = RemoteMediaException(RemoteMediaFailure.TIMEOUT)
        assertEquals(RemoteMediaFailure.TIMEOUT, RemoteFailureClassifier.classify(timeout))
    }

    @Test
    fun `a wrapped offline failure is still offline`() {
        val wrapped = IllegalStateException("query failed", IOException("failed to connect"))
        assertEquals(RemoteMediaFailure.OFFLINE, RemoteFailureClassifier.classify(wrapped))
    }

    @Test
    fun `a backend error is a server failure, never an absence`() {
        assertEquals(
            RemoteMediaFailure.SERVER,
            RemoteFailureClassifier.classify(IllegalStateException("Unexpected status 500"))
        )
    }

    @Test
    fun `an unauthenticated catalog request is unauthorized`() {
        assertEquals(
            RemoteMediaFailure.UNAUTHORIZED,
            RemoteFailureClassifier.classify(RemoteMediaException(RemoteMediaFailure.UNAUTHORIZED))
        )
    }

    @Test
    fun `messages stay non blocking and keep the existing server wording`() {
        assertEquals("Couldn't load your photos and videos.", RemoteFailureClassifier.message(RemoteMediaFailure.SERVER))
        assertEquals(
            "You're offline. Connect to the internet to sync your library.",
            RemoteFailureClassifier.message(RemoteMediaFailure.OFFLINE)
        )
    }
}
