package org.madmail.mobile.core

import java.net.URI

/** Accept only the documented HTTPS registration endpoint form. Never invent tokens. */
object Registration {
    fun payload(endpoint: String): String? {
        val value = endpoint.trim()
        if (value.isEmpty() || value.length > 1500) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
            uri.fragment != null || uri.port == 0 || uri.port > 65535) return null
        return "DCACCOUNT:$value"
    }
}
