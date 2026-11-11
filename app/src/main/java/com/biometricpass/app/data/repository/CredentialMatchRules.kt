package com.biometricpass.app.data.repository

import com.biometricpass.app.data.local.CredentialEntity

/**
 * Pure (Android-free) matching rules deciding which existing credential a save should update.
 *
 * Kept free of Room / Context dependencies so the rules can be covered by plain JVM unit tests.
 *
 * Data-safety principles:
 * 1. A record is only ever updated when it is unambiguously the same account.
 *    Saving a *different* username for the same App / website ALWAYS creates a new record.
 * 2. A blank-username record (saved earlier from a password-only screen) may be "upgraded"
 *    in place when the user later saves the same site with a username - nothing is deleted.
 * 3. Import / migration uses strict equality only and never merges fuzzy matches.
 */
object CredentialMatchRules {

    /**
     * Chooses which of [siteMatches] (credentials already bound to the same App or website)
     * should be updated when saving [username]. Returns `null` when a new record must be created.
     */
    fun pickExistingForSave(
        siteMatches: List<CredentialEntity>,
        username: String
    ): CredentialEntity? {
        val cleanUser = username.trim()
        if (siteMatches.isEmpty()) return null

        if (cleanUser.isNotEmpty()) {
            // Same account (case-insensitive username) -> update it
            siteMatches.firstOrNull { it.username.trim().equals(cleanUser, ignoreCase = true) }
                ?.let { return it }
            // Upgrade a single blank-username record saved from a password-only screen
            val blankUserRecords = siteMatches.filter { it.username.isBlank() }
            if (blankUserRecords.size == 1) return blankUserRecords.first()
            // Different account for the same site -> new record (NEVER overwrite another account)
            return null
        }

        // Password-only screen (no username visible): only update when there is exactly
        // one account for this site, otherwise the target is ambiguous -> new record.
        return siteMatches.singleOrNull()
    }

    /**
     * Returns the IDs of true duplicates of [saved] (same site and same non-blank username)
     * that can safely be removed after a save. Records for other accounts are never returned.
     */
    fun duplicateIdsToRemove(
        siteMatches: List<CredentialEntity>,
        saved: CredentialEntity
    ): List<Long> {
        val savedUser = saved.username.trim()
        if (savedUser.isEmpty()) return emptyList()
        return siteMatches
            .filter { it.id != saved.id && it.username.trim().equals(savedUser, ignoreCase = true) }
            .map { it.id }
    }

    /**
     * Strict identity key used for backup import / device migration merging.
     */
    fun importKey(packageName: String, webDomain: String, username: String): String =
        "${packageName.trim()}|${webDomain.trim().lowercase()}|${username.trim().lowercase()}"
}
