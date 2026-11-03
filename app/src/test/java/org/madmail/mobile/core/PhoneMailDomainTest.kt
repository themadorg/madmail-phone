package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test

class PhoneMailDomainTest {
    private val wifi = GuestAddress("192.168.1.23", GuestNetwork.Wifi, "wlan0")
    private val vpn = GuestAddress("100.64.90.15", GuestNetwork.Vpn, "tun0")

    @Test fun freshInstallUsesTheIpAndNoLocalName() {
        val config = PhoneMailDomain.render("192.168.1.23", null, emptyList())
        assertTrue(config.contains("hostname = \"192.168.1.23\""))
        assertTrue(config.contains("primary_domain = \"[192.168.1.23]\""))
        assertFalse(config.contains("local_domains"))
        assertFalse(config.contains(".local"))
        assertTrue(config.contains("http_tls_listen = \"0.0.0.0:18443\""))
    }
    @Test fun upgradeKeepsMadmailLocalAccountsDeliverable() {
        val old = "hostname = \"madmail.local\"\nprimary_domain = \"madmail.local\"\ntls_mode = \"self_signed\"\n"
        val (primary, locals) = PhoneMailDomain.previous(old)
        assertEquals("madmail.local", primary)
        assertTrue(locals.isEmpty())
        val config = PhoneMailDomain.render("192.168.1.23", primary, locals)
        assertTrue(config.contains("primary_domain = \"[192.168.1.23]\""))
        assertTrue(config.contains("local_domains = \"madmail.local\""))
    }
    @Test fun domainHistoryOnlyGrowsWithoutDuplicates() {
        var config = PhoneMailDomain.render("192.168.1.23", "madmail.local", emptyList())
        for (ip in listOf("100.64.90.15", "192.168.1.23")) {
            val (primary, locals) = PhoneMailDomain.previous(config)
            config = PhoneMailDomain.render(ip, primary, locals)
        }
        assertTrue(config.contains("primary_domain = \"[192.168.1.23]\""))
        assertTrue(config.contains("local_domains = \"madmail.local [100.64.90.15]\""))
        val (_, locals) = PhoneMailDomain.previous(config.replace("[100.64.90.15]", "100.64.90.15 [100.64.90.15]"))
        assertEquals(listOf("madmail.local", "[100.64.90.15]", "[100.64.90.15]"), locals)
        assertTrue(PhoneMailDomain.render("192.168.1.23", "[192.168.1.23]", locals).contains("local_domains = \"madmail.local [100.64.90.15]\""))
    }
    @Test fun malformedEntriesAreDropped() {
        val (primary, locals) = PhoneMailDomain.previous("primary_domain = \"a\\\"b\"\nlocal_domains = \"ok.example bad'x\"\n")
        assertNull(primary)
        assertEquals(listOf("ok.example"), locals)
    }
    @Test fun resolveOrder() {
        assertEquals("100.64.90.15", PhoneMailDomain.resolve("https://100.64.90.15:18443", listOf(wifi, vpn), null))
        assertEquals("192.168.1.23", PhoneMailDomain.resolve("https://10.0.0.9:18443", listOf(vpn, wifi), null))
        assertEquals("192.168.1.23", PhoneMailDomain.resolve("https://phone.tailnet.ts.net:18443", listOf(wifi), null))
        assertEquals("100.64.90.15", PhoneMailDomain.resolve(null, listOf(vpn), null))
        assertEquals("10.0.0.9", PhoneMailDomain.resolve(null, emptyList(), "[10.0.0.9]"))
        assertNull(PhoneMailDomain.resolve(null, emptyList(), "madmail.local"))
        assertNull(PhoneMailDomain.resolve(null, emptyList(), null))
    }
}
