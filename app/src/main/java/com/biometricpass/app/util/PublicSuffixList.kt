package com.biometricpass.app.util

import java.net.IDN
import java.util.Locale

/**
 * Offline Public Suffix List (PSL) lookup used to find a host's registrable domain (eTLD+1),
 * e.g. `accounts.google.com` -> `google.com`, `alice.github.io` -> `alice.github.io`.
 *
 * Implements the standard PSL algorithm (normal, wildcard `*.` and exception `!` rules, with the
 * implicit `*` default rule). Pure Kotlin so it is covered by JVM unit tests.
 */
class PublicSuffixList private constructor(
    private val rules: Set<String>,
    private val wildcardParents: Set<String>,
    private val exceptions: Set<String>
) {

    /**
     * Returns the registrable domain of [host], or `null` when [host] is blank, an IP address,
     * a single label (e.g. `localhost`), or itself a public suffix (e.g. `com`, `github.io`).
     */
    fun registrableDomain(host: String): String? {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty() || isIpAddress(normalized)) return null
        val labels = normalized.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null

        val suffixLabelCount = publicSuffixLabelCount(labels)
        if (suffixLabelCount >= labels.size) return null
        return labels.takeLast(suffixLabelCount + 1).joinToString(".")
    }

    private fun publicSuffixLabelCount(labels: List<String>): Int {
        // Walk from the longest candidate to the shortest: the first match is the longest rule.
        for (i in labels.indices) {
            val candidate = labels.subList(i, labels.size).joinToString(".")
            if (candidate in exceptions) return labels.size - i - 1
            if (candidate in rules) return labels.size - i
            if (i + 1 < labels.size) {
                val parent = labels.subList(i + 1, labels.size).joinToString(".")
                if (parent in wildcardParents) return labels.size - i
            }
        }
        return 1 // Default rule "*": the last label is the public suffix.
    }

    companion object {
        /** No rules at all: only the default `*` rule applies (used when the list is unavailable). */
        val DEFAULT_RULE_ONLY = PublicSuffixList(emptySet(), emptySet(), emptySet())

        /** Parses the standard `public_suffix_list.dat` format (comments `//`, blank lines ignored). */
        fun parse(lines: Sequence<String>): PublicSuffixList {
            val rules = HashSet<String>()
            val wildcards = HashSet<String>()
            val exceptions = HashSet<String>()
            for (raw in lines) {
                val line = raw.trim().substringBefore(' ')
                if (line.isEmpty() || line.startsWith("//")) continue
                val rule = line.lowercase(Locale.ROOT)
                when {
                    rule.startsWith("!") -> exceptions.add(rule.substring(1))
                    rule.startsWith("*.") -> wildcards.add(rule.substring(2))
                    else -> rules.add(rule)
                }
            }
            return PublicSuffixList(rules, wildcards, exceptions)
        }

        /** Lowercases, strips a trailing dot, and converts punycode (`xn--`) labels to Unicode. */
        fun normalizeHost(host: String): String {
            val trimmed = host.trim().trimEnd('.').lowercase(Locale.ROOT)
            if (!trimmed.contains("xn--")) return trimmed
            return try {
                IDN.toUnicode(trimmed, IDN.ALLOW_UNASSIGNED).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                trimmed
            }
        }

        private fun isIpAddress(host: String): Boolean =
            host.contains(':') || host.split('.').let { parts -> parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 } }
    }
}

/**
 * Decides whether a credential saved for one website may be offered on another.
 *
 * Two domains match when they are equal, or one is a sub-domain of the other AND both share the
 * same registrable domain (eTLD+1). This lets `google.com` and `accounts.google.com` match while
 * preventing matches across public suffixes such as `github.io` / `blogspot.com`.
 */
object WebDomainMatcher {

    fun matches(savedDomain: String, targetDomain: String, psl: PublicSuffixList): Boolean {
        val saved = PublicSuffixList.normalizeHost(savedDomain)
        val target = PublicSuffixList.normalizeHost(targetDomain)
        if (saved.isEmpty() || target.isEmpty()) return false
        if (saved == target) return true
        val related = target.endsWith(".$saved") || saved.endsWith(".$target")
        if (!related) return false
        val savedRegistrable = psl.registrableDomain(saved) ?: return false
        return savedRegistrable == psl.registrableDomain(target)
    }
}
