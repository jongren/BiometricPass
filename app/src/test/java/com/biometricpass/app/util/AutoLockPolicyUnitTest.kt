package com.biometricpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPolicyUnitTest {

    @Test
    fun `locks immediately when configured for zero seconds`() {
        assertTrue(
            AppSettings.shouldAutoLockAfterBackground(
                backgroundedAtElapsedMs = 10_000L,
                nowElapsedMs = 10_050L,
                autoLockSeconds = 0,
                hasExternalUiGrace = false
            )
        )
    }

    @Test
    fun `does not lock before configured timeout`() {
        assertFalse(
            AppSettings.shouldAutoLockAfterBackground(
                backgroundedAtElapsedMs = 10_000L,
                nowElapsedMs = 69_999L,
                autoLockSeconds = 60,
                hasExternalUiGrace = false
            )
        )
    }

    @Test
    fun `locks at or after configured timeout`() {
        assertTrue(
            AppSettings.shouldAutoLockAfterBackground(
                backgroundedAtElapsedMs = 10_000L,
                nowElapsedMs = 70_000L,
                autoLockSeconds = 60,
                hasExternalUiGrace = false
            )
        )
    }

    @Test
    fun `external saf or share grace skips auto-lock once`() {
        assertFalse(
            AppSettings.shouldAutoLockAfterBackground(
                backgroundedAtElapsedMs = 10_000L,
                nowElapsedMs = 500_000L,
                autoLockSeconds = 0,
                hasExternalUiGrace = true
            )
        )
    }

    @Test
    fun `background wipe timer uses the configured auto-lock delay`() {
        assertEquals(0L, AppSettings.backgroundLockDelayMs(autoLockSeconds = 0, remainingGraceMs = 0L))
        assertEquals(60_000L, AppSettings.backgroundLockDelayMs(autoLockSeconds = 60, remainingGraceMs = 0L))
        assertEquals(300_000L, AppSettings.backgroundLockDelayMs(autoLockSeconds = 300, remainingGraceMs = 0L))
    }

    @Test
    fun `background wipe timer waits for the external ui grace period`() {
        assertEquals(
            540_000L,
            AppSettings.backgroundLockDelayMs(autoLockSeconds = 0, remainingGraceMs = 540_000L)
        )
    }
}
