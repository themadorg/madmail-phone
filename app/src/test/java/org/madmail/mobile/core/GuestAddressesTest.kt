package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test

class GuestAddressesTest {
    @Test fun wifiCgnatIsNotTailscaleAndVpnWinsOverUnderlyingWifi() {
        assertEquals(GuestNetwork.Wifi, GuestAddresses.classify("wlan0", wifi = true))
        assertTrue(GuestAddresses.ipv4("100.64.90.15"))
        assertEquals(GuestNetwork.Vpn, GuestAddresses.classify("tun0", vpn = true, wifi = true))
        assertEquals(GuestNetwork.Mobile, GuestAddresses.classify("rmnet1"))
        assertEquals(GuestNetwork.Mobile, GuestAddresses.classify("ccmni0"))
        assertEquals(GuestNetwork.Mobile, GuestAddresses.classify("r_rmnet_data0", cellular = true))
        assertEquals(GuestNetwork.Vpn, GuestAddresses.classify("tun0", vpn = true, cellular = true))
        assertNull(GuestAddresses.classify("lo"))
        assertEquals(GuestNetwork.Hotspot, GuestAddresses.classify("ap0"))
    }
    @Test fun remoteDiscoveryKeepsPublicLanAndTailscaleSeparate() {
        val addresses = GuestAddresses.remote("2: eth0 inet 192.168.1.5/24 scope global eth0\n3: tailscale0 inet 100.80.1.2/32 scope global tailscale0\n1: lo inet 127.0.0.1/8 scope host lo")
        assertEquals(listOf(GuestNetwork.Ethernet, GuestNetwork.Vpn), addresses.map { it.network })
        assertEquals("https://100.80.1.2:18443", addresses.last().origin)
        assertNull(GuestAddresses.origin("127.0.0.1"))
        assertEquals("https://server.tailnet.ts.net:18443", GuestAddresses.origin("server.tailnet.ts.net"))
    }
    @Test fun invalidAndUnroutableCandidatesAreRejected() {
        listOf("127.1.2.3", "0.0.0.0", "169.254.1.2", "224.0.0.1", "192.0.0.4", "256.1.2.3", "not-an-ip").forEach { assertFalse(GuestAddresses.ipv4(it)) }
    }
    @Test fun mobileDataIsLastResortBehindLanAndVpn() {
        val wifi = GuestAddress("192.168.1.5", GuestNetwork.Wifi, "wlan0")
        val vpn = GuestAddress("100.80.1.2", GuestNetwork.Vpn, "tun0")
        val mobile = GuestAddress("10.20.30.40", GuestNetwork.Mobile, "rmnet0")
        assertEquals(wifi, PhoneMailDomain.preferred(listOf(mobile, vpn, wifi)))
        assertEquals(vpn, PhoneMailDomain.preferred(listOf(mobile, vpn)))
        assertEquals(mobile, PhoneMailDomain.preferred(listOf(mobile)))
    }
}
