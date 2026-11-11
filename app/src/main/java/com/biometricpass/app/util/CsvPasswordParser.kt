package com.biometricpass.app.util

import java.net.URI

/**
 * Pure RFC-4180 CSV parser for importing passwords exported from Chrome / Google Password Manager,
 * Microsoft Edge, Mozilla Firefox, or Bitwarden (`name,url,username,password`).
 */
object CsvPasswordParser {

    data class ParsedCsvCredential(
        val title: String,
        val packageName: String,
        val webDomain: String,
        val username: String,
        val password: String
    )

    private val TITLE_HEADERS = setOf("name", "title", "標題", "名稱")
    private val URL_HEADERS = setOf("url", "origin", "hostname", "formSubmitOrigin", "login_uri", "website", "網址")
    private val USER_HEADERS = setOf("username", "login_username", "user", "account", "帳號")
    private val PASS_HEADERS = setOf("password", "login_password", "pass", "密碼")

    class MissingPasswordColumnException :
        IllegalArgumentException("Missing 'password' column in CSV")

    fun parse(
        csvText: String,
        defaultTitle: String = "Imported Item"
    ): List<ParsedCsvCredential> {
        val cleaned = csvText.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) return emptyList()

        val rows = parseRfc4180Rows(cleaned)
        if (rows.size < 2) return emptyList()

        val header = rows.first().map { it.trim().lowercase() }
        val passIdx = header.indexOfFirst { it in PASS_HEADERS }
        if (passIdx < 0) {
            throw MissingPasswordColumnException()
        }
        val titleIdx = header.indexOfFirst { it in TITLE_HEADERS }
        val urlIdx = header.indexOfFirst { it in URL_HEADERS }
        val userIdx = header.indexOfFirst { it in USER_HEADERS }

        val results = mutableListOf<ParsedCsvCredential>()
        for (i in 1 until rows.size) {
            val row = rows[i]
            if (row.all { it.isBlank() }) continue
            val password = row.getOrNull(passIdx).orEmpty()
            if (password.isEmpty()) continue

            val rawTitle = if (titleIdx >= 0) row.getOrNull(titleIdx).orEmpty().trim() else ""
            val rawUrl = if (urlIdx >= 0) row.getOrNull(urlIdx).orEmpty().trim() else ""
            val username = if (userIdx >= 0) row.getOrNull(userIdx).orEmpty().trim() else ""

            val (pkg, domain) = extractTargetFromUrl(rawUrl)
            val finalTitle = rawTitle.ifBlank {
                domain.ifBlank { pkg.ifBlank { defaultTitle } }
            }

            results.add(
                ParsedCsvCredential(
                    title = finalTitle,
                    packageName = pkg,
                    webDomain = domain,
                    username = username,
                    password = password
                )
            )
        }
        return results
    }

    /**
     * Extracts either an Android `packageName` (from Chrome's `android://<certHash>@<packageName>/` format)
     * or a clean `webDomain` host (from `https://accounts.google.com/...` or bare domains).
     */
    internal fun extractTargetFromUrl(rawUrl: String): Pair<String, String> {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return "" to ""

        if (trimmed.startsWith("android://", ignoreCase = true)) {
            val afterScheme = trimmed.substringAfter("://").trimEnd('/')
            val pkg = afterScheme.substringAfter('@', afterScheme).substringBefore('/').trim()
            return pkg to ""
        }

        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        val host = try {
            URI(withScheme).host?.lowercase().orEmpty()
        } catch (_: Exception) {
            trimmed.substringAfter("://").substringBefore('/').substringBefore(':').lowercase().trim()
        }
        return "" to host.removePrefix("www.").takeIf { it.isNotBlank() }.orEmpty().ifBlank { host }
    }

    internal fun parseRfc4180Rows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var currentRow = mutableListOf<String>()
        val currentField = StringBuilder()
        var inQuotes = false
        var idx = 0

        while (idx < text.length) {
            val ch = text[idx]
            if (inQuotes) {
                if (ch == '"') {
                    if (idx + 1 < text.length && text[idx + 1] == '"') {
                        currentField.append('"')
                        idx += 2
                        continue
                    } else {
                        inQuotes = false
                    }
                } else {
                    currentField.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                    }
                    '\r' -> {
                        if (idx + 1 < text.length && text[idx + 1] == '\n') {
                            idx++
                        }
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    '\n' -> {
                        currentRow.add(currentField.toString())
                        currentField.setLength(0)
                        rows.add(currentRow)
                        currentRow = mutableListOf()
                    }
                    else -> currentField.append(ch)
                }
            }
            idx++
        }

        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow)
        }
        return rows
    }
}
