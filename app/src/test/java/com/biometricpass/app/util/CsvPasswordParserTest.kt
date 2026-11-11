package com.biometricpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CsvPasswordParserTest {

    @Test
    fun `parses chrome csv with quoted commas, escaped quotes, and android app uris`() {
        val csv = "\uFEFF" + """
            name,url,username,password,note
            "Google",https://accounts.google.com/ServiceLogin,alice@gmail.com,"My,Pass""123",ignored
            "Shopee App",android://abc123hash@com.shopee.tw/,shopee_user,Shop#999,
            "EmptyPass",https://example.com,nobody,,
        """.trimIndent()

        val parsed = CsvPasswordParser.parse(csv)
        assertEquals(2, parsed.size)

        assertEquals("Google", parsed[0].title)
        assertEquals("", parsed[0].packageName)
        assertEquals("accounts.google.com", parsed[0].webDomain)
        assertEquals("alice@gmail.com", parsed[0].username)
        assertEquals("My,Pass\"123", parsed[0].password)

        assertEquals("Shopee App", parsed[1].title)
        assertEquals("com.shopee.tw", parsed[1].packageName)
        assertEquals("", parsed[1].webDomain)
        assertEquals("shopee_user", parsed[1].username)
        assertEquals("Shop#999", parsed[1].password)
    }

    @Test
    fun `rejects csv without password header`() {
        try {
            CsvPasswordParser.parse("title,url,username\nTest,https://a.com,u")
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
