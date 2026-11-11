package com.biometricpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordGeneratorTest {

    // Ambiguous glyphs excluded by PasswordGenerator (0/O and 1/I/l)
    private val lookAlikes = setOf('0', 'O', '1', 'l', 'I')

    @Test
    fun `clamps password length to 8 through 32`() {
        assertEquals(8, PasswordGenerator.generate(length = 3).length)
        assertEquals(16, PasswordGenerator.generate(length = 16).length)
        assertEquals(32, PasswordGenerator.generate(length = 64).length)
    }

    @Test
    fun `includes every enabled character class and excludes look-alike characters`() {
        repeat(100) {
            val pwd = PasswordGenerator.generate(
                length = 12,
                useUppercase = true,
                useLowercase = true,
                useDigits = true,
                useSymbols = true
            )
            assertEquals(12, pwd.length)
            assertTrue("Expected uppercase in $pwd", pwd.any { it.isUpperCase() })
            assertTrue("Expected lowercase in $pwd", pwd.any { it.isLowerCase() })
            assertTrue("Expected digit in $pwd", pwd.any { it.isDigit() })
            assertTrue("Expected symbol in $pwd", pwd.any { !it.isLetterOrDigit() })
            assertFalse("Unexpected look-alike char in $pwd", pwd.any { it in lookAlikes })
        }
    }

    @Test
    fun `falls back to lowercase and digits when all character sets are disabled`() {
        repeat(30) {
            val pwd = PasswordGenerator.generate(
                length = 10,
                useUppercase = false,
                useLowercase = false,
                useDigits = false,
                useSymbols = false
            )
            assertEquals(10, pwd.length)
            assertTrue(pwd.any { it.isLowerCase() })
            assertTrue(pwd.any { it.isDigit() })
            assertFalse(pwd.any { it.isUpperCase() || !it.isLetterOrDigit() })
        }
    }
}
