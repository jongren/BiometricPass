package com.biometricpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskedIdentifierHelperTest {

    @Test
    fun `detects masked identifiers across common bank and wallet masking characters`() {
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("A123***789"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("A123●●●789"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("A123•••789"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("jon***@gmail.com"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("a*c@gmail.com"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("0912-***-678"))
        assertTrue(MaskedIdentifierHelper.isMaskedIdentifier("****5678"))

        assertFalse(MaskedIdentifierHelper.isMaskedIdentifier(""))
        assertFalse(MaskedIdentifierHelper.isMaskedIdentifier("A123456789"))
        assertFalse(MaskedIdentifierHelper.isMaskedIdentifier("jongren@gmail.com"))
        assertFalse(MaskedIdentifierHelper.isMaskedIdentifier("alex_chen-88"))
    }

    @Test
    fun `matches masked identifiers against full unmasked accounts accurately`() {
        assertTrue(MaskedIdentifierHelper.matchesUnmasked("A123***789", "A123456789"))
        assertTrue(MaskedIdentifierHelper.matchesUnmasked("a123***789", "A123456789"))
        assertTrue(MaskedIdentifierHelper.matchesUnmasked("jon***@gmail.com", "jongren@gmail.com"))
        assertTrue(MaskedIdentifierHelper.matchesUnmasked("0912-***-678", "0912345678"))
        assertTrue(MaskedIdentifierHelper.matchesUnmasked("****5678", "0912345678"))

        // Rejects unrelated accounts and fully redacted masks with < 2 visible chars
        assertFalse(MaskedIdentifierHelper.matchesUnmasked("A123***789", "B987654321"))
        assertFalse(MaskedIdentifierHelper.matchesUnmasked("******", "A123456789"))
        assertFalse(MaskedIdentifierHelper.matchesUnmasked("A*****", "A123456789"))
        assertFalse(MaskedIdentifierHelper.matchesUnmasked("A123***789", "A123***789"))
    }

    @Test
    fun `resolveBestUsername prioritizes unmasked context, then RAM cache, then unique vault account`() {
        // 1. Earlier FillContext still has unmasked value while latest was masked on submit
        assertEquals(
            "A123456789",
            MaskedIdentifierHelper.resolveBestUsername(
                candidatesNewestFirst = listOf("A123***789", "A123456789")
            )
        )

        // 2. Only masked in FillContexts, recovered from recent RAM cache
        assertEquals(
            "jongren@gmail.com",
            MaskedIdentifierHelper.resolveBestUsername(
                candidatesNewestFirst = listOf("jon***@gmail.com"),
                cachedUnmasked = listOf("jongren@gmail.com")
            )
        )

        // 3. Only masked in FillContexts, recovered from unique matching vault account
        assertEquals(
            "A123456789",
            MaskedIdentifierHelper.resolveBestUsername(
                candidatesNewestFirst = listOf("A123***789"),
                vaultUnmasked = listOf("A123456789", "other_user")
            )
        )

        // 4. Ambiguous vault accounts (2 accounts match jon***@gmail.com) -> returns masked for user review
        assertEquals(
            "jon***@gmail.com",
            MaskedIdentifierHelper.resolveBestUsername(
                candidatesNewestFirst = listOf("jon***@gmail.com"),
                vaultUnmasked = listOf("jongren@gmail.com", "jonathan@gmail.com")
            )
        )
    }

    @Test
    fun `identifies 4 to 8 digit PIN secrets`() {
        assertTrue(MaskedIdentifierHelper.isPinLikeSecret("123456"))
        assertTrue(MaskedIdentifierHelper.isPinLikeSecret("0088"))
        assertFalse(MaskedIdentifierHelper.isPinLikeSecret("G9#xK2!mQ8@vL4"))
        assertTrue(MaskedIdentifierHelper.isPinLikeSecret("abc", isNumericPasswordInput = true))
    }
}
