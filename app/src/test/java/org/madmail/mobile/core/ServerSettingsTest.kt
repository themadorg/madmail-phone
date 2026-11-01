package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test

class ServerSettingsTest {
    @Test fun phonePortsRejectPrivilegedAndOutOfRangeValues() {
        val setting = serverSettings.single { it.key == "turn_port" }
        assertFalse(setting.valid("443", true)); assertTrue(setting.valid("443", false))
        assertTrue(setting.valid("3478", true)); assertFalse(setting.valid("65536", false))
        assertFalse(setting.valid("0", false)); assertFalse(setting.valid("-1", false))
    }
    @Test fun durationAndSizeValidationKeepsUnits() {
        val duration = serverSettings.single { it.key == "message_retention" }
        assertTrue(duration.valid("30d", true)); assertTrue(duration.valid("24h30m", true))
        assertFalse(duration.valid("forever", true)); assertFalse(duration.valid("-1d", true))
        val size = serverSettings.single { it.key == "max_message_size" }
        assertTrue(size.valid("25M", true)); assertFalse(size.valid("lots", true))
        assertFalse(size.valid("25MB", true)); assertFalse(size.valid("1.5M", true))
        assertEquals("1,000 MiB", size.display("1000M", java.util.Locale.US))
        assertEquals("30 days", duration.display("30d", java.util.Locale.US))
    }
    @Test fun stableManagementEndpointsCannotBeEdited() {
        for (key in listOf("http_port", "https_port", "imap_tls_port", "submission_tls_port", "admin_path")) {
            assertEquals(SettingKind.Managed, serverSettings.single { it.key == key }.kind)
        }
    }
    @Test fun catalogUsesUniqueKeysAndDocumentedFederationValues() {
        assertEquals(serverSettings.size, serverSettings.map { it.key }.distinct().size)
        val policy = serverSettings.single { it.key == "federation_policy" }
        assertTrue(policy.valid("ACCEPT", true)); assertTrue(policy.valid("REJECT", true)); assertFalse(policy.valid("allowlist", true))
    }
}
