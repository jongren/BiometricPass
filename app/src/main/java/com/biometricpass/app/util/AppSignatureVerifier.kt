package com.biometricpass.app.util

import java.security.MessageDigest

/**
 * Signing-certificate digests resolved for an installed Android package.
 *
 * - [primarySha256]: canonical lowercase hex SHA-256 of the current primary signing certificate
 *   (stored in `CredentialEntity.appSignatureSha256` when binding a credential to a native App).
 * - [trustedSha256Set]: all lowercase hex SHA-256 digests trusted for this installed package,
 *   including both `apkContentsSigners` and APK Signature Scheme v3 `signingCertificateHistory`
 *   so legitimate developer key rotations continue matching seamlessly.
 */
data class AppSignatures(
    val primarySha256: String,
    val trustedSha256Set: Set<String>
) {
    companion object {
        fun ofSingle(sha256: String): AppSignatures {
            val normalized = sha256.trim().lowercase()
            return AppSignatures(
                primarySha256 = normalized,
                trustedSha256Set = if (normalized.isEmpty()) emptySet() else setOf(normalized)
            )
        }
    }
}

/**
 * Pure JVM helper for verifying whether an installed App's signing certificate matches the
 * certificate pinned when a credential was saved (`S7` anti-repackaging / anti-spoofing protection).
 */
object AppSignatureVerifier {

    private val HEX_CHARS = "0123456789abcdef".toCharArray()

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = CharArray(digest.size * 2)
        for (i in digest.indices) {
            val v = digest[i].toInt() and 0xFF
            out[i * 2] = HEX_CHARS[v ushr 4]
            out[i * 2 + 1] = HEX_CHARS[v and 0x0F]
        }
        return String(out)
    }

    /**
     * Returns `true` when [pinnedSha256] is compatible with [liveSignatures]:
     * - Legacy / CSV-imported / website-only records (`pinnedSha256.isBlank()`) are allowed (TOFU).
     * - Unresolvable packages (`liveSignatures == null`) are allowed (not installed or unit test without PM).
     * - Otherwise [pinnedSha256] must appear in [AppSignatures.trustedSha256Set].
     */
    fun matchesPinnedSignature(
        pinnedSha256: String,
        liveSignatures: AppSignatures?
    ): Boolean {
        val pinned = pinnedSha256.trim().lowercase()
        if (pinned.isEmpty() || liveSignatures == null || liveSignatures.trustedSha256Set.isEmpty()) {
            return true
        }
        return pinned in liveSignatures.trustedSha256Set
    }

    /**
     * Returns `true` ONLY when a pinned signature exists, live signatures are present, and they conflict
     * (indicating a repackaged / sideloaded App sharing the same `packageName`).
     */
    fun hasSignatureMismatch(
        pinnedSha256: String,
        liveSignatures: AppSignatures?
    ): Boolean = !matchesPinnedSignature(pinnedSha256, liveSignatures)
}
