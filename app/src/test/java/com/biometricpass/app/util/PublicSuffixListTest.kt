package com.biometricpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PublicSuffixListTest {

    companion object {
        /** The real bundled list (supports running with either `:app` or repo root as working directory). */
        val bundled: PublicSuffixList by lazy {
            val file = sequenceOf(
                File("src/main/assets/public_suffix_list.dat"),
                File("app/src/main/assets/public_suffix_list.dat")
            ).first { it.isFile }
            file.bufferedReader().useLines { PublicSuffixList.parse(it) }
        }
    }

    @Test
    fun `registrable domain follows normal, wildcard and exception rules`() {
        assertEquals("google.com", bundled.registrableDomain("accounts.google.com"))
        assertEquals("google.com", bundled.registrableDomain("Google.COM."))
        assertEquals("bbc.co.uk", bundled.registrableDomain("www.bbc.co.uk"))
        assertEquals("alice.github.io", bundled.registrableDomain("alice.github.io"))
        assertEquals("alice.github.io", bundled.registrableDomain("docs.alice.github.io"))
        // Wildcard *.kobe.jp with exception !city.kobe.jp
        assertEquals("city.kobe.jp", bundled.registrableDomain("www.city.kobe.jp"))
        assertEquals("foo.bar.kobe.jp", bundled.registrableDomain("foo.bar.kobe.jp"))
    }

    @Test
    fun `public suffixes, single labels and ip addresses have no registrable domain`() {
        assertNull(bundled.registrableDomain("com"))
        assertNull(bundled.registrableDomain("github.io"))
        assertNull(bundled.registrableDomain("co.uk"))
        assertNull(bundled.registrableDomain("localhost"))
        assertNull(bundled.registrableDomain("192.168.1.1"))
        assertNull(bundled.registrableDomain(""))
    }

    @Test
    fun `unknown tlds fall back to the default rule`() {
        assertEquals("example.unknowntld", bundled.registrableDomain("www.example.unknowntld"))
        assertEquals("example.com", PublicSuffixList.DEFAULT_RULE_ONLY.registrableDomain("a.example.com"))
    }

    @Test
    fun `domain matcher allows parent and sub-domains of the same site only`() {
        assertTrue(WebDomainMatcher.matches("google.com", "accounts.google.com", bundled))
        assertTrue(WebDomainMatcher.matches("accounts.google.com", "google.com", bundled))
        assertTrue(WebDomainMatcher.matches("Accounts.Google.com", "accounts.google.com", bundled))
        assertFalse(WebDomainMatcher.matches("google.com", "evilgoogle.com", bundled))
        assertFalse(WebDomainMatcher.matches("mail.google.com", "accounts.google.com", bundled))
        assertFalse(WebDomainMatcher.matches("google.com", "", bundled))
    }

    @Test
    fun `domain matcher never crosses public suffix boundaries`() {
        assertFalse(WebDomainMatcher.matches("alice.github.io", "bob.github.io", bundled))
        assertFalse(WebDomainMatcher.matches("github.io", "attacker.github.io", bundled))
        assertFalse(WebDomainMatcher.matches("co.uk", "bank.co.uk", bundled))
        assertTrue(WebDomainMatcher.matches("alice.github.io", "docs.alice.github.io", bundled))
    }
}
