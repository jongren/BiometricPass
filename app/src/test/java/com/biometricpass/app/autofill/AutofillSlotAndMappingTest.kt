package com.biometricpass.app.autofill

import androidx.biometric.BiometricPrompt
import com.biometricpass.app.crypto.BiometricAuthHelper
import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.ui.autofill.PickerViewModel
import com.biometricpass.app.util.AppSignatures
import com.biometricpass.app.util.PublicSuffixListTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutofillSlotAndMappingTest {

    @Test
    fun `api29 slot state advances independently per category and caps at max slot`() {
        val session = AutofillSlotProvider.newSessionToken()
        assertEquals(0, AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_CREDENTIAL))
        assertEquals(0, AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_PICKER))
        assertEquals(0, AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR))

        AutofillSlotProvider.advanceSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR)
        AutofillSlotProvider.advanceSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR)

        assertEquals(0, AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_CREDENTIAL))
        assertEquals(2, AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR))

        repeat(20) {
            AutofillSlotProvider.advanceSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR)
        }
        assertEquals(
            AutofillSlotProvider.MAX_SLOTS_API29 - 1,
            AutofillSlotProvider.getActiveSlot(session, AutofillSlotProvider.CATEGORY_GENERATOR)
        )
    }

    @Test
    fun `evicting old sessions preserves recent active sessions`() {
        val oldSession = AutofillSlotProvider.newSessionToken()
        AutofillSlotProvider.advanceSlot(oldSession, AutofillSlotProvider.CATEGORY_CREDENTIAL)

        // Create 19 more sessions -> oldSession is still within the 20-session window
        val recentSessions = (1..19).map {
            AutofillSlotProvider.newSessionToken().also { token ->
                AutofillSlotProvider.advanceSlot(token, AutofillSlotProvider.CATEGORY_PICKER)
            }
        }
        assertEquals(1, AutofillSlotProvider.getActiveSlot(oldSession, AutofillSlotProvider.CATEGORY_CREDENTIAL))

        // 21st session evicts oldSession, while all 19 recentSessions stay intact!
        AutofillSlotProvider.newSessionToken()
        assertEquals(0, AutofillSlotProvider.getActiveSlot(oldSession, AutofillSlotProvider.CATEGORY_CREDENTIAL))
        for (token in recentSessions) {
            assertEquals(1, AutofillSlotProvider.getActiveSlot(token, AutofillSlotProvider.CATEGORY_PICKER))
        }
    }

    @Test
    fun `credential field mapping populates username, password and companion fallback`() {
        val standard = AutofillDatasetFactory.mapCredentialFillValues(
            usernameId = "u",
            passwordId = "p",
            focusedId = "u",
            editableIds = listOf("u", "p"),
            username = "alice",
            password = "secret"
        )
        assertEquals(mapOf("u" to "alice", "p" to "secret"), standard)

        // Unlabeled username field right before password field
        val fallback = AutofillDatasetFactory.mapCredentialFillValues(
            usernameId = null,
            passwordId = "p",
            focusedId = "p",
            editableIds = listOf("field_before", "p"),
            username = "bob",
            password = "pw"
        )
        assertEquals(mapOf("p" to "pw", "field_before" to "bob"), fallback)

        // Blank username keeps null value for usernameId so passwordId still fills cleanly
        val blankUser = AutofillDatasetFactory.mapCredentialFillValues(
            usernameId = "u",
            passwordId = "p",
            focusedId = null,
            editableIds = listOf("u", "p"),
            username = "",
            password = "pw"
        )
        assertNull(blankUser["u"])
        assertEquals("pw", blankUser["p"])
    }

    @Test
    fun `generated field mapping fills username, password and distinct focused field`() {
        val mapped = AutofillDatasetFactory.mapGeneratedFillValues(
            usernameId = "u",
            passwordId = "p1",
            focusedId = "p2_confirm",
            username = "alice",
            password = "GenPass!23"
        )
        assertEquals(
            mapOf("u" to "alice", "p1" to "GenPass!23", "p2_confirm" to "GenPass!23"),
            mapped
        )
    }

    @Test
    fun `picker target matching enforces browser vs webview isolation and psl boundaries`() {
        val psl = PublicSuffixListTest.bundled
        val googleWeb = CredentialEntity(
            id = 1L,
            title = "Google",
            packageName = "com.android.chrome",
            webDomain = "accounts.google.com",
            username = "alice@gmail.com",
            encryptedPassword = "c",
            iv = "i",
            updatedAt = 1L
        )
        val bankApp = googleWeb.copy(id = 2L, title = "Bank", packageName = "com.bank.app", webDomain = "")
        val githubSite = googleWeb.copy(id = 3L, title = "Pages", webDomain = "alice.github.io")

        // In Chrome: matches parent/sub-domain of google.com, rejects sibling sub-domains and never crosses github.io
        assertTrue(PickerViewModel.credentialMatchesTarget(googleWeb, "com.android.chrome", "google.com", isBrowser = true, psl = psl))
        assertFalse(PickerViewModel.credentialMatchesTarget(googleWeb, "com.android.chrome", "mail.google.com", isBrowser = true, psl = psl))
        assertFalse(PickerViewModel.credentialMatchesTarget(githubSite, "com.android.chrome", "bob.github.io", isBrowser = true, psl = psl))

        // In non-browser app embedding a WebView claiming accounts.google.com: NEVER matches googleWeb!
        assertFalse(PickerViewModel.credentialMatchesTarget(googleWeb, "com.evil.webview", "accounts.google.com", isBrowser = false, psl = psl))
        assertTrue(PickerViewModel.credentialMatchesTarget(bankApp, "com.bank.app", "", isBrowser = false, psl = psl))

        // Signature-pinned bank item matches authentic cert, but is demoted out of Suggested on counterfeit cert!
        val pinnedBank = bankApp.copy(appSignatureSha256 = "aaaa")
        assertTrue(
            PickerViewModel.credentialMatchesTarget(
                pinnedBank,
                "com.bank.app",
                "",
                isBrowser = false,
                psl = psl,
                liveTargetSignatures = AppSignatures.ofSingle("aaaa")
            )
        )
        assertFalse(
            PickerViewModel.credentialMatchesTarget(
                pinnedBank,
                "com.bank.app",
                "",
                isBrowser = false,
                psl = psl,
                liveTargetSignatures = AppSignatures.ofSingle("bbbb")
            )
        )
    }

    @Test
    fun `biometric fallback triggers for missing hardware or sensor lockout but never on user cancel`() {
        assertTrue(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_HW_NOT_PRESENT))
        assertTrue(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_HW_UNAVAILABLE))
        assertTrue(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_NO_BIOMETRICS))
        assertTrue(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_LOCKOUT))
        assertTrue(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_LOCKOUT_PERMANENT))

        assertFalse(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_USER_CANCELED))
        assertFalse(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_NEGATIVE_BUTTON))
        assertFalse(BiometricAuthHelper.shouldFallbackToDeviceCredential(BiometricPrompt.ERROR_CANCELED))
    }
}
