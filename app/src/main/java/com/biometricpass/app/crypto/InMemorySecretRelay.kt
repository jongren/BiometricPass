package com.biometricpass.app.crypto

import android.os.SystemClock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * In-process memory-only relay so plaintext passwords and unlocked keys NEVER cross Android
 * `Intent` extras, `PendingIntent` records, `Bundle` saved states, or disk.
 */
object InMemorySecretRelay {

    private const val SAVE_TOKEN_TTL_MS = 90_000L

    private data class TimedSecret(
        val value: String,
        val expiresAtElapsedMs: Long
    )

    @Volatile
    var activeVaultDek: SecretKey? = null
        private set

    private val pendingSaveSecrets = ConcurrentHashMap<String, TimedSecret>()

    fun setVaultDek(dek: SecretKey?) {
        activeVaultDek = dek
    }

    fun clearVaultDek() {
        activeVaultDek = null
    }

    /**
     * Stores a newly typed password captured by `PassAutofillService.onSaveRequest` in process memory
     * and returns an opaque random token safe to pass in a `PendingIntent` extra.
     */
    fun putPendingSavePassword(plainPassword: String): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        pendingSaveSecrets[token] = TimedSecret(
            value = plainPassword,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + SAVE_TOKEN_TTL_MS
        )
        return token
    }

    /**
     * Consumes and immediately removes the pending password associated with [token].
     */
    fun consumePendingSavePassword(token: String?): String {
        if (token.isNullOrBlank()) return ""
        purgeExpired()
        val entry = pendingSaveSecrets.remove(token) ?: return ""
        return if (SystemClock.elapsedRealtime() <= entry.expiresAtElapsedMs) entry.value else ""
    }

    private fun purgeExpired() {
        val now = SystemClock.elapsedRealtime()
        val iterator = pendingSaveSecrets.entries.iterator()
        while (iterator.hasNext()) {
            if (now > iterator.next().value.expiresAtElapsedMs) {
                iterator.remove()
            }
        }
    }
}
