package com.biometricpass.app.autofill

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LoginFormAnalyzerTest {

    /** Hand-built form node. A plain class (identity equality), like real ViewNode adapters. */
    private class Node(
        override val className: String = "android.widget.EditText",
        override val idEntry: String = "",
        override val hint: String = "",
        override val contentDescription: String = "",
        override val autofillHints: List<String> = emptyList(),
        override val inputType: Int = InputType.TYPE_CLASS_TEXT,
        override val htmlTag: String = "",
        override val htmlAttributes: List<Pair<String, String>> = emptyList(),
        override val webDomain: String = "",
        override val isFocused: Boolean = false,
        override val text: String = "",
        override val autofillText: String = "",
        override val excludesDescendantsFromAutofill: Boolean = false,
        override val isVisible: Boolean = true,
        override val idPackage: String = "",
        override val hasAutofillId: Boolean = true,
        override val isTextAutofillType: Boolean = true,
        override val children: List<FormNode> = emptyList()
    ) : FormNode {
        override val hasHtmlInfo: Boolean get() = htmlTag.isNotBlank()
        override val isFocusable: Boolean get() = hasAutofillId
    }

    private fun container(
        vararg children: FormNode,
        className: String = "android.widget.LinearLayout",
        webDomain: String = "",
        excludes: Boolean = false
    ) = Node(
        className = className,
        inputType = 0,
        webDomain = webDomain,
        excludesDescendantsFromAutofill = excludes,
        hasAutofillId = false,
        isTextAutofillType = false,
        children = children.toList()
    )

    private fun label(text: String) = Node(
        className = "android.widget.TextView",
        inputType = 0,
        text = text,
        hasAutofillId = false,
        isTextAutofillType = false
    )

    private val textPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    private val textEmail = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    private fun analyze(root: FormNode, pkg: String = "com.example.app", manual: Boolean = false) =
        LoginFormAnalyzer.analyze(listOf(root), pkg, "com.biometricpass.app", manual)

    @Test
    fun `native login form detects username and password`() {
        val user = Node(idEntry = "login_email", inputType = textEmail, autofillText = "alice@example.com")
        val pass = Node(idEntry = "login_pwd", inputType = textPassword, autofillText = "s3cret!")
        val result = analyze(container(user, pass))

        assertEquals("com.example.app", result.packageName)
        assertSame(user, result.usernameNode)
        assertSame(pass, result.passwordNode)
        assertEquals("alice@example.com", result.usernameText)
        assertEquals("s3cret!", result.passwordText)
    }

    @Test
    fun `browser address bar is ignored while web login fields are detected`() {
        val omnibox = Node(idEntry = "url_bar", hint = "Search or type URL")
        val webUser = Node(htmlTag = "input", htmlAttributes = listOf("type" to "email", "name" to "identifier"))
        val webPass = Node(htmlTag = "input", htmlAttributes = listOf("type" to "password", "name" to "passwd"))
        val webRoot = container(webUser, webPass, className = "android.webkit.WebView", webDomain = "accounts.google.com")
        val result = analyze(container(omnibox, webRoot), pkg = "com.android.chrome")

        assertEquals("accounts.google.com", result.webDomain)
        assertSame(webUser, result.usernameNode)
        assertSame(webPass, result.passwordNode)
        assertTrue(omnibox !in result.editableNodes)
    }

    @Test
    fun `multiline chat input is never treated as a credential field`() {
        val chat = Node(
            idEntry = "chathistory_message_edit",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        val result = analyze(container(chat), pkg = "jp.naver.line.android")

        assertNull(result.usernameNode)
        assertNull(result.passwordNode)
        assertTrue(result.editableNodes.isEmpty())
    }

    @Test
    fun `search box is excluded`() {
        val search = Node(idEntry = "search_src_text", hint = "Search")
        val result = analyze(container(search))
        assertTrue(result.editableNodes.isEmpty())
    }

    @Test
    fun `chinese label inherited from a single-input container marks the password field`() {
        val accountRow = container(label("帳號"), Node(idEntry = "et_1"))
        val passwordRow = container(label("密碼"), Node(idEntry = "et_2"))
        val result = analyze(container(accountRow, passwordRow))

        assertSame(passwordRow.children[1], result.passwordNode)
        assertSame(accountRow.children[1], result.usernameNode)
    }

    @Test
    fun `unlabeled field right before a masked password becomes the companion username`() {
        val first = Node(idEntry = "et_1")
        val pass = Node(idEntry = "et_2", inputType = textPassword)
        val result = analyze(container(first, pass))

        assertSame(pass, result.passwordNode)
        assertSame(first, result.usernameNode)
    }

    @Test
    fun `standalone visible-password field is not promoted without a preceding username`() {
        val visible = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val alone = analyze(container(Node(idEntry = "promo_code", inputType = visible)))
        assertNull(alone.passwordNode)

        val user = Node(idEntry = "username")
        val pwd = Node(idEntry = "field2", inputType = visible)
        val withUser = analyze(container(user, pwd))
        assertSame(pwd, withUser.passwordNode)
    }

    @Test
    fun `one-time-code web input is excluded`() {
        val otp = Node(htmlTag = "input", htmlAttributes = listOf("autocomplete" to "one-time-code"))
        val result = analyze(container(otp, className = "android.webkit.WebView", webDomain = "bank.example"))
        assertTrue(result.editableNodes.isEmpty())
    }

    @Test
    fun `excluded subtrees are skipped unless the user requested autofill manually`() {
        val pass = Node(idEntry = "pwd", inputType = textPassword)
        val root = container(container(pass, excludes = true))

        assertNull(analyze(root).passwordNode)
        assertSame(pass, analyze(root, manual = true).passwordNode)
    }

    @Test
    fun `autofill hints take priority over keyword heuristics`() {
        val user = Node(idEntry = "field_password_like", autofillHints = listOf("emailAddress"))
        val pass = Node(idEntry = "field_b", autofillHints = listOf("password"))
        val result = analyze(container(user, pass))

        assertSame(user, result.usernameNode)
        assertSame(pass, result.passwordNode)
    }

    @Test
    fun `taishin bank 3-field form with masked ID and masked User ID maps all 3 fields accurately`() {
        // Flat ConstraintLayout with sibling TextViews and all 3 EditTexts masked with textPassword,
        // even when cursor focus is currently on the middle User ID ("使用者代號") box!
        val natIdInput = Node(idEntry = "et_a", inputType = textPassword, autofillText = "A123456789")
        val userCodeInput = Node(
            idEntry = "et_b",
            inputType = textPassword,
            isFocused = true,
            autofillText = "jongren88"
        )
        val pwdInput = Node(idEntry = "et_c", inputType = textPassword, autofillText = "TaishinPass!9")

        val flatLayout = container(
            label("身分證字號"),
            natIdInput,
            label("使用者代號"),
            userCodeInput,
            label("使用者密碼"),
            pwdInput,
            className = "androidx.constraintlayout.widget.ConstraintLayout"
        )

        val result = analyze(flatLayout, pkg = "tw.com.taishinbank.mobile")
        assertSame(natIdInput, result.usernameNode)
        assertSame(userCodeInput, result.secondaryUsernameNode)
        assertSame(pwdInput, result.passwordNode)
        assertEquals("A123456789", result.usernameText)
        assertEquals("jongren88", result.secondaryUsernameText)
        assertEquals("TaishinPass!9", result.passwordText)
        assertEquals(
            listOf("A123456789", "jongren88"),
            result.capturedFields.filter { !it.isPasswordMask }.map { it.value }
        )
    }

    @Test
    fun `unlabeled 3-field all-masked bank login never confuses middle User ID box with password`() {
        val box1 = Node(idEntry = "f1", inputType = textPassword, autofillText = "A123456789")
        val box2 = Node(idEntry = "f2", inputType = textPassword, isFocused = true, autofillText = "my_code")
        val box3 = Node(idEntry = "f3", inputType = textPassword, autofillText = "real_secret")

        val result = analyze(container(box1, box2, box3), pkg = "com.bank.app")
        assertSame(box1, result.usernameNode)
        assertSame(box2, result.secondaryUsernameNode)
        assertSame(box3, result.passwordNode)
    }

    @Test
    fun `bank form where password uses non-autofillable custom keypad never mistakes masked User ID for password`() {
        val natIdInput = Node(hint = "請輸入身分證字號", inputType = textPassword)
        val userCodeInput = Node(hint = "請輸入使用者代號", inputType = textPassword, isFocused = true)

        val result = analyze(container(natIdInput, userCodeInput), pkg = "tw.com.taishinbank.mobile")
        assertNull("Masked User ID must NEVER be classified as passwordNode", result.passwordNode)
        assertSame(natIdInput, result.usernameNode)
        assertSame(userCodeInput, result.secondaryUsernameNode)
    }

    @Test
    fun `sign-up form with confirm password keeps both password fields and no secondary username`() {
        val user = Node(hint = "帳號")
        val pwd1 = Node(hint = "設定密碼", inputType = textPassword, autofillText = "SamePass!1")
        val pwd2 = Node(hint = "確認密碼", inputType = textPassword, autofillText = "SamePass!1")

        val result = analyze(container(user, pwd1, pwd2))
        assertSame(user, result.usernameNode)
        assertNull(result.secondaryUsernameNode)
        assertSame(pwd1, result.passwordNode)
        assertEquals(listOf(pwd1, pwd2), result.passwordNodes)
    }
}
