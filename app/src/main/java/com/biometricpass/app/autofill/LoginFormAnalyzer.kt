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
 * Non-blank value captured from an editable input node on a login / authentication form.
 * Allows multi-field forms (e.g. Bank ID + User ID + Password, or unlabeled fields) to present
 * quick-assign chips during Save without losing the 3rd field.
 */
data class CapturedFormField(
    val labelHint: String,
    val value: String,
    val isPasswordMask: Boolean,
    val isNumericPassword: Boolean
)

/**
 * Pure login-form detection heuristics (Android-framework free):
 * - Strictly identifies real login / password forms, 3-field Taiwanese bank login forms
 *   (National ID `身分證字號` + User ID `使用者代號` + Password `使用者密碼`, even when the bank
 *   masks National ID or User ID with `TYPE_TEXT_VARIATION_PASSWORD`), and two-step username screens.
 * - Excludes browser address/omnibox bars, search boxes, chat/message inputs, multiline text areas,
 *   and `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` subtrees.
 * - Field role classification is 100% deterministic from form structure & labels, never flipping
 *   roles based on which input currently holds cursor focus.
 */
object LoginFormAnalyzer {

    data class Analysis(
        val packageName: String,
        val webDomain: String,
        val usernameNode: FormNode?,
        /** Optional 2nd identifier field on 3-field Bank logins (e.g. `使用者代號` / `User ID`). */
        val secondaryUsernameNode: FormNode? = null,
        val secondaryUsernameLabel: String = "",
        val passwordNode: FormNode?,
        /** Primary password node first, then every other true password node (no duplicates). */
        val passwordNodes: List<FormNode>,
        val focusedNode: FormNode?,
        val editableNodes: List<FormNode>,
        val usernameText: String,
        val secondaryUsernameText: String = "",
        val passwordText: String,
        val capturedFields: List<CapturedFormField> = emptyList(),
        val isNumericPasswordFocus: Boolean = false
    )

