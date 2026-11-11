package com.biometricpass.app.autofill

import android.app.assist.AssistStructure
import android.view.View
import android.view.autofill.AutofillId

/**
 * Parses an [AssistStructure] from Autofill requests following official Android `AutofillService`
 * best practices. The detection heuristics live in the framework-free [LoginFormAnalyzer]
 * (covered by JVM unit tests); this object only adapts `ViewNode`s and maps results to [AutofillId]s.
 */
object AssistStructureParser {

    data class ParsedStructure(
        val packageName: String,
        val webDomain: String,
        val usernameId: AutofillId?,
        val passwordId: AutofillId?,
        val allPasswordIds: List<AutofillId>,
        val focusedInputId: AutofillId?,
        val allEditableIds: List<AutofillId>,
        val usernameText: String,
        val passwordText: String
    ) {
        val hasPasswordField: Boolean
            get() = passwordId != null

        val hasCredentialFields: Boolean
            get() = passwordId != null || usernameId != null
    }

    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.sec.android.app.sbrowser",
        "com.sec.android.app.sbrowser.beta",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "org.mozilla.fenix",
        "com.microsoft.emmx",
        "com.brave.browser",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "mark.via.gp"
    )

    fun isBrowserPackage(packageName: String): Boolean {
        return BROWSER_PACKAGES.contains(packageName.trim())
    }

    fun parse(
        structure: AssistStructure,
        ownPackageName: String,
        isManualRequest: Boolean = false
    ): ParsedStructure {
        val roots = buildList {
            for (i in 0 until structure.windowNodeCount) {
                structure.getWindowNodeAt(i)?.rootViewNode?.let { add(ViewNodeFormNode(it)) }
            }
        }
        val analysis = LoginFormAnalyzer.analyze(
            roots = roots,
            activityPackage = structure.activityComponent?.packageName.orEmpty(),
            ownPackageName = ownPackageName,
            isManualRequest = isManualRequest
        )
        return ParsedStructure(
            packageName = analysis.packageName,
            webDomain = analysis.webDomain,
            usernameId = analysis.usernameNode.autofillId(),
            passwordId = analysis.passwordNode.autofillId(),
            allPasswordIds = analysis.passwordNodes.distinctAutofillIds(),
            focusedInputId = analysis.focusedNode.autofillId(),
            allEditableIds = analysis.editableNodes.distinctAutofillIds(),
            usernameText = analysis.usernameText,
            passwordText = analysis.passwordText
        )
    }

    private fun FormNode?.autofillId(): AutofillId? = (this as? ViewNodeFormNode)?.node?.autofillId

    private fun List<FormNode>.distinctAutofillIds(): List<AutofillId> =
        mapNotNull { it.autofillId() }.distinct()

    /** Adapts an `AssistStructure.ViewNode` to [FormNode]; children are created once and cached. */
    private class ViewNodeFormNode(val node: AssistStructure.ViewNode) : FormNode {
        override val isVisible: Boolean get() = node.visibility == View.VISIBLE
        override val excludesDescendantsFromAutofill: Boolean
            get() = node.importantForAutofill == View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        override val idPackage: String get() = node.idPackage.orEmpty()
        override val webDomain: String get() = node.webDomain.orEmpty()
        override val hasHtmlInfo: Boolean get() = node.htmlInfo != null
        override val htmlTag: String get() = node.htmlInfo?.tag.orEmpty().lowercase()
        override val htmlAttributes: List<Pair<String, String>> by lazy {
            node.htmlInfo?.attributes.orEmpty().map { pair ->
                pair.first?.lowercase().orEmpty() to pair.second?.lowercase().orEmpty()
            }
        }
        override val idEntry: String get() = node.idEntry.orEmpty()
        override val hint: String get() = node.hint.orEmpty()
        override val contentDescription: String get() = node.contentDescription?.toString().orEmpty()
        override val autofillHints: List<String> get() = node.autofillHints?.toList().orEmpty()
        override val hasAutofillId: Boolean get() = node.autofillId != null
        override val className: String get() = node.className.orEmpty()
        override val isTextAutofillType: Boolean get() = node.autofillType == View.AUTOFILL_TYPE_TEXT
        override val inputType: Int get() = node.inputType
        override val isFocusable: Boolean get() = node.isFocusable
        override val isFocused: Boolean get() = node.isFocused
        override val text: String get() = node.text?.toString().orEmpty()
        override val autofillText: String
            get() {
                val value = node.autofillValue
                return if (value != null && value.isText) value.textValue?.toString().orEmpty() else ""
            }
        override val children: List<FormNode> by lazy {
            (0 until node.childCount).mapNotNull { i -> node.getChildAt(i)?.let { ViewNodeFormNode(it) } }
        }
    }
}
