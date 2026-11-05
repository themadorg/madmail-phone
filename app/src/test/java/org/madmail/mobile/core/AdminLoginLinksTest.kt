package org.madmail.mobile.core

import org.junit.Assert.*
import org.junit.Test
import org.madmail.mobile.engine.ApiCredential
import java.util.Base64

class AdminLoginLinksTest {
    private val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val endpoint = "https://203.0.113.7/api/admin"
    private val expected = ApiCredential(endpoint, token)
    // Same encoding as `madmail admin-token`: URL-safe base64 of {"u","t"} without padding.
    private fun payload(json: String = """{"u":"$endpoint","t":"$token"}""") =
        Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @Test fun readsTerminalQrPayload() {
        assertEquals(expected, AdminLoginLinks.parse(payload()))
        assertEquals(expected, AdminLoginLinks.parse("  #${payload()}\n"))
    }

    @Test fun readsWebPanelLink() {
        assertEquals(expected, AdminLoginLinks.parse("https://admin.madmail.chat/#${payload()}"))
        assertEquals(expected, AdminLoginLinks.parse("https://ADMIN.madmail.chat#${payload()}"))
    }

    @Test fun readsLegacyAndAppSchemeForms() {
        assertEquals(expected, AdminLoginLinks.parse("https://admin.madmail.chat/?madmail_u=https%3A%2F%2F203.0.113.7%2Fapi%2Fadmin&madmail_t=$token"))
        assertEquals(expected, AdminLoginLinks.parse("madmail-admin://connect?u=https%3A%2F%2F203.0.113.7%2Fapi%2Fadmin&t=$token"))
        assertEquals(expected, AdminLoginLinks.parse("""{"url":"$endpoint","token":"$token"}"""))
    }

    @Test fun acceptsPaddedAndStandardAlphabet() {
        val standard = Base64.getEncoder().encodeToString("""{"u":"$endpoint","t":"$token"}""".toByteArray())
        assertEquals(expected, AdminLoginLinks.parse(standard))
    }

    @Test fun bareOriginGainsAdminPath() {
        assertEquals(ApiCredential(endpoint, token), AdminLoginLinks.parse(payload("""{"u":"https://203.0.113.7","t":"$token"}""")))
    }

    @Test fun rejectsUnsafeOrIncompletePayloads() {
        listOf(
            payload("""{"u":"http://203.0.113.7/api/admin","t":"$token"}"""),
            payload("""{"u":"https://user:pw@203.0.113.7/api/admin","t":"$token"}"""),
            payload("""{"u":"$endpoint"}"""),
            payload("""{"u":"$endpoint","t":""}"""),
            payload("""{"u":"$endpoint","t":null}"""),
            payload("""{"u":"$endpoint","t":"a\u0000b"}"""),
            payload("not json"),
            "https://admin.madmail.chat/",
            "https://admin.madmail.chat/#garbage!!",
            "http://admin.madmail.chat/#${payload()}",
            "mail.example.org",
            "",
            "dclogin://user@example.org?p=x",
        ).forEach { assertNull(it, AdminLoginLinks.parse(it)) }
    }
}
