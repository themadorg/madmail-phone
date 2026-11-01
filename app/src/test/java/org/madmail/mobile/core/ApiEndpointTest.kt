package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.engine.ApiEndpoint

class ApiEndpointTest {
    @Test fun normalizesHttpsAndPreservesExplicitAdminPath() {
        assertEquals("https://mail.example.org/api/admin", ApiEndpoint.normalize("https://mail.example.org/"))
        assertEquals("https://mail.example.org:18443/custom/admin", ApiEndpoint.normalize("https://mail.example.org:18443/custom/admin/"))
    }
    @Test fun rejectsCredentialBearingOrInsecureEndpoints() {
        listOf("http://host/api/admin", "https://user:secret@host/api/admin", "https://host/api/admin?token=x", "https://host/#token", "https://host:0").forEach { assertNull(ApiEndpoint.normalize(it)) }
    }
}
