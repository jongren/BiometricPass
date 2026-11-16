package com.biometricpass.app.data.repository

import com.biometricpass.app.data.local.CredentialEntity
import com.biometricpass.app.util.MaskedIdentifierHelper

/**
 * Pure (Android-free) matching rules deciding which existing credential a save should update.
 *
 * Kept free of Room / Context dependencies so the rules can be covered by plain JVM unit tests.
 *
 * Data-safety principles:
 * 1. A record is only ever updated when it is unambiguously the same account.
 *    Saving a *different* username for the same App / website ALWAYS creates a new record.
 * 2. A password-only / PIN-only screen (`username` blank) NEVER overwrites an existing account
 *    that has a non-blank `username` (preventing e.g. Samsung Wallet PIN setup from overwriting
 *    the user's Samsung Account login + generated high-strength password).
 * 3. When a target App masks the username on screen with `*` / `●` (e.g. `A123***789`),
 *    [pickExistingForSave] matches an existing unmasked account (`A123456789`) when unambiguous,
 *    and never downgrades a clean username into a masked one.
 * 4. Import / migration uses strict equality (disambiguating blank-username items by `title`).
 */
object CredentialMatchRules {

    private val SECONDARY_SECRET_TITLE_KEYWORDS = listOf(
        "pin", "交易密碼", "提款密碼", "支付密碼", "安全碼", "轉帳密碼", "passcode"
    )

    fun isSecondarySecretTitle(title: String): Boolean {
        val lower = title.trim().lowercase()
        if (lower.isEmpty()) return false
        return SECONDARY_SECRET_TITLE_KEYWORDS.any { lower.contains(it) }
    }

    /**
     * Chooses which of [siteMatches] (credentials already bound to the same App or website)
     * should be updated when saving [username]. Returns `null` when a new record must be created.
     */
    fun pickExistingForSave(
        siteMatches: List<CredentialEntity>,
        username: String,
        proposedTitle: String = ""
    ): CredentialEntity? {
        val cleanUser = username.trim()
        val cleanTitle = proposedTitle.trim()
        if (siteMatches.isEmpty()) return null

        if (cleanUser.isNotEmpty()) {
            // 1. Exact same account (case-insensitive username) -> update it
            siteMatches.firstOrNull { it.username.trim().equals(cleanUser, ignoreCase = true) }
                ?.let { return it }

            // 2. Masked username from App post-submit screen (e.g. "A123***789"):
            //    match existing unmasked account ("A123456789") ONLY when uniquely unambiguous,
            //    and never upgrade a blank-username record into a masked username.
            if (MaskedIdentifierHelper.isMaskedIdentifier(cleanUser)) {
                val unmaskedMatches = siteMatches.filter {
                    it.username.isNotBlank() &&
                        !MaskedIdentifierHelper.isMaskedIdentifier(it.username) &&
                        MaskedIdentifierHelper.matchesUnmasked(cleanUser, it.username)
                }
                return unmaskedMatches.singleOrNull()
            }

            // 3. Upgrade a single generic blank-username record saved earlier from a password-only
            //    step, EXCEPT when that blank-username record is an explicit PIN / secondary secret.
            val upgradeableBlankRecords = siteMatches.filter {
                it.username.isBlank() && !isSecondarySecretTitle(it.title) && !it.hasExtraFields
            }
            if (upgradeableBlankRecords.size == 1) {
                return upgradeableBlankRecords.first()
            }

            // Different account for the same site -> new record (NEVER overwrite another account)
            return null
        }

        // Password-only / PIN-only screen (no username visible):
        // CRITICAL SAFETY RULE: NEVER auto-match an existing record that has a non-blank username!
        // Only consider existing blank-username records with a matching title (or the sole blank-username
        // record when no title disambiguates it and no full accounts exist).
        val blankUserRecords = siteMatches.filter { it.username.isBlank() }
        if (blankUserRecords.isEmpty()) return null

        if (cleanTitle.isNotEmpty()) {
            blankUserRecords.firstOrNull { it.title.trim().equals(cleanTitle, ignoreCase = true) }
                ?.let { return it }
            if (isSecondarySecretTitle(cleanTitle)) {
                return null
            }
        }

        return if (siteMatches.size == 1 && blankUserRecords.size == 1) {
            blankUserRecords.single()
        } else {
            null
        }
    }

    /**
     * Returns the IDs of true duplicates of [saved] (same site and same non-blank, unmasked username)
     * that can safely be removed after a save. Records for other accounts or PINs are never returned.
     */
    fun duplicateIdsToRemove(
        siteMatches: List<CredentialEntity>,
        saved: CredentialEntity
    ): List<Long> {
        val savedUser = saved.username.trim()
        if (savedUser.isEmpty() || MaskedIdentifierHelper.isMaskedIdentifier(savedUser)) {
            return emptyList()
        }
        return siteMatches
            .filter { it.id != saved.id && it.username.trim().equals(savedUser, ignoreCase = true) }
            .map { it.id }
    }

    /**
     * Strict identity key used for backup import / device migration merging.
     * Blank-username items (such as Wallet PIN vs Transaction PIN under the same App) are
     * disambiguated by normalized `title` so they never overwrite one another on restore.
     */
    fun importKey(
        packageName: String,
        webDomain: String,
        username: String,
        title: String = ""
    ): String {
        val cleanUser = username.trim().lowercase()
        val accountPart = if (cleanUser.isEmpty()) {
            "#title:${title.trim().lowercase()}"
        } else {
            cleanUser
        }
        return "${packageName.trim()}|${webDomain.trim().lowercase()}|$accountPart"
    }
}
