package com.biometricpass.app.autofill

import android.text.InputType

/**
 * Framework-independent view of one node of an Autofill view hierarchy.
 *
 * [AssistStructureParser] adapts `AssistStructure.ViewNode` to this interface, so all login-form
 * heuristics in [LoginFormAnalyzer] can be covered by plain JVM unit tests with hand-built forms.
 * Implementations must return the SAME child instances on every access (identity is used as a key).
 */
interface FormNode {
    val isVisible: Boolean

    /** `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`. */
    val excludesDescendantsFromAutofill: Boolean
    val idPackage: String
    val webDomain: String
    val hasHtmlInfo: Boolean

    /** HTML tag name, lowercase ("" for native views). */
    val htmlTag: String

    /** HTML attributes as lowercase (name, value) pairs. */
    val htmlAttributes: List<Pair<String, String>>
    val idEntry: String
    val hint: String
    val contentDescription: String
    val autofillHints: List<String>
    val hasAutofillId: Boolean
    val className: String
    val isTextAutofillType: Boolean
    val inputType: Int
    val isFocusable: Boolean
    val isFocused: Boolean
    val text: String

    /** Current text from the node's Autofill value ("" when not a text value). */
    val autofillText: String
    val children: List<FormNode>
}

/**
 * Pure login-form detection heuristics (Android-framework free):
 * - Strictly identifies real login / password forms and explicit two-step login username screens.
 * - Excludes browser address/omnibox bars, search boxes, chat/message inputs, multiline text areas,
 *   and `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` subtrees.
 * - Never guesses arbitrary plain text-field pairs as username/password forms.
 */
object LoginFormAnalyzer {

    data class Analysis(
        val packageName: String,
        val webDomain: String,
        val usernameNode: FormNode?,
        val passwordNode: FormNode?,
        /** Primary password node first, then every other password node (no duplicates). */
        val passwordNodes: List<FormNode>,
        val focusedNode: FormNode?,
        val editableNodes: List<FormNode>,
        val usernameText: String,
        val passwordText: String
    )

    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    // Keyword lists intentionally contain both English and Chinese terms so login forms in either
    // language are recognized regardless of the device's UI locale.
    private val USERNAME_KEYWORDS = listOf(
        "username", "user_name", "userid", "user_id", "user_account",
        "login_id", "login_name", "login_user", "account", "acct",
        "email", "e-mail", "id_card", "idno",
        "帳號", "帳戶", "使用者名稱", "用戶名", "電子郵件", "信箱", "電郵",
        "登入帳號", "會員帳號", "員工編號", "工號", "身分證字號"
    )

    private val PASSWORD_KEYWORDS = listOf(
        "password", "passwd", "passcode", "pwd", "user_pin", "pin_code", "pincode",
        "new-password", "current-password",
        "密碼", "通行碼", "暗號"
    )

    private val NON_CREDENTIAL_KEYWORDS = listOf(
        "url_bar", "location_bar", "omnibox", "address_bar", "mozac_browser_toolbar",
        "search", "query", "filter", "find_in_page",
        "chat", "message", "msg_input", "send_msg", "comment", "reply", "post_content",
        "note", "memo", "captcha", "otp", "one-time-code", "sms_code", "verify_code", "verification_code",
        "搜尋", "查找", "輸入訊息", "傳送訊息", "聊天", "留言", "評論", "備註", "驗證碼"
    )

    private val NON_CREDENTIAL_HTML_TYPES = setOf(
        "search", "hidden", "submit", "button", "reset", "checkbox", "radio",
        "file", "image", "range", "color", "date", "datetime-local", "month", "week", "time"
    )

    // android.view.View autofill hint values (kept as literals so this object stays framework free)
    private const val HINT_PASSWORD = "password"
    private const val HINT_USERNAME = "username"
    private const val HINT_EMAIL_ADDRESS = "emailaddress"
    private const val HINT_PHONE = "phone"

