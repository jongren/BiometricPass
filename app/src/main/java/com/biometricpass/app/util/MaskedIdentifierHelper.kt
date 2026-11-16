package com.biometricpass.app.util

import java.util.regex.Pattern

/**
 * Pure Kotlin helper for detecting and recovering identifiers masked with `*` / `●` / `•` by
 * banking, wallet, or telecom apps right upon form submission (e.g. `A123***789`, `jon***@gmail.com`,
 * `0912-***-678`, `****5678`), as well as identifying PIN-style numeric secrets.
 */
object MaskedIdentifierHelper {

    private val MASK_CHARS: Set<Char> = setOf(
        '*', '＊', '•', '●', '▪', '◦', '✕', '✖', '✱', '﹡'
    )

    private val FORMATTING_SEPARATORS: Set<Char> = setOf('-', ' ', '_')

    /**
     * Returns `true` when [text] looks like a redacted / partially masked account identifier
     * rather than a real complete username.
     */
    fun isMaskedIdentifier(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false

        val maskCount = trimmed.count { it in MASK_CHARS }
        if (maskCount >= 2) return true

        // Single mask symbol inside a compact single-token identifier (e.g. "a*c@gmail.com")
        if (maskCount == 1 && trimmed.length >= 3 && !trimmed.contains(' ')) {
            return true
        }
        return false
    }

    /**
     * Returns `true` when [candidateUnmasked] is a complete, unmasked identifier that matches
     * the visible prefix/infix/suffix pattern of [masked].
     *
     * Requires at least 2 visible non-separator characters in [masked] so a completely hidden
     * string like `"******"` never blindly matches an unrelated account.
     */
    fun matchesUnmasked(masked: String, candidateUnmasked: String): Boolean {
        val cleanMasked = masked.trim()
        val cleanCandidate = candidateUnmasked.trim()
        if (cleanMasked.isEmpty() || cleanCandidate.isEmpty()) return false
        if (!isMaskedIdentifier(cleanMasked) || isMaskedIdentifier(cleanCandidate)) return false

        if (matchesLiteralPattern(cleanMasked, cleanCandidate)) return true

        // Also support apps that insert formatting dashes when masking phone/card numbers
        // (e.g. "0912345678" masked on screen as "0912-***-678").
        val strippedMasked = cleanMasked.filterNot { it in FORMATTING_SEPARATORS }
        val strippedCandidate = cleanCandidate.filterNot { it in FORMATTING_SEPARATORS }
        if (strippedMasked != cleanMasked || strippedCandidate != cleanCandidate) {
            return matchesLiteralPattern(strippedMasked, strippedCandidate)
        }
        return false
    }

    private fun matchesLiteralPattern(masked: String, candidate: String): Boolean {
        val visibleCount = masked.count { it !in MASK_CHARS && it !in FORMATTING_SEPARATORS }
        if (visibleCount < 2) return false

        val regexBuilder = StringBuilder("^")
        var i = 0
        while (i < masked.length) {
            if (masked[i] in MASK_CHARS) {
                while (i < masked.length && masked[i] in MASK_CHARS) {
                    i++
                }
                regexBuilder.append(".+")
            } else {
                val start = i
                while (i < masked.length && masked[i] !in MASK_CHARS) {
                    i++
                }
                regexBuilder.append(Pattern.quote(masked.substring(start, i)))
            }
        }
        regexBuilder.append("$")
        return Pattern.compile(regexBuilder.toString(), Pattern.CASE_INSENSITIVE)
            .matcher(candidate)
            .matches()
    }

    /**
     * Resolves the best unmasked username from:
     * 1. [candidatesNewestFirst] — if any `FillContext` in the session still holds an unmasked value,
     *    prefer it over a later context where the app replaced the input with `*` on submit.
     * 2. [cachedUnmasked] — short-lived RAM values seen moments earlier in `onFillRequest` or Autofill fill.
     * 3. [vaultUnmasked] — existing unmasked usernames already saved for the same App/domain (only when
     *    uniquely 1 distinct account matches the masked pattern).
     * 4. Falls back to the first non-blank candidate so the UI can warn the user about the `*` mask.
     */
    fun resolveBestUsername(
        candidatesNewestFirst: List<String>,
        cachedUnmasked: List<String> = emptyList(),
        vaultUnmasked: List<String> = emptyList()
    ): String {
        val nonBlank = candidatesNewestFirst.map { it.trim() }.filter { it.isNotEmpty() }
        if (nonBlank.isEmpty()) return ""

        // 1. Prefer an unmasked candidate directly from the session's FillContexts
        nonBlank.firstOrNull { !isMaskedIdentifier(it) }?.let { return it }

        val masked = nonBlank.first()

        // 2. Recover from recent in-memory FillRequest / Autofill fill cache
        cachedUnmasked
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !isMaskedIdentifier(it) && matchesUnmasked(masked, it) }
            ?.let { return it }

        // 3. Recover from existing Vault accounts for the same App/website when unambiguous
        val matchingVaultUsers = vaultUnmasked
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isMaskedIdentifier(it) && matchesUnmasked(masked, it) }
            .distinctBy { it.lowercase() }
        if (matchingVaultUsers.size == 1) {
            return matchingVaultUsers.single()
        }

        return masked
    }

    /**
     * Heuristic identifying a 4–8 digit PIN or explicit numeric-password secret.
     */
    fun isPinLikeSecret(secret: String, isNumericPasswordInput: Boolean = false): Boolean {
        if (isNumericPasswordInput) return true
        val trimmed = secret.trim()
        return trimmed.length in 4..8 && trimmed.all { it in '0'..'9' }
    }
}
