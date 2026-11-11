package com.biometricpass.app.data.repository

import com.biometricpass.app.data.local.CredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMatchRulesTest {

    private fun cred(id: Long, username: String, pkg: String = "jp.naver.line.android") =
        CredentialEntity(
            id = id,
            title = "LINE",
            packageName = pkg,
            webDomain = "",
            username = username,
            encryptedPassword = "x",
            iv = "y",
            updatedAt = id
        )

    @Test
    fun `saving a different username for the same app creates a new record`() {
        val existing = listOf(cred(1, "alice"))
        assertNull(CredentialMatchRules.pickExistingForSave(existing, "bob"))
    }

    @Test
    fun `saving the same username updates it case-insensitively`() {
        val existing = listOf(cred(1, "Alice@Mail.com"), cred(2, "bob"))
        assertEquals(1L, CredentialMatchRules.pickExistingForSave(existing, " alice@mail.com ")?.id)
    }

    @Test
    fun `a single blank-username record is upgraded in place`() {
        val existing = listOf(cred(1, ""), cred(2, "bob"))
        assertEquals(1L, CredentialMatchRules.pickExistingForSave(existing, "alice")?.id)
    }

    @Test
    fun `password-only save updates the only account`() {
        assertEquals(1L, CredentialMatchRules.pickExistingForSave(listOf(cred(1, "alice")), "")?.id)
    }

    @Test
    fun `password-only save with several accounts is ambiguous and creates a new record`() {
        val existing = listOf(cred(1, "alice"), cred(2, "bob"))
        assertNull(CredentialMatchRules.pickExistingForSave(existing, ""))
    }

    @Test
    fun `no existing records means new record`() {
        assertNull(CredentialMatchRules.pickExistingForSave(emptyList(), "alice"))
    }

    @Test
    fun `only true duplicates are removed`() {
        val saved = cred(3, "alice")
        val matches = listOf(cred(1, "ALICE"), cred(2, "bob"), cred(4, ""), saved)
        assertEquals(listOf(1L), CredentialMatchRules.duplicateIdsToRemove(matches, saved))
    }

    @Test
    fun `blank-username save never removes anything`() {
        val saved = cred(3, "")
        val matches = listOf(cred(1, ""), cred(2, "bob"), saved)
        assertTrue(CredentialMatchRules.duplicateIdsToRemove(matches, saved).isEmpty())
    }

    @Test
    fun `import key distinguishes accounts but ignores case and whitespace`() {
        assertEquals(
            CredentialMatchRules.importKey("pkg", "Example.com", " Alice "),
            CredentialMatchRules.importKey("pkg", "example.com", "alice")
        )
        assertTrue(
            CredentialMatchRules.importKey("pkg", "", "alice") !=
                CredentialMatchRules.importKey("pkg", "", "bob")
        )
    }
}
