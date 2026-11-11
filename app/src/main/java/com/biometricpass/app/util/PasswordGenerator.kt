package com.biometricpass.app.util

import java.security.SecureRandom

/**
 * Cryptographically secure random password generator supporting customizable length (8..32)
 * and character sets (uppercase, lowercase, digits, symbols).
 */
object PasswordGenerator {

    private const val UPPERCASE = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWERCASE = "abcdefghijkmnopqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SYMBOLS = "!@#$%^&*()-_=+[]{}?"

    private val secureRandom = SecureRandom()

    fun generate(
        length: Int = 16,
        useUppercase: Boolean = true,
        useLowercase: Boolean = true,
        useDigits: Boolean = true,
        useSymbols: Boolean = true
    ): String {
        val targetLength = length.coerceIn(8, 32)
        val activePools = mutableListOf<String>()
        if (useUppercase) activePools.add(UPPERCASE)
        if (useLowercase) activePools.add(LOWERCASE)
        if (useDigits) activePools.add(DIGITS)
        if (useSymbols) activePools.add(SYMBOLS)

        if (activePools.isEmpty()) {
            activePools.add(LOWERCASE)
            activePools.add(DIGITS)
        }

        val combinedPool = activePools.joinToString("")
        val passwordChars = ArrayList<Char>(targetLength)

        // Guarantee at least one character from each selected character set
        for (pool in activePools) {
            if (passwordChars.size < targetLength) {
                passwordChars.add(pool[secureRandom.nextInt(pool.length)])
            }
        }

        // Fill remaining length from combined pool
        while (passwordChars.size < targetLength) {
            passwordChars.add(combinedPool[secureRandom.nextInt(combinedPool.length)])
        }

        // Fisher-Yates shuffle using SecureRandom
        for (i in passwordChars.lastIndex downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val temp = passwordChars[i]
            passwordChars[i] = passwordChars[j]
            passwordChars[j] = temp
        }

        return passwordChars.joinToString("")
    }
}
