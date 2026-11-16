package com.biometricpass.app.data.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Additional sensitive or auxiliary field attached to a credential (e.g. Bank `User ID`,
 * Wallet `PIN 碼`, `交易密碼`, `身分證字號`, or encrypted notes).
 *
 * Stored as an AES-256-GCM encrypted JSON array in `CredentialEntity.encryptedExtraFields`
 * with its own dedicated 12-byte random GCM IV (`CredentialEntity.extraFieldsIv`).
 */
data class ExtraField(
    val label: String,
    val value: String,
    val isSecret: Boolean = true
)

object ExtraFieldsCodec {

    fun encodeToJson(fields: List<ExtraField>): String {
        val cleaned = fields.mapNotNull { field ->
            val trimmedValue = field.value.trim()
            if (trimmedValue.isEmpty()) {
                null
            } else {
                ExtraField(
                    label = field.label.trim().ifEmpty { "Note" },
                    value = trimmedValue,
                    isSecret = field.isSecret
                )
            }
        }
        if (cleaned.isEmpty()) return ""

        val arr = JSONArray()
        for (item in cleaned) {
            arr.put(
                JSONObject().apply {
                    put("label", item.label)
                    put("value", item.value)
                    put("isSecret", item.isSecret)
                }
            )
        }
        return arr.toString()
    }

    fun decodeFromJson(json: String): List<ExtraField> {
        val trimmed = json.trim()
        if (trimmed.isEmpty()) return emptyList()
        return try {
            decodeFromJsonArray(JSONArray(trimmed))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun decodeFromJsonArray(arr: JSONArray?): List<ExtraField> {
        if (arr == null || arr.length() == 0) return emptyList()
        val result = ArrayList<ExtraField>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val value = obj.optString("value", "").trim()
            if (value.isEmpty()) continue
            val label = obj.optString("label", "").trim().ifEmpty { "Note" }
            val isSecret = obj.optBoolean("isSecret", true)
            result.add(ExtraField(label = label, value = value, isSecret = isSecret))
        }
        return result
    }

    fun toJsonArray(fields: List<ExtraField>): JSONArray {
        val arr = JSONArray()
        for (item in fields) {
            val trimmedValue = item.value.trim()
            if (trimmedValue.isEmpty()) continue
            arr.put(
                JSONObject().apply {
                    put("label", item.label.trim().ifEmpty { "Note" })
                    put("value", trimmedValue)
                    put("isSecret", item.isSecret)
                }
            )
        }
        return arr
    }

    /**
     * Merges [incoming] extra fields into [existing]:
     * - Updates in place if an entry with the same case-insensitive `label` already exists.
     * - Appends new entries otherwise.
     */
    fun mergeFields(existing: List<ExtraField>, incoming: List<ExtraField>): List<ExtraField> {
        val result = existing.toMutableList()
        for (inc in incoming) {
            val cleanLabel = inc.label.trim()
            val cleanValue = inc.value.trim()
            if (cleanLabel.isEmpty() || cleanValue.isEmpty()) continue
            val normalized = ExtraField(label = cleanLabel, value = cleanValue, isSecret = inc.isSecret)
            val idx = result.indexOfFirst { it.label.trim().equals(cleanLabel, ignoreCase = true) }
            if (idx >= 0) {
                result[idx] = normalized
            } else {
                result.add(normalized)
            }
        }
        return result
    }
}
