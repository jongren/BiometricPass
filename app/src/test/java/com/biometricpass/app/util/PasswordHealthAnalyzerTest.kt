package com.biometricpass.app.util

import com.biometricpass.app.data.local.CredentialEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHealthAnalyzerTest {

    private val now = 1_800_000_000_000L
    private val oneDayMs = 24L * 60L * 60L * 1000L

    private fun entity(id: Long, title: String, updatedAt: Long = now) = CredentialEntity(
        id = id,
        title = title,
        packageName = "com.app.$id",
        webDomain = "",
        username = "user$id",
        encryptedPassword = "enc",
        iv = "iv",
        updatedAt = updatedAt
    )

    @Test
    fun `detects reused, weak, and outdated passwords without retaining plaintext`() {
        val reusedSecret = "SuperStrongShared#99"
        val weakShort = "Ab1!"
        val weakSingleClass = "onlylowercaseletters"
        val healthySecret = "UniqueStrong@2026"

        val entries = listOf(
            entity(1, "Google") to reusedSecret,
            entity(2, "Facebook") to reusedSecret,
            entity(3, "ATM") to weakShort,
            entity(4, "Forum") to weakSingleClass,
            entity(5, "OldMail", updatedAt = now - 400 * oneDayMs) to "OldButStrong#2020",
            entity(6, "Bank") to healthySecret
        )

        val report = PasswordHealthAnalyzer.analyze(entries, nowMillis = now)

        assertEquals(6, report.totalCount)
        assertEquals(1, report.healthyCount)
        assertEquals(2, report.reusedItems.size)
        assertEquals(2, report.weakItems.size)
        assertEquals(1, report.outdatedItems.size)
        assertEquals(5L, report.outdatedItems.single().credentialId)

        // Ensure no plaintext password ever leaks into the report representation
        val serialized = report.toString()
        assertFalse(serialized.contains(reusedSecret))
        assertFalse(serialized.contains(weakSingleClass))
        assertFalse(serialized.contains(healthySecret))
    }

    @Test
    fun `all strong unique recent passwords produce a clean report`() {
        val report = PasswordHealthAnalyzer.analyze(
            entries = listOf(
                entity(1, "A") to "Alpha#928374!",
                entity(2, "B") to "Bravo$192837?"
            ),
            nowMillis = now
        )
        assertFalse(report.hasIssues)
        assertEquals(2, report.healthyCount)
    }
}
