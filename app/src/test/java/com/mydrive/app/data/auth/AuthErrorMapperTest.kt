package com.mydrive.app.data.auth

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class AuthErrorMapperTest {
    @Test
    fun `invalid credentials are not reported as offline`() {
        assertEquals(
            "Incorrect email or password.",
            AuthErrorMapper.message(IllegalStateException("invalid login credentials"))
        )
    }

    @Test
    fun `server errors are not reported as offline`() {
        assertEquals(
            "Albums is temporarily unavailable. Please try again.",
            AuthErrorMapper.message(IllegalStateException("Unexpected status 500"))
        )
    }

    @Test
    fun `timeouts remain distinct from offline`() {
        assertEquals(
            "The request timed out. Please try again.",
            AuthErrorMapper.message(SocketTimeoutException("read timed out"))
        )
    }

    @Test
    fun `dns failures remain distinct from confirmed offline`() {
        assertEquals(
            "Couldn't reach the server. Check your network and try again.",
            AuthErrorMapper.message(UnknownHostException("auth.example.test"))
        )
        assertEquals(
            "No internet connection. Check your network and try again.",
            AuthErrorMapper.message(IOException("network is unreachable"))
        )
    }

    @Test
    fun `cancellation is never converted into a user facing auth error`() {
        assertThrows(CancellationException::class.java) {
            AuthErrorMapper.message(CancellationException("cancelled"))
        }
    }
}
