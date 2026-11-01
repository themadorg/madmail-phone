package org.madmail.mobile.core

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/** Pure URI transformations. Never fetch a pasted URL or send credentials to it. */
internal object InviteLinks {
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun origin(input: String): String? {
        val uri = runCatching { URI(input.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.path !in listOf("", "/") || uri.port == 0 || uri.port > 65535) return null
        if (uri.host.lowercase() in listOf("localhost", "127.0.0.1", "0.0.0.0", "[::1]", "[::]")) return null
        return "https://${uri.rawAuthority}"
    }
    fun invite(origin: String, token: String): String = "${requireNotNull(origin(origin))}/inv/${encode(token)}"
    fun login(serverLink: String, origin: String): String {
        val host = URI(requireNotNull(origin(origin))).host.removeSurrounding("[", "]")
        require(serverLink.startsWith("dclogin:", ignoreCase = true))
        val raw = serverLink.substringAfter(':').removePrefix("//")
        val account = raw.substringBefore('?').removeSuffix("/")
        require(account.contains('@') && !account.contains('#'))
        val params = raw.substringAfter('?', "").split('&').associate { field ->
            val pair = field.split('=', limit = 2)
            URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair.getOrElse(1) { "" }, "UTF-8")
        }.toMutableMap()
        require(params["v"] == "1" && !params["p"].isNullOrEmpty())
        require(params["ip"]?.toIntOrNull() in 1..65535 && params["sp"]?.toIntOrNull() in 1..65535)
        require(params["is"] == "ssl" && params["ss"] == "ssl") { "This server did not provide TLS connection settings." }
        // Host override is explicit in the UI; ports and credentials come from this managed server.
        params["ih"] = host; params["sh"] = host
        params["sc"] = params["ic"] ?: "1"
        return "dclogin://$account/?" + params.entries.joinToString("&") { "${encode(it.key)}=${encode(it.value)}" }
    }
}
