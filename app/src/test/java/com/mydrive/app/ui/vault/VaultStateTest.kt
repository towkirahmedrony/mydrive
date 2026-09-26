package com.mydrive.app.ui.vault

import com.mydrive.app.data.vault.VaultSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM cover for the vault presentation contract.
 *
 * The important rule is structural: media may only be represented in the
 * [VaultViewModel.Phase.UNLOCKED] state. Everything else — locked, authenticating,
 * setup, locking — must expose an empty item list, so the locked screen has nothing
 * it *could* render behind the biometric prompt or PIN pad.
 */
class VaultStateTest {

    @Test
    fun `a fresh vault state carries no media and is not unlocked`() {
        val state = VaultViewModel.VaultUiState()
        assertFalse(state.isUnlocked)
        assertTrue("items must be empty by default", state.items.isEmpty())
        assertEquals(VaultViewModel.Phase.SETUP_PIN, state.phase)
    }

    @Test
    fun `only the unlocked phase reports an unlocked session`() {
        VaultViewModel.Phase.entries.forEach { phase ->
            val state = VaultViewModel.VaultUiState(phase = phase)
            assertEquals(
                "phase $phase",
                phase == VaultViewModel.Phase.UNLOCKED,
                state.isUnlocked
            )
        }
    }

    @Test
    fun `the locked and authenticating phases are distinct security states`() {
        assertTrue(VaultViewModel.Phase.entries.contains(VaultViewModel.Phase.LOCKED))
        assertTrue(VaultViewModel.Phase.entries.contains(VaultViewModel.Phase.AUTHENTICATING))
        assertTrue(VaultViewModel.Phase.entries.contains(VaultViewModel.Phase.UNLOCKED))
        assertTrue(VaultViewModel.Phase.entries.contains(VaultViewModel.Phase.LOCKING))
    }

    @Test
    fun `lock timeout options are valid and include the default`() {
        val options = VaultSettingsViewModel.LOCK_TIMEOUT_OPTIONS
        assertTrue(options.isNotEmpty())
        options.forEach { option ->
            assertTrue("timeout in schema range: ${option.seconds}", option.seconds in 0..3600)
            assertTrue("label present", option.label.isNotBlank())
        }
        assertTrue(options.any { it.seconds == VaultSession.DEFAULT_TIMEOUT_SECONDS })
    }
}
