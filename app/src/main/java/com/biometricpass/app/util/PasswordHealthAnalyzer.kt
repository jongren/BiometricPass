package com.biometricpass.app.util

import com.biometricpass.app.data.local.CredentialEntity
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Pure offline password health analyzer.
 *
 * Security guarantee: plaintext passwords are inspected ONLY inside [analyze] and are NEVER stored
 * inside [PasswordHealthReport] or any of its issue items. Duplicate detection compares SHA-256 digests.
 */
object PasswordHealthAnalyzer {

    const val MIN_STRONG_LENGTH = 10
    const val OUTDATED_THRESHOLD_DAYS = 365L
    private const val DAY_MS = 24L * 60L * 60L * 1000L

    sealed interface IssueReason {
        data class TooShort(val length: Int, val minLength: Int) : IssueReason
        data object SingleCharClass : IssueReason
        data class OutdatedMonths(val months: Long) : IssueReason
        data class ReusedWithSingle(val otherTitle: String) : IssueReason
        data class ReusedWithMultiple(val firstTitle: String, val otherCount: Int) : IssueReason
    }

    data class HealthIssueItem(
        val credentialId: Long,
        val title: String,
        val username: String,
        val packageName: String,
        val webDomain: String,
        val reason: IssueReason
    )

    data class PasswordHealthReport(
        val totalCount: Int,
        val healthyCount: Int,
        val reusedItems: List<HealthIssueItem>,
        val weakItems: List<HealthIssueItem>,
        val outdatedItems: List<HealthIssueItem>
    ) {
        val hasIssues: Boolean
            get() = reusedItems.isNotEmpty() || weakItems.isNotEmpty() || outdatedItems.isNotEmpty()
    }

    /**
     * Inspects `(entity, plainPassword)` pairs in memory and produces a plaintext-free [PasswordHealthReport].
     */
    fun analyze(
        entries: List<Pair<CredentialEntity, String>>,
        nowMillis: Long = System.currentTimeMillis()
    ): PasswordHealthReport {
        if (entries.isEmpty()) {
            return PasswordHealthReport(
                totalCount = 0,
                healthyCount = 0,
                reusedItems = emptyList(),
                weakItems = emptyList(),
                outdatedItems = emptyList()
            )
        }

        // Group by SHA-256 digest of the password so we never retain plaintext keys in maps
        val byDigest = linkedMapOf<String, MutableList<CredentialEntity>>()
        val weakList = mutableListOf<HealthIssueItem>()
        val outdatedList = mutableListOf<HealthIssueItem>()
        val issueIds = hashSetOf<Long>()

        val outdatedCutoff = nowMillis - OUTDATED_THRESHOLD_DAYS * DAY_MS

        for ((entity, plainPassword) in entries) {
            if (plainPassword.isNotEmpty()) {
                val digestHex = sha256Hex(plainPassword)
                byDigest.getOrPut(digestHex) { mutableListOf() }.add(entity)
            }

            val weakReason = evaluateWeakReason(plainPassword)
            if (weakReason != null) {
                issueIds.add(entity.id)
                weakList.add(
                    HealthIssueItem(
                        credentialId = entity.id,
                        title = entity.title,
                        username = entity.username,
                        packageName = entity.packageName,
                        webDomain = entity.webDomain,
                        reason = weakReason
                    )
                )
            }

            if (entity.updatedAt in 1 until outdatedCutoff) {
                val daysAgo = ((nowMillis - entity.updatedAt) / DAY_MS).coerceAtLeast(OUTDATED_THRESHOLD_DAYS)
                issueIds.add(entity.id)
                outdatedList.add(
                    HealthIssueItem(
                        credentialId = entity.id,
                        title = entity.title,
                        username = entity.username,
                        packageName = entity.packageName,
                        webDomain = entity.webDomain,
                        reason = IssueReason.OutdatedMonths(daysAgo / 30)
                    )
                )
            }
        }

        val reusedList = mutableListOf<HealthIssueItem>()
        for (group in byDigest.values) {
            if (group.size > 1) {
                for (entity in group) {
                    issueIds.add(entity.id)
                    val others = group.filter { it.id != entity.id }.map { it.title }
                    val peerReason = if (others.size == 1) {
                        IssueReason.ReusedWithSingle(others.first())
                    } else {
                        IssueReason.ReusedWithMultiple(others.first(), others.size)
                    }
                    reusedList.add(
                        HealthIssueItem(
                            credentialId = entity.id,
                            title = entity.title,
                            username = entity.username,
                            packageName = entity.packageName,
                            webDomain = entity.webDomain,
                            reason = peerReason
                        )
                    )
                }
            }
        }

        val healthyCount = (entries.size - issueIds.size).coerceAtLeast(0)
        return PasswordHealthReport(
            totalCount = entries.size,
            healthyCount = healthyCount,
            reusedItems = reusedList,
            weakItems = weakList,
            outdatedItems = outdatedList
        )
    }

    internal fun evaluateWeakReason(password: String): IssueReason? {
        if (password.length < MIN_STRONG_LENGTH) {
            return IssueReason.TooShort(password.length, MIN_STRONG_LENGTH)
        }
        var classes = 0
        if (password.any { it.isUpperCase() }) classes++
        if (password.any { it.isLowerCase() }) classes++
        if (password.any { it.isDigit() }) classes++
        if (password.any { !it.isLetterOrDigit() }) classes++
        if (classes < 2) {
            return IssueReason.SingleCharClass
        }
        return null
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(StandardCharsets.UTF_8))
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(((b.toInt() ushr 4) and 0xF).toString(16))
                append((b.toInt() and 0xF).toString(16))
            }
        }
    }
}
