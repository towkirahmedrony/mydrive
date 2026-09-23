package com.mydrive.app.data.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AccountSessionTest {

    @Before
    fun reset() {
        AccountSession.bind(null)
    }

    @Test
    fun bindAndUnbindChangeIdentityAndGeneration() {
        val before = AccountSession.snapshot()
        AccountSession.bind("user-a")
        val userA = AccountSession.snapshot()
        assertEquals("user-a", userA.userId)
        assertTrue(userA.generation > before.generation)
        assertTrue(AccountSession.isCurrent("user-a", userA.generation))

        AccountSession.bind(null)
        assertNull(AccountSession.userId)
        assertFalse(AccountSession.isCurrent("user-a", userA.generation))
    }

    @Test
    fun accountSwitchInvalidatesPreviousGeneration() {
        AccountSession.bind("user-a")
        val userA = AccountSession.snapshot()
        AccountSession.bind("user-b")
        val userB = AccountSession.snapshot()
        assertEquals("user-b", AccountSession.userId)
        assertFalse(AccountSession.isCurrent(userA.userId, userA.generation))
        assertTrue(AccountSession.isCurrent(userB.userId, userB.generation))
    }

    @Test
    fun sameUserRebindStillRotatesGeneration() {
        AccountSession.bind("user-a")
        val first = AccountSession.snapshot()
        AccountSession.bind("user-a")
        val second = AccountSession.snapshot()
        assertEquals("user-a", second.userId)
        assertTrue(second.generation > first.generation)
        assertFalse(AccountSession.isCurrent(first.userId, first.generation))
    }
}
