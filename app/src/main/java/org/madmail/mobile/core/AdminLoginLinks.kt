package org.madmail.mobile.core

import org.json.JSONObject
import org.madmail.mobile.engine.ApiCredential
import org.madmail.mobile.engine.ApiEndpoint
import java.net.URI
import java.net.URLDecoder
import java.util.Base64

/**
 * Admin sign-in handed out by `madmail admin-token`, in every form the web panel accepts:
 * the terminal QR (a bare base64url JSON `{"u","t"}`), the `https://admin.madmail.chat/#…` link,
 * `madmail-admin://connect?u=…&t=…`, legacy `?madmail_u=…&madmail_t=…` and plain JSON.
 *
 * Pure parsing; never fetches. The token is only ever sent to the endpoint it arrived with,
 * so the endpoint passes the same HTTPS-only checks as one typed by hand.
 */
internal object AdminLoginLinks {
    const val WEB_PANEL_HOST = "admin.madmail.chat"
    const val SCHEME = "madmail-admin"
    private const val MAX_INPUT = 16384

    fun parse(raw: String): ApiCredential? {
        val text = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_INPUT } ?: return null
        return fromUri(text) ?: fromPayload(text.removePrefix("#")) ?: fromJson(text)
    }

    private fun fromUri(text: String): ApiCredential? {
        val uri = runCatching { URI(text) }.getOrNull()?.takeIf { it.isAbsolute } ?: return null
        when (uri.scheme.lowercase()) {
            "https" -> {}
            SCHEME -> return query(uri.rawSchemeSpecificPart.substringAfter('?', "").substringBefore('#'))
            else -> return null
        }
        uri.rawFragment?.takeIf { it.isNotEmpty() }?.let { fromPayload(it) }?.let { return it }
        return uri.rawQuery?.let(::query)
    }

    private fun query(raw: String): ApiCredential? {
        val params = raw.split('&').filter { it.isNotEmpty() }.associate { field ->
            val pair = field.split('=', limit = 2)
            decode(pair[0]) to decode(pair.getOrElse(1) { "" })
        }
        return credential(params["madmail_u"] ?: params["u"], params["madmail_t"] ?: params["t"])
    }

    private fun fromPayload(encoded: String): ApiCredential? {
        // Both alphabets and optional padding, like the web panel's decoder.
        val normalized = encoded.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        if (normalized.isEmpty() || normalized.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' && it != '-' && it != '_' }) return null
        val bytes = runCatching { Base64.getUrlDecoder().decode(normalized) }.getOrNull() ?: return null
        return fromJson(String(bytes, Charsets.UTF_8))
    }

    private fun fromJson(text: String): ApiCredential? {
        if (!text.trimStart().startsWith("{")) return null
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        fun field(vararg names: String) = names.firstNotNullOfOrNull { json.opt(it) as? String }
        return credential(field("u", "url"), field("t", "token"))
    }

    private fun credential(url: String?, token: String?): ApiCredential? {
        val endpoint = url?.let(ApiEndpoint::normalize) ?: return null
        val secret = token?.trim()?.takeIf { it.isNotEmpty() && it.length <= 8192 && it.none(Char::isISOControl) } ?: return null
        return ApiCredential(endpoint, secret)
    }

    private fun decode(value: String) = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault("")
}