    fun analyze(
        roots: List<FormNode>,
        activityPackage: String,
        ownPackageName: String,
        isManualRequest: Boolean = false
    ): Analysis {
        var detectedPackage = activityPackage
        var detectedDomain = ""
        var usernameNode: FormNode? = null
        var passwordNode: FormNode? = null
        val allPasswordNodes = mutableListOf<FormNode>()
        var visiblePasswordCandidateNode: FormNode? = null
        var focusedNode: FormNode? = null
        val allEditableNodes = mutableListOf<FormNode>()
        val nodeInheritedMeta = HashMap<FormNode, String>()

        fun countVisibleEditableDescendants(node: FormNode): Int {
            if (!node.isVisible) return 0
            var count = if (isEditableInputNode(node)) 1 else 0
            for (child in node.children) {
                count += countVisibleEditableDescendants(child)
                if (count > 1) return count
            }
            return count
        }

        fun traverse(node: FormNode, inheritedMeta: String = "", insideWebTree: Boolean = false) {
            if (!node.isVisible) return
            if (!isManualRequest && node.excludesDescendantsFromAutofill) return

            val nodePkg = node.idPackage
            if (detectedPackage.isBlank() &&
                nodePkg.isNotBlank() &&
                nodePkg != ownPackageName &&
                !IGNORED_PACKAGES.contains(nodePkg)
            ) {
                detectedPackage = nodePkg
            }

            val currentInsideWeb = insideWebTree || isWebNode(node)
            if (detectedDomain.isBlank() && node.webDomain.isNotBlank()) {
                detectedDomain = node.webDomain.trim()
            }

            val ownMeta = buildString {
                append(node.idEntry)
                append(" ")
                append(node.hint)
                append(" ")
                append(node.contentDescription)
                if (node.autofillHints.isNotEmpty()) {
                    append(" ")
                    append(node.autofillHints.joinToString(" "))
                }
            }.trim()

            val effectiveMeta = if (inheritedMeta.isNotBlank() && ownMeta.isNotBlank()) {
                "$ownMeta $inheritedMeta"
            } else if (ownMeta.isNotBlank()) {
                ownMeta
            } else {
                inheritedMeta
            }

            if (isEditableInputNode(node) &&
                !isExcludedNonCredentialInput(
                    node = node,
                    ownMeta = ownMeta,
                    effectiveMeta = effectiveMeta,
                    hostPackage = detectedPackage,
                    insideWebTree = currentInsideWeb
                )
            ) {
                allEditableNodes.add(node)
                nodeInheritedMeta[node] = effectiveMeta
                if (node.isFocused) {
                    focusedNode = node
                }

                when {
                    isStrictPasswordNode(node, effectiveMeta) -> {
                        allPasswordNodes.add(node)
                        if (passwordNode == null || node.isFocused || passwordNode?.isFocused != true) {
                            passwordNode = node
                        }
                    }
                    isUsernameNode(node, effectiveMeta) -> {
                        if (usernameNode == null || node.isFocused) {
                            usernameNode = node
                        }
                    }
                    isVisiblePasswordInputType(node) -> {
                        if (visiblePasswordCandidateNode == null || node.isFocused) {
                            visiblePasswordCandidateNode = node
                        }
                    }
                }
            }

            // Pass container metadata (e.g. from TextInputLayout or a single-input row with a TextView label)
            // down ONLY when this container wraps a single visible editable input, preventing form-level
            // containers from leaking "password" metadata onto sibling fields.
            val childInheritedMeta = if (!isEditableInputNode(node) &&
                node.children.size in 1..4 &&
                countVisibleEditableDescendants(node) == 1
            ) {
                buildString {
                    if (effectiveMeta.isNotBlank()) append(effectiveMeta)
                    for (child in node.children) {
                        if (child.isVisible && !isEditableInputNode(child) && child.children.isEmpty()) {
                            val labelText = child.text.trim()
                            if (labelText.length in 1..24) {
                                append(" ")
                                append(labelText)
                            }
                        }
                    }
                }.trim()
            } else {
                ""
            }

            for (child in node.children) {
                traverse(child, childInheritedMeta, currentInsideWeb)
            }
        }

        roots.forEach { traverse(it) }

        // Only promote a standalone `textVisiblePassword` field to passwordNode when an explicit
        // usernameNode already precedes it on the same form (many non-password fields use
        // textVisiblePassword merely to suppress keyboard spellcheck).
        val visibleCandidate = visiblePasswordCandidateNode
        if (passwordNode == null && visibleCandidate != null && usernameNode != null) {
            val userIdx = allEditableNodes.indexOf(usernameNode)
            val visPwdIdx = allEditableNodes.indexOf(visibleCandidate)
            if (userIdx in 0 until visPwdIdx) {
                passwordNode = visibleCandidate
                allPasswordNodes.add(visibleCandidate)
            }
        }

        // Companion Username Resolution: ONLY when a real passwordNode has been confirmed on screen,
        // if usernameNode wasn't explicitly labeled, find the nearest preceding editable field in the
        // same layer (web vs native) so both username and password can be filled together.
        val confirmedPassword = passwordNode
        if (confirmedPassword != null && usernameNode == null) {
            val pwdIsWeb = isWebNode(confirmedPassword)
            val pwdIndex = allEditableNodes.indexOf(confirmedPassword)
            if (pwdIndex > 0) {
                for (i in (pwdIndex - 1) downTo 0) {
                    val candidate = allEditableNodes[i]
                    if (isWebNode(candidate) != pwdIsWeb) continue
                    val meta = nodeInheritedMeta[candidate].orEmpty()
                    if (!isStrictPasswordNode(candidate, meta)) {
                        usernameNode = candidate
                        break
                    }
                }
            }
        }

        val passwordNodes = buildList {
            passwordNode?.let { add(it) }
            for (n in allPasswordNodes) if (!contains(n)) add(n)
        }

        return Analysis(
            packageName = if (detectedPackage == ownPackageName) "" else detectedPackage,
            webDomain = detectedDomain,
            usernameNode = usernameNode,
            passwordNode = passwordNode,
            passwordNodes = passwordNodes,
            focusedNode = focusedNode,
            editableNodes = allEditableNodes,
            usernameText = extractNodeText(usernameNode),
            passwordText = extractNodeText(passwordNode)
        )
    }

