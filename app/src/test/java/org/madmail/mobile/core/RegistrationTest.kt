package org.madmail.mobile.core
import org.junit.Assert.*
import org.junit.Test
class RegistrationTest {
    @Test fun preservesInvitationToken() {
        assertEquals("DCACCOUNT:https://mail.example.org/new?t=a%2Bb", Registration.payload(" https://mail.example.org/new?t=a%2Bb "))
    }
    @Test fun rejectsUnsafeOrMalformedEndpoints() {
        listOf("", "http://host/new", "https://user:pass@host/new", "https://host/#token", "https://host:99999/new", "https://host:0/new", "https://bad host/new").forEach { assertNull(it, Registration.payload(it)) }
    }
}