    private val IGNORED_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.google.android.inputmethod.latin",
        "com.samsung.android.honeyboard"
    )

    internal val NATIONAL_ID_KEYWORDS = listOf(
        "身分證", "身份證", "統一編號", "統編", "居留證",
        "id_card", "idno", "id_no", "cust_id", "custid", "roc_id", "national_id"
    )

    internal val USER_ID_CODE_KEYWORDS = listOf(
        "使用者代號", "用戶代號", "網銀代號", "網路銀行代號", "登入代號", "會員代號",
        "使用者代碼", "用戶代碼", "登入代碼", "代號",
        "userid", "user_id", "usercode", "user_code", "login_code"
    )

    // Keyword lists intentionally contain both English and Chinese terms so login forms in either
    // language are recognized regardless of the device's UI locale.
    private val USERNAME_KEYWORDS = listOf(
        "username", "user_name", "user_account",
        "login_id", "login_name", "login_user", "account", "acct",
        "email", "e-mail",
        "帳號", "帳戶", "使用者名稱", "用戶名", "電子郵件", "信箱", "電郵",
        "登入帳號", "會員帳號", "員工編號", "工號"
    ) + NATIONAL_ID_KEYWORDS + USER_ID_CODE_KEYWORDS

    private val PASSWORD_KEYWORDS = listOf(
        "password", "passwd", "passcode", "pwd", "user_pin", "pin_code", "pincode",
        "new-password", "current-password",
        "密碼", "通行碼", "暗號"
    )

    private val CONFIRM_OR_CHANGE_PASSWORD_KEYWORDS = listOf(
        "confirm", "repeat", "retype", "verify_pwd", "new-password", "new_password",
        "old_password", "current-password",
        "確認密碼", "再次輸入", "重輸密碼", "新密碼", "舊密碼", "變更密碼"
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
        val rawPasswordNodes = mutableListOf<FormNode>()
        val explicitUsernameNodes = mutableListOf<FormNode>()
        val visiblePasswordCandidates = mutableListOf<FormNode>()
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

        fun collectPureLabelText(node: FormNode, depth: Int = 0): String {
            if (!node.isVisible || depth > 3 || isEditableInputNode(node)) return ""
            val ownText = node.text.trim()
            if (node.children.isEmpty()) {
                return if (ownText.length in 1..28) ownText else ""
            }
            return buildString {
                if (ownText.length in 1..28) append(ownText)
                for (c in node.children) {
                    val sub = collectPureLabelText(c, depth + 1)
                    if (sub.isNotEmpty()) {
                        if (isNotEmpty()) append(" ")
                        append(sub)
                    }
                }
            }.trim()
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

            val effectiveMeta = when {
                inheritedMeta.isNotBlank() && ownMeta.isNotBlank() -> "$ownMeta $inheritedMeta"
                ownMeta.isNotBlank() -> ownMeta
                else -> inheritedMeta
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
                        rawPasswordNodes.add(node)
                    }
                    isUsernameNode(node, effectiveMeta) -> {
                        explicitUsernameNodes.add(node)
                    }
                    isVisiblePasswordInputType(node) -> {
                        visiblePasswordCandidates.add(node)
                    }
                }
            }

            if (isEditableInputNode(node) || node.children.isEmpty()) return

            val editableDescendantCount = countVisibleEditableDescendants(node)
            if (editableDescendantCount == 1 && node.children.size in 1..6) {
                // Single-input row/wrapper (e.g. TextInputLayout or LinearLayout row with nested label FrameLayout)
                val singleInputMeta = buildString {
                    if (effectiveMeta.isNotBlank()) append(effectiveMeta)
                    for (child in node.children) {
                        if (countVisibleEditableDescendants(child) == 0) {
                            val lbl = collectPureLabelText(child)
                            if (lbl.isNotBlank()) {
                                if (isNotEmpty()) append(" ")
                                append(lbl)
                            }
                        }
                    }
                }.trim()
                for (child in node.children) {
                    traverse(child, singleInputMeta, currentInsideWeb)
                }
            } else {
                // Multi-input container (e.g. flat ConstraintLayout / LinearLayout with [Label1, Input1, Label2, Input2, Label3, Input3]):
                // pair each pure label sibling strictly with the immediately following single-input sibling!
                var pendingSiblingLabel = ""
                for (child in node.children) {
                    if (!child.isVisible) continue
                    val childEditCount = countVisibleEditableDescendants(child)
                    if (childEditCount == 0) {
                        val lbl = collectPureLabelText(child)
                        if (lbl.isNotBlank()) {
                            pendingSiblingLabel = lbl
                        }
                    } else if (childEditCount == 1) {
                        traverse(child, pendingSiblingLabel, currentInsideWeb)
                        pendingSiblingLabel = ""
                    } else {
                        pendingSiblingLabel = ""
                        traverse(child, "", currentInsideWeb)
                    }
                }
            }
        }

        roots.forEach { traverse(it) }

        // Promote standalone `textVisiblePassword` ONLY when an explicit username precedes it
        if (rawPasswordNodes.isEmpty() && visiblePasswordCandidates.isNotEmpty() && explicitUsernameNodes.isNotEmpty()) {
            val firstUserIdx = allEditableNodes.indexOf(explicitUsernameNodes.first())
            val visPwd = visiblePasswordCandidates.firstOrNull { allEditableNodes.indexOf(it) > firstUserIdx }
            if (visPwd != null) {
                rawPasswordNodes.add(visPwd)
            }
        }

        // Disambiguate multi-masked login forms (common in Taiwanese Bank Apps such as Taishin Bank,
        // where National ID and/or User ID also use TYPE_TEXT_VARIATION_PASSWORD for shoulder-surfing
        // protection) vs registration / change-password forms (`Password` + `Confirm Password`):
        val confirmedPasswordNodes = resolveConfirmedPasswordNodes(
            allEditableNodes = allEditableNodes,
            rawPasswordNodes = rawPasswordNodes,
            explicitUsernameNodes = explicitUsernameNodes,
            nodeInheritedMeta = nodeInheritedMeta
        )
        val passwordNode: FormNode? = confirmedPasswordNodes.firstOrNull()

        // Resolve primary `usernameNode` and optional 3-field Bank `secondaryUsernameNode` (`使用者代號`)
        // strictly from non-password editable fields in the same layer (web vs native).
        var usernameNode: FormNode? = null
        var secondaryUsernameNode: FormNode? = null

        if (passwordNode != null) {
            val pwdIsWeb = isWebNode(passwordNode)
            val pwdIdx = allEditableNodes.indexOf(passwordNode)
            val precedingIdentifierNodes = allEditableNodes
                .subList(0, pwdIdx.coerceAtLeast(0))
                .filter { isWebNode(it) == pwdIsWeb && it !in confirmedPasswordNodes }

            when {
                precedingIdentifierNodes.size >= 2 -> {
                    val natId = precedingIdentifierNodes.firstOrNull { n ->
                        val meta = nodeInheritedMeta[n].orEmpty().lowercase()
                        NATIONAL_ID_KEYWORDS.any { meta.contains(it) }
                    }
                    val userCode = precedingIdentifierNodes.firstOrNull { n ->
                        n !== natId && USER_ID_CODE_KEYWORDS.any {
                            nodeInheritedMeta[n].orEmpty().lowercase().contains(it)
                        }
                    }
                    if (natId != null && userCode != null) {
                        usernameNode = natId
                        secondaryUsernameNode = userCode
                    } else {
                        // Standard 3-field Taiwanese bank layout order: [0] 身分證字號, [1] 使用者代號
                        usernameNode = precedingIdentifierNodes[precedingIdentifierNodes.size - 2]
                        secondaryUsernameNode = precedingIdentifierNodes[precedingIdentifierNodes.size - 1]
                    }
                }
                precedingIdentifierNodes.size == 1 -> {
                    usernameNode = precedingIdentifierNodes.single()
                }
                else -> {
                    usernameNode = explicitUsernameNodes.firstOrNull {
                        isWebNode(it) == pwdIsWeb && it !in confirmedPasswordNodes
                    }
                }
            }
        } else if (explicitUsernameNodes.size >= 2) {
            val natId = explicitUsernameNodes.firstOrNull { n ->
                val meta = nodeInheritedMeta[n].orEmpty().lowercase()
                NATIONAL_ID_KEYWORDS.any { meta.contains(it) }
            }
            val userCode = explicitUsernameNodes.firstOrNull { n ->
                n !== natId && USER_ID_CODE_KEYWORDS.any {
                    nodeInheritedMeta[n].orEmpty().lowercase().contains(it)
                }
            }
            if (natId != null && userCode != null) {
                usernameNode = natId
                secondaryUsernameNode = userCode
            } else {
                usernameNode = explicitUsernameNodes[0]
                secondaryUsernameNode = explicitUsernameNodes[1]
            }
        } else {
            usernameNode = explicitUsernameNodes.firstOrNull()
        }

        val secondaryUsernameLabel = secondaryUsernameNode?.let { node ->
            resolveCleanFieldLabel(node, nodeInheritedMeta[node].orEmpty())
        }.orEmpty()

        val capturedFields = buildList<CapturedFormField> {
            for (editable in allEditableNodes) {
                val valText = extractNodeText(editable).trim()
                if (valText.isEmpty()) continue
                val meta = nodeInheritedMeta[editable].orEmpty()
                val isPwd = editable in confirmedPasswordNodes
                val isNumPwd = isPwd && isNumericPinNode(editable, meta)
                val label = resolveCleanFieldLabel(editable, meta)
                if (none { it.value == valText && it.isPasswordMask == isPwd }) {
                    add(
                        CapturedFormField(
                            labelHint = label,
                            value = valText,
                            isPasswordMask = isPwd,
                            isNumericPassword = isNumPwd
                        )
                    )
                }
            }
        }

        val focusTarget = focusedNode ?: passwordNode
        val isNumericPasswordFocus = focusTarget != null &&
            isNumericPinNode(focusTarget, nodeInheritedMeta[focusTarget].orEmpty())

        return Analysis(
            packageName = if (detectedPackage == ownPackageName) "" else detectedPackage,
            webDomain = detectedDomain,
            usernameNode = usernameNode,
            secondaryUsernameNode = secondaryUsernameNode,
            secondaryUsernameLabel = secondaryUsernameLabel,
            passwordNode = passwordNode,
            passwordNodes = confirmedPasswordNodes,
            focusedNode = focusedNode,
            editableNodes = allEditableNodes,
            usernameText = extractNodeText(usernameNode),
            secondaryUsernameText = extractNodeText(secondaryUsernameNode),
            passwordText = extractNodeText(passwordNode),
            capturedFields = capturedFields,
            isNumericPasswordFocus = isNumericPasswordFocus
        )
    }

    private fun resolveConfirmedPasswordNodes(
        allEditableNodes: List<FormNode>,
        rawPasswordNodes: List<FormNode>,
        explicitUsernameNodes: List<FormNode>,
        nodeInheritedMeta: Map<FormNode, String>
    ): List<FormNode> {
        if (rawPasswordNodes.size <= 1) return rawPasswordNodes

        // Check whether this is an explicit registration / password-change form (Confirm / New Password)
        val hasConfirmOrChangeKeyword = rawPasswordNodes.any { node ->
            val meta = "${ownMetaLower(node)} ${nodeInheritedMeta[node].orEmpty().lowercase()}"
            CONFIRM_OR_CHANGE_PASSWORD_KEYWORDS.any { meta.contains(it) }
        }
        if (hasConfirmOrChangeKeyword) {
            return rawPasswordNodes
        }

        // During onSaveRequest, if two password-masked inputs contain identical non-blank text,
        // they are [Password, Confirm Password] on a sign-up form.
        val lastTwoTexts = rawPasswordNodes.takeLast(2).map { extractNodeText(it).trim() }
        if (lastTwoTexts.size == 2 &&
            lastTwoTexts[0].isNotEmpty() &&
            lastTwoTexts[0] == lastTwoTexts[1]
        ) {
            return rawPasswordNodes
        }

        // Otherwise, when a form has multiple masked fields with NO confirm/change-password keywords
        // (e.g. [masked ID, masked User ID, masked Password] or [plain ID, masked User ID, masked Password]
        // or [masked Account, masked Password]), earlier masked inputs are masked login identifiers
        // and the LAST masked input is the actual login password!
        if (explicitUsernameNodes.isEmpty() || allEditableNodes.size >= 3) {
            return listOf(rawPasswordNodes.last())
        }

        return rawPasswordNodes
    }

    private fun isNumericPinNode(node: FormNode, effectiveMeta: String): Boolean {
        val inputType = node.inputType
        val isNumberPwd = (inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER &&
            (inputType and InputType.TYPE_MASK_VARIATION) == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        if (isNumberPwd) return true
        val lower = effectiveMeta.lowercase()
        return lower.contains("pin") ||
            lower.contains("passcode") ||
            lower.contains("交易密碼") ||
            lower.contains("提款密碼") ||
            lower.contains("支付密碼") ||
            lower.contains("安全碼")
    }

    private fun resolveCleanFieldLabel(node: FormNode, inheritedMeta: String): String {
        val directHint = node.hint.trim()
        if (directHint.length in 1..24) return directHint
        val desc = node.contentDescription.trim()
        if (desc.length in 1..24) return desc
        val inheritedLower = inheritedMeta.lowercase()
        when {
            USER_ID_CODE_KEYWORDS.any { inheritedLower.contains(it) } -> return "使用者代號"
            NATIONAL_ID_KEYWORDS.any { inheritedLower.contains(it) } -> return "身分證字號"
        }
        val idClean = node.idEntry.trim()
        if (idClean.length in 3..24 && !idClean.matches(Regex("^[a-z]{1,2}\\d+$", RegexOption.IGNORE_CASE))) {
            return idClean
        }
        return inheritedMeta.trim().take(24)
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

        if (hasHiddenPasswordInputType(node)) {
            return false
        }

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

        val ownLower = ownMetaLower(node)
        val combinedMeta = if (inheritedMeta.isNotBlank()) {
            "$ownLower ${inheritedMeta.lowercase()}"
        } else {
            ownLower
        }

        // CRITICAL FOR TAIWANESE BANK APPS:
        // Fields explicitly labeled as National ID ("身分證字號") or User ID ("使用者代號" / "userid")
        // often set `inputType = textPassword` solely to mask dots against shoulder surfing.
        // Never classify an explicitly labeled ID / User ID field as the password field!
        if (USERNAME_KEYWORDS.any { combinedMeta.contains(it) } &&
            PASSWORD_KEYWORDS.none { combinedMeta.contains(it) }
        ) {
            return false
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

        return PASSWORD_KEYWORDS.any { combinedMeta.contains(it) }
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
