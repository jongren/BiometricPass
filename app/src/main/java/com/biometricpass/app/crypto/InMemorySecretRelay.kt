package com.biometricpass.app.crypto

import android.os.SystemClock
import com.biometricpass.app.autofill.CapturedFormField
import com.biometricpass.app.util.MaskedIdentifierHelper
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKey

/**
 * In-process memory-only relay so plaintext passwords, multi-field form captures, recent unmasked
 * usernames, and unlocked keys NEVER cross Android `Intent` extras, `PendingIntent` records,
 * `Bundle` saved states, or disk.
 */
object InMemorySecretRelay {

    private const val SAVE_TOKEN_TTL_MS = 90_000L
    private const val RECENT_INPUT_TTL_MS = 300_000L

    data class PendingSavePayload(
        val password: String,
        val secondaryUsername: String = "",
        val secondaryLabel: String = "",
        val isPinLike: Boolean = false,
        val capturedFields: List<CapturedFormField> = emptyList()
    )

    private data class TimedPayload(
        val payload: PendingSavePayload,
        val expiresAtElapsedMs: Long
    )

    private data class TimedInputs(
        val values: List<String>,
        val expiresAtElapsedMs: Long
    )

    @Volatile
    var activeVaultDek: SecretKey? = null
        private set

    private val pendingSavePayloads = ConcurrentHashMap<String, TimedPayload>()
    private val recentUnmaskedInputsByTarget = ConcurrentHashMap<String, TimedInputs>()

    fun setVaultDek(dek: SecretKey?) {
        activeVaultDek = dek
    }

    fun clearVaultDek() {
        activeVaultDek = null
        recentUnmaskedInputsByTarget.clear()
    }

    /**
     * Stores a newly typed password (and any extra form fields) captured by `PassAutofillService.onSaveRequest`
     * in process memory and returns an opaque random token safe to pass in a `PendingIntent` extra.
     */
    fun putPendingSavePayload(payload: PendingSavePayload): String {
        purgeExpired()
        val token = UUID.randomUUID().toString()
        pendingSavePayloads[token] = TimedPayload(
            payload = payload,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + SAVE_TOKEN_TTL_MS
        )
        return token
    }

    fun putPendingSavePassword(plainPassword: String): String =
        putPendingSavePayload(PendingSavePayload(password = plainPassword))

    /**
     * Consumes and immediately removes the pending payload associated with [token].
     */
    fun consumePendingSavePayload(token: String?): PendingSavePayload? {
        if (token.isNullOrBlank()) return null
        purgeExpired()
        val entry = pendingSavePayloads.remove(token) ?: return null
        return if (SystemClock.elapsedRealtime() <= entry.expiresAtElapsedMs) entry.payload else null
    }

    fun consumePendingSavePassword(token: String?): String =
        consumePendingSavePayload(token)?.password.orEmpty()

    /**
     * Records unmasked account / User ID inputs observed during `onFillRequest` or Autofill fill
     * strictly in RAM (5-minute TTL). If the target App masks the input box with `*` right when the
     * user taps "Login" (before `onSaveRequest` fires), BiometricPass recovers the unmasked text.
     */
    fun recordRecentUnmaskedInputs(
        packageName: String,
        webDomain: String,
        values: List<String>
    ) {
        val cleanValues = values
            .map { it.trim() }
            .filter { it.isNotEmpty() && !MaskedIdentifierHelper.isMaskedIdentifier(it) }
            .distinct()
        if (cleanValues.isEmpty()) return
        val key = targetKey(packageName, webDomain)
        if (key == "|") return

        purgeExpired()
        val existing = recentUnmaskedInputsByTarget[key]?.values.orEmpty()
        val merged = (cleanValues + existing).distinct().take(8)
        recentUnmaskedInputsByTarget[key] = TimedInputs(
            values = merged,
            expiresAtElapsedMs = SystemClock.elapsedRealtime() + RECENT_INPUT_TTL_MS
        )
    }

    fun getRecentUnmaskedInputs(packageName: String, webDomain: String): List<String> {
        purgeExpired()
        val now = SystemClock.elapsedRealtime()
        val exactKey = targetKey(packageName, webDomain)
        val pkgOnlyKey = targetKey(packageName, "")
        val result = ArrayList<String>()
        recentUnmaskedInputsByTarget[exactKey]?.takeIf { now <= it.expiresAtElapsedMs }?.let {
            result.addAll(it.values)
        }
        if (pkgOnlyKey != exactKey) {
            recentUnmaskedInputsByTarget[pkgOnlyKey]?.takeIf { now <= it.expiresAtElapsedMs }?.let {
                for (v in it.values) if (v !in result) result.add(v)
            }
        }
        return result
    }

    private fun targetKey(packageName: String, webDomain: String): String =
        "${packageName.trim()}|${webDomain.trim().lowercase()}"

    private fun purgeExpired() {
        val now = SystemClock.elapsedRealtime()
        val saveIter = pendingSavePayloads.entries.iterator()
        while (saveIter.hasNext()) {
            if (now > saveIter.next().value.expiresAtElapsedMs) {
                saveIter.remove()
            }
        }
        val recentIter = recentUnmaskedInputsByTarget.entries.iterator()
        while (recentIter.hasNext()) {
            if (now > recentIter.next().value.expiresAtElapsedMs) {
                recentIter.remove()
            }
        }
    }
}
