package com.biometricpass.app.data.repository

import com.biometricpass.app.data.local.CredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialMatchRulesTest {

    private fun cred(
        id: Long,
        username: String,
        pkg: String = "jp.naver.line.android",
        title: String = "LINE"
    ) = CredentialEntity(
        id = id,
        title = title,
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
    fun `a single generic blank-username record is upgraded in place, but PIN records are protected`() {
        val genericBlank = listOf(cred(1, "", title = "LINE"), cred(2, "bob", title = "LINE"))
        assertEquals(1L, CredentialMatchRules.pickExistingForSave(genericBlank, "alice")?.id)

        val pinRecord = listOf(cred(1, "", title = "三星錢包 - PIN 碼"), cred(2, "bob", title = "三星錢包"))
        assertNull(
            "Saving a login account must NEVER overwrite a standalone PIN record",
            CredentialMatchRules.pickExistingForSave(pinRecord, "alice")
        )
    }

    @Test
    fun `password-only or PIN save NEVER overwrites an existing account that has a username`() {
        // Exact Samsung Wallet scenario: user saved Samsung login ("alice"), then sets 6-digit Wallet PIN ("")
        assertNull(
            CredentialMatchRules.pickExistingForSave(
                siteMatches = listOf(cred(1, "alice", pkg = "com.samsung.android.spay", title = "三星錢包")),
                username = "",
                proposedTitle = "三星錢包 - PIN 碼"
            )
        )
        assertNull(
            CredentialMatchRules.pickExistingForSave(
                siteMatches = listOf(cred(1, "alice", pkg = "com.samsung.android.spay", title = "三星錢包")),
                username = ""
            )
        )

        // Updating a standalone blank-username record with the exact same title matches cleanly
        val standalonePin = listOf(cred(10, "", pkg = "com.samsung.android.spay", title = "三星錢包 - PIN 碼"))
        assertEquals(
            10L,
            CredentialMatchRules.pickExistingForSave(
                siteMatches = standalonePin,
                username = "",
                proposedTitle = "三星錢包 - PIN 碼"
            )?.id
        )
    }

    @Test
    fun `password-only save with several accounts creates a new record`() {
        val existing = listOf(cred(1, "alice"), cred(2, "bob"))
        assertNull(CredentialMatchRules.pickExistingForSave(existing, ""))
    }

    @Test
    fun `masked username matches unique unmasked account and never corrupts blank records`() {
        val bankCreds = listOf(cred(1, "A123456789", pkg = "com.bank", title = "MyBank"))
        assertEquals(
            1L,
            CredentialMatchRules.pickExistingForSave(bankCreds, "A123***789")?.id
        )

        // Never upgrades a blank-username record into a masked string
        val blankOnly = listOf(cred(2, "", pkg = "com.bank", title = "MyBank"))
        assertNull(CredentialMatchRules.pickExistingForSave(blankOnly, "A123***789"))
    }

    @Test
    fun `no existing records means new record`() {
        assertNull(CredentialMatchRules.pickExistingForSave(emptyList(), "alice"))
    }

    @Test
    fun `only true unmasked duplicates are removed`() {
        val saved = cred(3, "alice")
        val matches = listOf(cred(1, "ALICE"), cred(2, "bob"), cred(4, ""), saved)
        assertEquals(listOf(1L), CredentialMatchRules.duplicateIdsToRemove(matches, saved))

        val maskedSaved = cred(5, "ali***")
        assertTrue(CredentialMatchRules.duplicateIdsToRemove(matches, maskedSaved).isEmpty())
    }

    @Test
    fun `blank-username save never removes anything`() {
        val saved = cred(3, "")
        val matches = listOf(cred(1, ""), cred(2, "bob"), saved)
        assertTrue(CredentialMatchRules.duplicateIdsToRemove(matches, saved).isEmpty())
    }

    @Test
    fun `import key distinguishes accounts and disambiguates blank-username PINs by title`() {
        assertEquals(
            CredentialMatchRules.importKey("pkg", "Example.com", " Alice ", "Title A"),
            CredentialMatchRules.importKey("pkg", "example.com", "alice", "Title B")
        )
        assertNotEquals(
            CredentialMatchRules.importKey("pkg", "", "alice"),
            CredentialMatchRules.importKey("pkg", "", "bob")
        )
        assertNotEquals(
            CredentialMatchRules.importKey("com.samsung.android.spay", "", "", "三星錢包 - PIN 碼"),
            CredentialMatchRules.importKey("com.samsung.android.spay", "", "", "三星錢包 - 交易密碼")
        )
    }
}