    private fun isWebNode(node: FormNode): Boolean = node.webDomain.isNotBlank() || node.hasHtmlInfo

    private fun isEditableInputNode(node: FormNode): Boolean {
        if (!node.hasAutofillId) return false
        val className = node.className
        if (className.contains("Layout", ignoreCase = true) ||
            className.contains("ViewGroup", ignoreCase = true)
        ) {
            return false
        }
        val htmlTag = node.htmlTag
        if (htmlTag.isNotBlank() && htmlTag != "input" && htmlTag != "textarea") {
            return false
        }
        return node.isTextAutofillType ||
            className.contains("EditText", ignoreCase = true) ||
            className.contains("AutoCompleteTextView", ignoreCase = true) ||
            htmlTag == "input" ||
            (node.inputType != 0 && node.isFocusable)
    }

    private fun isExcludedNonCredentialInput(
        node: FormNode,
        ownMeta: String,
        effectiveMeta: String,
        hostPackage: String,
        insideWebTree: Boolean
    ): Boolean {
        // 1. Inside a browser app, any native input outside the web document tree is browser UI
        //    (e.g. Chrome/Samsung/Firefox address bar, omnibox, find-in-page), never a website login field.
        if (AssistStructureParser.isBrowserPackage(hostPackage) && !insideWebTree) {
            return true
        }

        if (node.htmlTag == "textarea") {
            return true
        }

        for ((attrName, attrVal) in node.htmlAttributes) {
            if (attrName == "type" && NON_CREDENTIAL_HTML_TYPES.contains(attrVal)) {
                return true
            }
            if (attrName == "role" && attrVal == "searchbox") {
                return true
            }
            if (attrName == "autocomplete" &&
                (attrVal == "one-time-code" || attrVal == "off" && NON_CREDENTIAL_KEYWORDS.any { ownMeta.lowercase().contains(it) })
            ) {
                return true
            }
        }

        // 2. If the field has explicit hardware/inputType password masking, never exclude it.
        if (hasHiddenPasswordInputType(node)) {
            return false
        }

        // 3. Multiline / chat message inputs (e.g. LINE chat box, comment editors, notes) are never credentials.
        val inputType = node.inputType
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val isMultiLine = (inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ||
                (inputType and InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
            val isChatMessageVariation = variation == InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE ||
                variation == InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE
            if (isMultiLine || isChatMessageVariation) {
                return true
            }
        }

        // 4. Exclude explicit search / URL / chat / comment / OTP fields unless explicitly marked as password.
        val metaLower = effectiveMeta.lowercase()
        val ownLower = ownMeta.lowercase()
        if (PASSWORD_KEYWORDS.none { ownLower.contains(it) } &&
            NON_CREDENTIAL_KEYWORDS.any { metaLower.contains(it) }
        ) {
            return true
        }

        return false
    }

    private fun hasHiddenPasswordInputType(node: FormNode): Boolean {
        val inputType = node.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        val isHiddenTextPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        val isNumberPassword = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        return isHiddenTextPassword || isNumberPassword
    }

    private fun isVisiblePasswordInputType(node: FormNode): Boolean {
        val inputType = node.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private fun ownMetaLower(node: FormNode): String = buildString {
        append(node.idEntry.lowercase())
        append(" ")
        append(node.hint.lowercase())
        append(" ")
        append(node.contentDescription.lowercase())
    }

    private fun isStrictPasswordNode(node: FormNode, inheritedMeta: String = ""): Boolean {
        for (hint in node.autofillHints) {
            val h = hint.lowercase()
            if (h.contains(HINT_PASSWORD) || h.contains("passcode")) {
                return true
            }
            if (h.contains(HINT_USERNAME) || h.contains(HINT_EMAIL_ADDRESS) || h.contains(HINT_PHONE)) {
                return false
            }
        }

        if (hasHiddenPasswordInputType(node)) {
            return true
        }

        for ((attrName, attrVal) in node.htmlAttributes) {
            if (attrName == "type" && attrVal == "password") return true
            if ((attrName == "name" || attrName == "id" || attrName == "autocomplete") &&
                PASSWORD_KEYWORDS.any { attrVal.contains(it) }
            ) {
                return true
            }
        }

        val ownMetaLower = ownMetaLower(node)
        if (USERNAME_KEYWORDS.any { ownMetaLower.contains(it) } &&
            PASSWORD_KEYWORDS.none { ownMetaLower.contains(it) }
        ) {
            return false
        }

        val combinedMeta = if (inheritedMeta.isNotBlank()) {
            "$ownMetaLower ${inheritedMeta.lowercase()}"
        } else {
            ownMetaLower
        }
        val hasPasswordKeyword = PASSWORD_KEYWORDS.any { combinedMeta.contains(it) }
        val hasUsernameKeyword = USERNAME_KEYWORDS.any { combinedMeta.contains(it) }
        if (hasUsernameKeyword && !hasPasswordKeyword) {
            return false
        }
        return hasPasswordKeyword
    }

    private fun isUsernameNode(node: FormNode, inheritedMeta: String = ""): Boolean {
        if (isStrictPasswordNode(node, inheritedMeta)) return false

        for (hint in node.autofillHints) {
            val h = hint.lowercase()
            if (h.contains(HINT_USERNAME) ||
                h.contains(HINT_EMAIL_ADDRESS) ||
                USERNAME_KEYWORDS.any { h.contains(it) }
            ) {
                return true
            }
        }

        val inputType = node.inputType
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if ((inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT &&
            (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        ) {
            return true
        }

        for ((attrName, attrVal) in node.htmlAttributes) {
            if (attrName == "type" && attrVal == "email") return true
            if ((attrName == "name" || attrName == "id" || attrName == "autocomplete") &&
                USERNAME_KEYWORDS.any { attrVal.contains(it) }
            ) {
                return true
            }
        }

        val combinedMeta = buildString {
            append(ownMetaLower(node))
            if (inheritedMeta.isNotBlank()) {
                append(" ")
                append(inheritedMeta.lowercase())
            }
        }
        return USERNAME_KEYWORDS.any { combinedMeta.contains(it) }
    }

    private fun extractNodeText(node: FormNode?): String {
        if (node == null) return ""
        return node.autofillText.ifBlank { node.text }
    }
}
