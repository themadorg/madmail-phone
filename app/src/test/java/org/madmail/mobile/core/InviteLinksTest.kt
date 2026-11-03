package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test

class InviteLinksTest {
    @Test fun keepsPublicPortAndEncodesToken() {
        assertEquals("https://192.168.1.2:18443/inv/a%2Bb%2Fc", InviteLinks.invite("https://192.168.1.2:18443/", "a+b/c"))
    }
    @Test fun refusesOriginsWithCredentialsPathsOrLoopback() {
        listOf("http://host", "https://user:pass@host", "https://host/new", "https://host?token=secret", "https://host:0", "https://127.0.0.1", "https://[::1]").forEach { assertNull(it, InviteLinks.origin(it)) }
    }
    @Test fun localLoginKeepsPasswordAndPortsButUsesGuestReachableHost() {
        val result = InviteLinks.login("dclogin:user@madmail.local/?p=a%2Bb%26c&v=1&ih=localhost&ip=11993&is=ssl&sh=localhost&sp=11465&ss=ssl&ic=3", "https://192.168.1.2:18443")
        assertTrue(result.startsWith("dclogin://user@madmail.local/"))
        assertTrue(result.contains("p=a%2Bb%26c"))
        assertTrue(result.contains("ih=192.168.1.2"))
        assertTrue(result.contains("ip=11993"))
        assertTrue(result.contains("sp=11465"))
        assertTrue(result.contains("sc=3"))
        assertFalse(result.contains("localhost"))
    }
    @Test fun ipAccountDomainPassesThroughUnchanged() {
        val result = InviteLinks.login("dclogin:abcd@[100.64.90.15]/?p=p&v=1&ih=100.64.90.15&ip=11993&is=ssl&sh=100.64.90.15&sp=11465&ss=ssl&ic=3", "https://100.64.90.15:18443")
        assertTrue(result.startsWith("dclogin://abcd@[100.64.90.15]/"))
        assertTrue(result.contains("ih=100.64.90.15"))
    }
    @Test fun ipv6HostAndStrictCertificatesArePreserved() {
        val result = InviteLinks.login("dclogin:u@example.org/?p=p&v=1&ip=993&is=ssl&sp=465&ss=ssl&ic=1", "https://[fd00::1]:18443")
        assertTrue(result.contains("ih=fd00%3A%3A1"))
        assertTrue(result.contains("sc=1"))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsIncompleteServerLogin() {
        InviteLinks.login("dclogin:u@example.org/?p=p&v=1", "https://example.org")
    }
}
